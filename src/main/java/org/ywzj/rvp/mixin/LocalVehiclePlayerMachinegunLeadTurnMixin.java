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
        return RVP_MachinegunLeadSolver.solveCurrent(weaponUnit, 1.0f) != null;
    }
}
