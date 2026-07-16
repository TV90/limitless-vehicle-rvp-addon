package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.network.message.ServerVehicleExplosion;
import org.ywzj.vehicle.util.VehicleExplosion;

@Mixin(value = VehicleExplosion.class, remap = false)
public abstract class VehicleExplosionClientVisualMixin {

    @Inject(method = "effect", at = @At("HEAD"), cancellable = true)
    private static void rvp$suppressMarkedVisual(ServerVehicleExplosion message, CallbackInfo ci) {
        if (message != null && message.radius() < 0.0F) {
            ci.cancel();
        }
    }
}
