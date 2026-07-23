package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;

@Mixin(value = VectorUtil.class, remap = false)
public class VectorUtilPhysicsOnlyHitFilterMixin {

    @Inject(method = "closestHitObbPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private static void rvp$filterPhysicsOnlyHit(Entity entity, Vec3 start, Vec3 end, CallbackInfoReturnable<Vec3> cir) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (RVP_PhysicsOnlyCollisionHelper.getPhysicsOnlyCubes(vehicle).isEmpty()) {
            return;
        }
        cir.setReturnValue(RVP_PhysicsOnlyCollisionHelper.closestNonPhysicsOnlyHitPosition(vehicle, start, end));
    }
}
