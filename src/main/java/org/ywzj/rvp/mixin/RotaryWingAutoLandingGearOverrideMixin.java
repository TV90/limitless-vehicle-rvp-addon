package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.AutoLandingGearCache;
import org.ywzj.rvp.config.ManualOverrideManager;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.network.message.ClientVehicleAction;

/**
 * 旋翼机（直升机）手动切换起落架时，标记手动覆盖。
 * 逻辑与 {@link AutoLandingGearManualOverrideMixin} 对称。
 */
@Mixin(value = RotaryWingVehicle.class, remap = false)
public abstract class RotaryWingAutoLandingGearOverrideMixin {

    @Inject(method = "onClientVehicleAction", at = @At(value = "INVOKE",
            target = "Lorg/ywzj/vehicle/vehicle/part/SwitchableUnit;setOn(Z)V",
            remap = false), remap = false)
    private void rvp$markManualOverride(ClientVehicleAction message, Player player, CallbackInfo ci) {
        if (!message.toggleLandingGear) return;
        RotaryWingVehicle self = (RotaryWingVehicle) (Object) this;
        if (!AutoLandingGearCache.isEnabled(self.getVehicleId())) return;
        ManualOverrideManager.markManualOverride(self.getId(), self.level().getGameTime());
    }
}
