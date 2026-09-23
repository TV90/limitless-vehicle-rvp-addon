package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.client.state.RVP_FireControlStabilizerState;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = LocalVehiclePlayer.class, remap = false)
public class LocalVehiclePlayerMachinegunLeadTurnMixin {

    @Redirect(
            method = "handlePlayerTurn",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;setXAimRot(F)V"
            ),
            remap = false
    )
    private void ywzj_rvp$suppressStableLeadMouseX(WeaponUnit weaponUnit, float xAimRot) {
        if (ywzj_rvp$shouldSuppressMouseAim(weaponUnit)) {
            return;
        }
        weaponUnit.setXAimRot(xAimRot);
    }

    @Redirect(
            method = "handlePlayerTurn",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;setYAimRot(F)V"
            ),
            remap = false
    )
    private void ywzj_rvp$suppressStableLeadMouseY(WeaponUnit weaponUnit, float yAimRot) {
        if (ywzj_rvp$shouldSuppressMouseAim(weaponUnit)) {
            return;
        }
        weaponUnit.setYAimRot(yAimRot);
    }

    private static boolean ywzj_rvp$shouldSuppressMouseAim(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return false;
        }
        if (LocalVehiclePlayer.instance == null
                || LocalVehiclePlayer.instance.viewType != LocalVehiclePlayer.ViewType.SCOPE) {
            return false;
        }
        if (RVP_FireControlStabilizerState.getMode(weaponUnit) != RVP_FireControlStabilizerState.Mode.STABLE) {
            return false;
        }
        if (!RVP_MachinegunLeadSolver.isCurrentRvpMachinegun(weaponUnit)) {
            return false;
        }
        // 调用本项目锁定目标解析器只做轻量资格判断；实际弹道解由共享状态每 tick 至多计算一次，
        // 禁止在 X/Y 鼠标重定向中重复扫描 239 组候选时间导致客户端卡顿。
        return RVP_MachinegunLeadSolver.resolveTrackedTarget(weaponUnit) != null;
    }
}
