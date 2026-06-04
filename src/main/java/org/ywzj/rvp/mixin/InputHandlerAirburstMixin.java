package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.ywzj.rvp.client.state.RVP_AirburstInput;
import org.ywzj.vehicle.vehicle.control.InputHandler;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = InputHandler.class, remap = false)
public class InputHandlerAirburstMixin {

    /**
     * 本体 0.5.5+ 将 {@code fireControlLock()} 从 {@code onKey} 挪到 {@code handleVehicleAction}。
     */
    @Redirect(
            method = "handleVehicleAction",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/ywzj/vehicle/vehicle/part/WeaponUnit;fireControlLock()V"
            )
    )
    private static void ywzj_rvp$redirectFireControlLock(WeaponUnit weaponUnit, int key, int scanCode, int action) {
        if (!RVP_AirburstInput.tryMeasureOnLockKey(weaponUnit)) {
            weaponUnit.fireControlLock();
        }
    }
}
