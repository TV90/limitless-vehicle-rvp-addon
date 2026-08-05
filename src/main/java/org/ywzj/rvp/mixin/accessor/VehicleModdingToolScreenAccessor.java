package org.ywzj.rvp.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.ywzj.vehicle.client.screen.VehicleModdingToolScreen;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

@Mixin(value = VehicleModdingToolScreen.class, remap = false)
public interface VehicleModdingToolScreenAccessor {

    @Accessor("vehicle")
    AbstractVehicle rvp$getVehicle();
}
