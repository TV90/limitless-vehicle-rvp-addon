package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.config.LauncherDeployPoseHelper;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitLauncherDeployPoseBypassMixin {

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void rvp$applyLauncherDeployPitchAfterWeaponTick(CallbackInfo ci) {
        LauncherDeployPoseHelper.applyRuntimePitchForWeaponUnit((WeaponUnit) (Object) this);
    }
}
