package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.part.LandingGearUnit;

/**
 * 起落架自动收放：
 *   - 速度 > 100 kph → 收起
 *   - 速度 < 50 kph 且离地高度 < 25 米 → 放下
 */
@Mixin(value = Entity.class, remap = true)
public abstract class VehicleAutoLandingGearMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void rvp$onEntityTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof AbstractVehicle vehicle)) return;
        if (vehicle.level().isClientSide()) return;

        LandingGearUnit gear = null;
        if (vehicle instanceof FixedWingVehicle fw) {
            gear = fw.landingGear;
        } else if (vehicle instanceof RotaryWingVehicle rw) {
            gear = rw.landingGear;
        }
        if (gear == null) return;

        // m/s → km/h
        double speed = vehicle.getDeltaMovement().length() * 20.0 * 3.6;
        // 离地高度
        double groundHeight = vehicle.getY() - vehicle.level().getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                vehicle.blockPosition()).getY();

        if (speed > 100.0 && !gear.isOn()) {
            gear.setOn(true);  // 收起
        } else if (speed < 50.0 && groundHeight < 25.0 && gear.isOn()) {
            gear.setOn(false); // 放下
        }
    }
}
