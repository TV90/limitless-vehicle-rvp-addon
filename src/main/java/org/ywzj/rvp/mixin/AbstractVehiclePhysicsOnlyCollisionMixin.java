package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.ext.RVPPhysicsOnlyCollisionAccess;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import java.util.List;

@Mixin(value = AbstractVehicle.class, remap = false)
public abstract class AbstractVehiclePhysicsOnlyCollisionMixin implements RVPPhysicsOnlyCollisionAccess {

    @Unique
    private List<VehicleCubeOBB> rvp$physicsOnlyCubes = List.of();

    @Override
    public List<VehicleCubeOBB> rvp$getPhysicsOnlyCubes() {
        return rvp$physicsOnlyCubes;
    }

    @Override
    public void rvp$setPhysicsOnlyCubes(List<VehicleCubeOBB> cubes) {
        this.rvp$physicsOnlyCubes = cubes == null ? List.of() : List.copyOf(cubes);
    }

    @Inject(method = "initData(Lorg/ywzj/vehicle/custom/vehicle/BaseVehicleData;)V", at = @At("TAIL"), remap = false)
    private void rvp$initPhysicsOnlyCubes(BaseVehicleData<?> vehicleData, CallbackInfo ci) {
        RVP_PhysicsOnlyCollisionHelper.rebuildPhysicsOnlyCubes((AbstractVehicle) (Object) this);
    }

    @Inject(method = "updateOBBs", at = @At("TAIL"), remap = false)
    private void rvp$updatePhysicsOnlyCubes(CallbackInfo ci) {
        RVP_PhysicsOnlyCollisionHelper.updatePhysicsOnlyCubes((AbstractVehicle) (Object) this);
    }
}
