package org.ywzj.rvp.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

@Mixin(value = LocalVehiclePlayer.class, remap = false)
public class LocalVehiclePlayerTVMissileTurnMixin {

    @Inject(method = "handlePlayerTurn", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$TVMissileTrackTurn(double pYRot, double pXRot, CallbackInfoReturnable<Boolean> cir) {
        if (!RVP_ClientHitlState.isActive()) {
            return;
        }
        if (RVP_ClientHitlState.isMouseSteering()) {
            RVP_ClientHitlState.applySteeringDelta(pYRot, pXRot);
            cir.setReturnValue(true);
            return;
        }
        if (RVP_ClientHitlState.isDesignateMode()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            Entity entity = mc.level.getEntity(RVP_ClientHitlState.getActiveMissileId());
            if (entity instanceof RVP_MissileEntity missile) {
                RVP_ClientHitlState.applyLookOffsetDelta(
                        pYRot, pXRot, missile.rvp$getHitlMaxLookOffsetDeg());
                cir.setReturnValue(true);
            }
        }
    }
}
