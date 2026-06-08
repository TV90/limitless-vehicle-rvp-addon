package org.ywzj.rvp.mixin;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

/**
 * Synced {@link WeaponUnit#setCurrentWeaponIndex(int)} does not call {@code onSwitchFrom}/{@code onSwitchTo}.
 * RVP weapons rely on those hooks on the client (seeker HUD teardown, fire-mode controller reset).
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = WeaponUnit.class, remap = false)
public abstract class WeaponUnitClientWeaponSwitchMixin {

    @Inject(method = "setCurrentWeaponIndex", at = @At("HEAD"))
    private void ywzj_rvp$beforeWeaponIndexChange(int index, CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        int previous = self.getCurrentWeaponIndex();
        if (previous < 0 || previous == index) {
            return;
        }
        self.getCurrentWeapon().ifPresent(AbstractVehicleWeapon::onSwitchFrom);
    }

    @Inject(method = "setCurrentWeaponIndex", at = @At("TAIL"))
    private void ywzj_rvp$afterWeaponIndexChange(int index, CallbackInfo ci) {
        WeaponUnit self = (WeaponUnit) (Object) this;
        if (index < 0 || self.getCurrentWeaponIndex() != index) {
            return;
        }
        self.getCurrentWeapon().ifPresent(AbstractVehicleWeapon::onSwitchTo);
    }
}
