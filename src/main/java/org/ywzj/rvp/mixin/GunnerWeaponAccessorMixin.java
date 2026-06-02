package org.ywzj.rvp.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

@Mixin(value = AbstractVehicleWeapon.class, remap = false)
public interface GunnerWeaponAccessorMixin {
    @Invoker(value = "setReloadTime", remap = false)
    void ywzj_rvp$setReloadTime(int reloadTime);
}
