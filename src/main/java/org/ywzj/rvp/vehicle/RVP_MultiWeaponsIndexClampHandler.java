package org.ywzj.rvp.vehicle;

import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

/**
 * 多弹种槽选中索引越界防护（2026-10-12 崩服事故后订立）。
 *
 * <p>背景：本体 {@code VehicleMultiWeapons} 把 {@code selectedIndex} 存入载具 NBT 且
 * {@code deserializeNBT} 不夹取，而 {@code getSelectedWeapon()} 直接
 * {@code subWeapons.get(selectedIndex)} 无边界检查。一旦缩短某槽的 {@code ids} 条数，
 * 旧存档里的 {@code SelectedIndex} 就会越界；RVP 侧 {@code RVP_WeaponBase.tick}
 * 每 tick 都会经 {@code RVP_WeaponResolveHelper.unwrap} 读到它，导致服务端载具 tick
 * 每 tick 抛 {@code IndexOutOfBoundsException}，**进世界即崩**。</p>
 *
 * <p>处理：载具加入世界（反序列化后、首次 tick 前）时，把越界的
 * {@code selectedIndex} 夹回 {@code [0, subWeapons.size()-1]}。全部使用本体公开 API
 * （{@code AbstractVehicle.getPartUnits()} / {@code WeaponUnit.getIndexedWeapons()} /
 * {@code VehicleMultiWeapons.getSubWeapons/getSelectedIndex/setSelectedIndex}），
 * 不新增 Mixin、不改本体源码。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_MultiWeaponsIndexClampHandler {

    private RVP_MultiWeaponsIndexClampHandler() {}

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // 目的：仅服务端执行（服务端权威，夹取结果随后经同步下发客户端），与既有
        // RVP_EcmActiveEventHandler / RVP_CountermeasureEventHandler 的端判定保持一致。
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractVehicle vehicle)) {
            return;
        }
        clampMultiWeaponsIndex(vehicle);
    }

    /**
     * 遍历载具全部武器站，把多弹种槽越界的选中索引夹回合法范围。
     *
     * @param vehicle 已从存档载入的载具实体
     */
    private static void clampMultiWeaponsIndex(AbstractVehicle vehicle) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {          // 调用本体公开 API，遍历部件
            if (!(part instanceof WeaponUnit unit)) {
                continue;
            }
            for (AbstractVehicleWeapon<?> weapon : unit.getIndexedWeapons()) {   // 调用本体公开 API，遍历该站全部武器
                if (!(weapon instanceof VehicleMultiWeapons multi)) {
                    continue;
                }
                int size = multi.getSubWeapons().size();
                if (size <= 0) {
                    continue;
                }
                int index = multi.getSelectedIndex();
                int clamped = Math.max(0, Math.min(size - 1, index));
                if (clamped != index) {
                    // 调用本体公开 setter，把越界索引夹回合法值，避免 getSelectedWeapon 抛异常。
                    multi.setSelectedIndex(clamped);
                }
            }
        }
    }
}
