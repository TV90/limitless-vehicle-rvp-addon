package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.AutoLandingGearCache;
import org.ywzj.rvp.config.AutoLandingGearManualOverrideManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.part.LandingGearUnit;

@Mixin(value = Entity.class, remap = true)
public abstract class VehicleAutoLandingGearMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void rvp$onEntityTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof AbstractVehicle vehicle)) {
            return;
        }
        if (vehicle.level().isClientSide()) {
            return;
        }

        AutoLandingGearCache.AutoLandingGearConfig config = AutoLandingGearCache.get(vehicle.getVehicleId());
        if (!config.enabled) {
            return;
        }

        LandingGearUnit gear = null;
        if (vehicle instanceof FixedWingVehicle fw) {
            gear = fw.landingGear;
        } else if (vehicle instanceof RotaryWingVehicle rw) {
            gear = rw.landingGear;
        }
        if (gear == null) {
            return;
        }

        Long overrideUntil = AutoLandingGearManualOverrideManager.getOverrideUntil(vehicle.getId());
        if (overrideUntil != null) {
            if (vehicle.level().getGameTime() < overrideUntil) {
                return;
            }
            AutoLandingGearManualOverrideManager.removeOverride(vehicle.getId());
        }

        double speed = vehicle.getDeltaMovement().length() * 20.0 * 3.6;
        double groundHeight = vehicle.getY() - vehicle.level().getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                vehicle.blockPosition()
        ).getY();

        if (speed > config.retractSpeed && !gear.isOn()) {
            gear.setOn(true);
        } else if (speed < config.deploySpeed && groundHeight < config.deployHeight && gear.isOn()) {
            gear.setOn(false);
        }
    }
}
