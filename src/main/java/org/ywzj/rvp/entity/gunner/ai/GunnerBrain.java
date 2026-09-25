package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.config.RVP_LauncherDeployConfig;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionGateway;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle.Seat;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.List;

/**
 * Gunner 兼容查询入口。
 *
 * <p>阶段 D 后本类不再包含索敌、移动、交战、雷达、反制或 SEAD 战术；服务端 tick 直接由
 * 行为管理器运行固定 {@code BehaviorPlan}。这里仅保留被现有诊断与能力判定调用的无副作用查询。</p>
 */
public final class GunnerBrain {

    private GunnerBrain() {
    }

    /** 检查载具 JSON 是否声明发射架部署能力。 */
    public static boolean hasLauncherDeployConfig(AbstractVehicle vehicle) {
        if (vehicle == null || vehicle.getVehicleId() == null) {
            return false;
        }
        List<RVP_LauncherDeployConfig> configs = RVP_LauncherDeployConfigCache.get(vehicle.getVehicleId());
        return !configs.isEmpty();
    }

    /** 供诊断监控查询当前目标可用武器，不产生开火副作用。 */
    static int findWeaponIndexForDump(WeaponUnit weaponUnit, Entity target) {
        // 调用武器动作适配器的只读选择入口，避免诊断监控复制武器选择算法。
        return RVP_GunnerActionGateway.INSTANCE.weapons().findWeaponIndex(weaponUnit, target, null);
    }

    /** 解析司机能力，并兼容本体 driver 缓存尚未更新的座位表。 */
    public static boolean isDriverSeat(AbstractVehicle vehicle, GunnerEntity gunner) {
        if (vehicle.getDriver() == gunner) {
            return true;
        }
        for (Seat seat : vehicle.seats) {
            if (seat.passengerId == gunner.getId()) {
                return seat.seatIndex == 0;
            }
        }
        return false;
    }

    /** 解析 Gunner 当前实际可控制的武器站。 */
    @Nullable
    public static WeaponUnit resolveControlledWeaponUnit(AbstractVehicle vehicle,
                                                         @Nullable PartUnit<?> seatUnit,
                                                         boolean driver) {
        if (seatUnit instanceof WeaponUnit weaponUnit) {
            return weaponUnit;
        }
        if (!driver) {
            return null;
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponUnit weaponUnit && !weaponUnit.getIndexedWeapons().isEmpty()) {
                return weaponUnit;
            }
        }
        return null;
    }
}
