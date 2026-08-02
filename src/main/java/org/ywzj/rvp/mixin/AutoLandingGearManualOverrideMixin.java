package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.AutoLandingGearCache;
import org.ywzj.rvp.config.AutoLandingGearManualOverrideManager;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.network.message.ClientVehicleAction;

@Mixin(value = FixedWingVehicle.class, remap = false)
public abstract class AutoLandingGearManualOverrideMixin {

    @Inject(
            method = "onClientVehicleAction",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/LandingGearUnit;setOn(Z)V",
                    remap = false
            ),
            remap = false
    )
    private void rvp$markManualOverride(ClientVehicleAction message, Player player, CallbackInfo ci) {
        if (!message.toggleLandingGear) {
            return;
        }
        FixedWingVehicle self = (FixedWingVehicle) (Object) this;
        if (!AutoLandingGearCache.isEnabled(self.getVehicleId())) {
            return;
        }
        AutoLandingGearManualOverrideManager.markManualOverride(self.getId());
    }
}
