package org.ywzj.rvp.mixin;

import net.minecraftforge.client.event.InputEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.client.state.RVP_AirburstInput;
import org.ywzj.vehicle.vehicle.control.InputHandler;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = InputHandler.class, remap = false)
public class InputHandlerAirburstMixin {

    @Redirect(
            method = "onKey",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;fireControlLock()V"
            )
    )
    private static void ywzj_rvp$redirectFireControlLock(WeaponUnit weaponUnit, InputEvent.Key event) {
        if (!RVP_AirburstInput.tryMeasureOnLockKey(weaponUnit)) {
            weaponUnit.fireControlLock();
        }
    }
}
