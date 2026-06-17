package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Optional;

/**
 * 修复 RVP 武器的 getSeekerFov() 返回值。
 * 本体只对 VehicleMissile 返回实际导引头 FOV，
 * RVP 武器走默认值 30°，导致 UI 大圈尺寸错误。
 */
@Mixin(value = WeaponUnit.class, remap = false)
public class WeaponUnitGetSeekerFovMixin {

    @Inject(method = "getSeekerFov", at = @At("RETURN"), cancellable = true, remap = false)
    private void ywzj_rvp$getSeekerFov(CallbackInfoReturnable<Float> cir) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        Optional<AbstractVehicleWeapon<?>> weaponOpt = self.getCurrentWeapon();
        if (weaponOpt.isPresent() && weaponOpt.get() instanceof RVP_WeaponBase rvpWeapon) {
            cir.setReturnValue(rvpWeapon.getData().getMaxGuideHeadAngle());
        }
    }
}
