package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.weapon.damage.RVP_HitboxDamageContext;
import org.ywzj.vehicle.api.event.HitVehicleEvent;
import org.ywzj.vehicle.network.message.ServerHitVehicleEvent;

@Mixin(value = ServerHitVehicleEvent.class, remap = false)
public class ServerHitVehicleEventMixin {

    @Inject(method = "<init>(Lorg/ywzj/vehicle/api/event/HitVehicleEvent;)V", at = @At("TAIL"))
    private void rvp$useActualDamage(HitVehicleEvent hitVehicleEvent, CallbackInfo ci) {
        float damage = RVP_HitboxDamageContext.getVehicleHitDisplayDamage();
        if (!Float.isFinite(damage)) {
            return;
        }
        ((ServerHitVehicleEvent) (Object) this).damage = damage;
    }
}
