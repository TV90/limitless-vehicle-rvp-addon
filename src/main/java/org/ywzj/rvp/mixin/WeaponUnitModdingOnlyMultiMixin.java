package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitModdingOnlyMultiMixin {

    @Inject(method = "cycleMultiWeapon", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$blockRuntimeMultiCycle(boolean next, CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (!RVP_VehicleExtendedConfigManager.INSTANCE.shouldBlockCurrentMultiCycle(self)) {
            return;
        }
        ci.cancel();
    }
}
