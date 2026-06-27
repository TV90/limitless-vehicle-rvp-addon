package org.ywzj.rvp.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.ywzj.vehicle.vehicle.part.WeaponBayUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Map;

@Mixin(value = WeaponUnit.class, remap = false)
public interface WeaponUnitAccessor {

    @Accessor("weaponBayUnits")
    Map<AbstractVehicleWeapon<?>, WeaponBayUnit> getWeaponBayUnits();

    @Accessor("currentWeaponIndex")
    int getCurrentWeaponIndex();

    @Accessor("currentSecondaryWeaponIndex")
    int getCurrentSecondaryWeaponIndex();

    @Accessor("xTurnGroup")
    VehicleCubeGroup getXTurnGroup();
}
