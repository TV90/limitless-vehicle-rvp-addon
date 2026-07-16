package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.ywzj.rvp.weapon.effects.RVP_ExplosionVisualSuppression;
import org.ywzj.vehicle.util.VehicleExplosion;

@Mixin(value = VehicleExplosion.class, remap = false)
public abstract class VehicleExplosionVisualPacketMixin {

    @ModifyArg(
            method = "explode(Ljava/util/List;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/network/message/ServerVehicleExplosion;<init>(IDDDF)V"
            ),
            index = 4
    )
    private float rvp$markSuppressedVisualPacket(float radius) {
        return RVP_ExplosionVisualSuppression.active() ? -Math.abs(radius) : radius;
    }
}
