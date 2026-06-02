package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.state.RvpClientTVMissileState;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

@Mixin(value = LocalVehiclePlayer.class, remap = false)
public class LocalVehiclePlayerTVMissileTurnMixin {
    /**
     * Do not cancel {@code handlePlayerTurn}: scope aim must keep updating so
     * {@link LocalVehiclePlayer#cameraAimRotX} / {@code cameraAimRotY} track the crosshair.
     */
    @Inject(method = "handlePlayerTurn", at = @At("HEAD"), remap = false)
    private void ywzj_rvp$TVMissileTrackTurn(double pYRot, double pXRot, CallbackInfoReturnable<Boolean> cir) {
        if (!RvpClientTVMissileState.isActive()) {
            return;
        }
        if (pYRot == 0 && pXRot == 0) {
            return;
        }
        RvpClientTVMissileState.applyTVMissileTurnDelta(pYRot, pXRot);
    }
}
