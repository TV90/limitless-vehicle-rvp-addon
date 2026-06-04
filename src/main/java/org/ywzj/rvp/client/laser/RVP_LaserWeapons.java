package org.ywzj.rvp.client.laser;

import org.ywzj.rvp.client.laser.RVP_ClientLaserState.LaserBeamKey;
import org.ywzj.rvp.weapon.core.RVP_LaserWeapon;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;

import javax.annotation.Nullable;

/**
 * Resolves proxy weapons ({@link VehicleWeaponAgent}, {@link VehicleMultiWeapons})
 * to the concrete mounted weapon (e.g. {@link RVP_LaserWeapon} on a child {@code WeaponUnit}).
 */
public final class RVP_LaserWeapons {

    private RVP_LaserWeapons() {}

    public static AbstractVehicleWeapon<?> unwrap(AbstractVehicleWeapon<?> weapon) {
        if (weapon == null) {
            return null;
        }
        if (weapon instanceof VehicleWeaponAgent) {
            return weapon.getWeaponUnit().getCurrentWeapon().orElse(null);
        }
        if (weapon instanceof VehicleMultiWeapons multi) {
            return multi.getSelectedWeapon();
        }
        return weapon;
    }

    public static RVP_LaserWeapon asLaser(AbstractVehicleWeapon<?> weapon) {
        AbstractVehicleWeapon<?> resolved = unwrap(weapon);
        if (resolved instanceof RVP_LaserWeapon laser
                && resolved.getData() instanceof RVP_WeaponData data
                && data.getWeaponKind() == RVP_EnumWeaponKind.LASER) {
            return laser;
        }
        return null;
    }

    public static RVP_WeaponData laserData(AbstractVehicleWeapon<?> weapon) {
        AbstractVehicleWeapon<?> resolved = unwrap(weapon);
        if (resolved != null && resolved.getData() instanceof RVP_WeaponData data
                && data.getWeaponKind() == RVP_EnumWeaponKind.LASER) {
            return data;
        }
        return null;
    }

    @Nullable
    public static WeaponUnit registryUnit(AbstractVehicle vehicle, LaserBeamKey key) {
        if (key.partUnitIndex() < 0 || key.partUnitIndex() >= vehicle.getPartUnits().size()) {
            return null;
        }
        PartUnit<?> part = vehicle.getPartUnits().get(key.partUnitIndex());
        return part instanceof WeaponUnit weaponUnit ? weaponUnit : null;
    }

    @Nullable
    public static RVP_LaserWeapon resolveLaser(WeaponUnit registryUnit, int weaponIndex) {
        if (registryUnit == null || weaponIndex < 0 || weaponIndex >= registryUnit.indexedWeapons.size()) {
            return null;
        }
        return asLaser(registryUnit.indexedWeapons.get(weaponIndex));
    }

    /** Part unit that owns the muzzle pose ({@code part_unit_id} mount target). */
    public static WeaponUnit aimUnit(RVP_LaserWeapon laser) {
        return laser.getWeaponUnit();
    }

    /**
     * Client beam visibility. Do not use {@link AbstractVehicleWeapon#isCoolingDown()} here:
     * laser {@code shoot_interval} is per damage tick; cooldown is almost always true between shots
     * and would prune the beam every tick while the player holds fire.
     */
    public static boolean canRenderBeam(RVP_LaserWeapon laser) {
        return !laser.isReloading() && laser.hasAmmo();
    }
}
