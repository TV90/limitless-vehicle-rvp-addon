package org.ywzj.rvp.entity.gunner.behavior.action;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.GunnerGuidedWeaponController;
import org.ywzj.rvp.guidance.saclos.RVP_SaclosOperatorSession;
import org.ywzj.rvp.weapon.gps.GPSTargetManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/** 封装 Gunner 的 GPS、照射、HITL 与发射前制导控制源写入。 */
public final class RVP_GunnerGuidanceActions {

    RVP_GunnerGuidanceActions() {
    }

    /** 维持当前目标需要的 GPS、照射和在途 HITL 控制源。 */
    public RVP_GunnerActionResult maintain(GunnerEntity gunner,
                                           AbstractVehicle vehicle,
                                           @Nullable WeaponUnit weaponUnit,
                                           @Nullable Entity target) {
        if (gunner == null || vehicle == null || vehicle.level().isClientSide()) {
            return RVP_GunnerActionResult.INVALID;
        }
        // 调用项目既有制导控制器，集中维护 GPS、照射与在途 HITL 会话。
        GunnerGuidedWeaponController.tick(gunner, vehicle, weaponUnit, target);
        return RVP_GunnerActionResult.EXECUTED;
    }

    /** 在同一武器事务内写入本发弹药需要的制导控制源。 */
    public RVP_GunnerActionResult prepareLaunch(GunnerEntity gunner,
                                                AbstractVehicle vehicle,
                                                WeaponUnit weaponUnit,
                                                AbstractVehicleWeapon<?> weapon,
                                                @Nullable Entity target) {
        if (gunner == null || vehicle == null || weaponUnit == null || weapon == null
                || target == null || !target.isAlive() || vehicle.level().isClientSide()) {
            return RVP_GunnerActionResult.INVALID;
        }
        // 调用项目既有制导控制器，保证准备动作与随后本体 shoot 调用处于同一事务。
        GunnerGuidedWeaponController.prepareForLaunch(gunner, vehicle, weaponUnit, weapon, target);
        return RVP_GunnerActionResult.EXECUTED;
    }

    /** Profile 切换、换车或离座时清除仅属于当前 Gunner 的 GPS 与照射会话。 */
    public RVP_GunnerActionResult clear(GunnerEntity gunner) {
        if (gunner == null || gunner.level().isClientSide()) {
            return RVP_GunnerActionResult.INVALID;
        }
        // 调用项目 GPS 状态表，清除当前 Gunner 所有者键下的待发射目标。
        GPSTargetManager.clear(gunner);
        // 调用项目照射会话入口，只释放当前 Gunner UUID，不影响其他玩家或 Gunner。
        RVP_SaclosOperatorSession.setDesignation(gunner.getUUID(), false, null);
        return RVP_GunnerActionResult.EXECUTED;
    }
}
