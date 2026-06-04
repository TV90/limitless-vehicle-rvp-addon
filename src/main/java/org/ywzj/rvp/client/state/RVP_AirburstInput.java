package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.ywzj.rvp.network.C2SSetAirburstRange;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.data.RVP_FuseData;
import org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * 锁定键（R）测距：当前 RVP 武器启用可编程空爆时，替代火控锁定。
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
        AbstractVehicleWeapon<?> current = weaponUnit.getCurrentWeapon().get();
        if (!(current instanceof RVP_WeaponBase weapon)) {
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
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.getVehicle();
        if (vehicle == null) {
            return false;
        }

        int dist = RVP_AirburstMeasureUtil.measureDistanceMeters(weaponUnit);
        int min = fuse.getAirburstMeasureMin();
        int max = fuse.getAirburstMeasureMax();
        if (dist <= min || dist >= max) {
            RVP_AirburstRangeStore.set(vehicle, weaponUnit, weapon.getIndex(), 0);
            RVP_Network.CHANNEL.sendToServer(C2SSetAirburstRange.of(vehicle.getId(), weaponUnit.getIndex(), weapon.getIndex(), 0));
            mc.player.displayClientMessage(Component.translatable("message.ywzj_rvp.airburst.invalid"), true);
            return true;
        }

        RVP_AirburstRangeStore.set(vehicle, weaponUnit, weapon.getIndex(), dist);
        RVP_Network.CHANNEL.sendToServer(C2SSetAirburstRange.of(
                vehicle.getId(), weaponUnit.getIndex(), weapon.getIndex(), dist));
        float offset = fuse.getAirburstOffset();
        mc.player.displayClientMessage(
                Component.translatable("message.ywzj_rvp.airburst.set", dist, String.format("%.0f", offset)),
                true);
        return true;
    }
}
