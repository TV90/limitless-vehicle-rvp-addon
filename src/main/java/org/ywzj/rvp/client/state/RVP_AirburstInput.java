package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.ywzj.rvp.network.C2SSetAirburstRange;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_FuseData;
import org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore;
import org.ywzj.rvp.weapon.util.RVP_WeaponLeafResolver;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * 锁定键（R）测距：当前 RVP 武器启用可编程空爆时，替代火控锁定。
 *
 * <p>2026-10-22：多弹种/分组槽（{@code modding_only_multi} /
 * {@code merge_into_previous_slot}）的 {@code getCurrentWeapon()} 返回外层
 * {@code VehicleMultiWeapons}（基类本体类，非 RVP 武器），必须经
 * {@link RVP_WeaponLeafResolver} 解到叶子武器（如 T90M HE 组的 3OF82）；
 * 否则 instanceof 落空、R 键静默回落本体锁定，表现为「无法装订空爆引信」。</p>
 */
public final class RVP_AirburstInput {

    private RVP_AirburstInput() {}

    /**
     * @return true 表示已处理测距，调用方应跳过 {@link WeaponUnit#fireControlLock()}。
     */
    public static boolean tryMeasureOnLockKey(WeaponUnit weaponUnit) {
        if (weaponUnit == null || weaponUnit.getCurrentWeapon().isEmpty()) {
            return false;
        }
        // 分组/多弹种槽：解到当前选中的叶子武器（如 HE 组里的 3OF82），外层 multi 不是 RVP 武器
        AbstractVehicleWeapon<?> resolved = RVP_WeaponLeafResolver.resolveLeaf(weaponUnit).orElse(null);
        if (!(resolved instanceof RVP_WeaponBase weapon)) {
            return false;
        }
        int leafIndex = weapon.getIndex();
        // ahead 武器的装订方式是锁定目标（R 键走 fireControlLock，由 programForShot 解算引信距离），
        // 不走测距装订；未锁定时弹丸照常直射（像普通机炮）
        if (weapon.getData().getFuseData().isAheadEnabled()) {
            return false;
        }
        RVP_FuseData fuse = weapon.getData().getFuseData();
        if (!fuse.isProgrammableAirburst()) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null) {
            return false;
        }

        int dist = RVP_AirburstMeasureUtil.measureDistanceMeters(weaponUnit);
        int min = fuse.getAirburstMeasureMin();
        int max = fuse.getAirburstMeasureMax();
        if (dist <= min || dist >= max) {
            RVP_AirburstRangeStore.set(vehicle, weaponUnit, leafIndex, 0);
            RVP_Network.CHANNEL.sendToServer(C2SSetAirburstRange.of(vehicle.getId(), weaponUnit.getIndex(), leafIndex, 0));
            mc.player.displayClientMessage(Component.translatable("message.ywzj_rvp.airburst.invalid"), true);
            return true;
        }

        RVP_AirburstRangeStore.set(vehicle, weaponUnit, leafIndex, dist);
        RVP_Network.CHANNEL.sendToServer(C2SSetAirburstRange.of(
                vehicle.getId(), weaponUnit.getIndex(), leafIndex, dist));
        float offset = fuse.getAirburstOffset();
        mc.player.displayClientMessage(
                Component.translatable("message.ywzj_rvp.airburst.set", dist, String.format("%.0f", offset)),
                true);
        return true;
    }
}
