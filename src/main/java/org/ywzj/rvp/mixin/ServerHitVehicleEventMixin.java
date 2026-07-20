package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxRuntimeAccess;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.api.event.HitVehicleEvent;
import org.ywzj.vehicle.network.message.ServerHitVehicleEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mixin(value = ServerHitVehicleEvent.class, remap = false)
public class ServerHitVehicleEventMixin {

    @Inject(method = "<init>(Lorg/ywzj/vehicle/api/event/HitVehicleEvent;)V", at = @At("TAIL"))
    private void rvp$useActualDamage(HitVehicleEvent hitVehicleEvent, CallbackInfo ci) {
        AbstractVehicle vehicle = null;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            for (var level : server.getAllLevels()) {
                var entity = level.getEntity(hitVehicleEvent.entityId);
                if (entity instanceof AbstractVehicle foundVehicle) {
                    vehicle = foundVehicle;
                    break;
                }
            }
        }
        if (!(vehicle instanceof RVP_VehicleHitboxRuntimeAccess access)) {
            return;
        }
        float damage = access.rvp$getPendingVehicleHitDisplayDamage();
        if (!Float.isFinite(damage)) {
            return;
        }
        ((ServerHitVehicleEvent) (Object) this).damage = damage;
        access.rvp$clearPendingVehicleHitDisplayDamage();
    }
}
