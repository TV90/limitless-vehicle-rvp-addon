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
     * Accumulate the raw mouse delta into the TV missile free-look heading so the missile
     * flies where the player looks ("看向哪里就飞向哪里"), independent of the launcher turret
     * rotation limits. We intentionally do NOT cancel {@code handlePlayerTurn} so the SCOPE
     * HUD keeps rendering normally; the turret aim it computes is no longer used for steering.
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
