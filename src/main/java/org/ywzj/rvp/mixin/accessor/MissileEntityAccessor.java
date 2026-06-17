package org.ywzj.rvp.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

@Mixin(value = MissileEntity.class, remap = false)
public interface MissileEntityAccessor {

    @Accessor("guidance")
    VehicleMissileWeaponData.Guidance getGuidance();

    @Accessor("weaponUnit")
    WeaponUnit getWeaponUnit();
}
