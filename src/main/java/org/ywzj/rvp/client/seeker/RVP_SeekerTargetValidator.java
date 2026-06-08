package org.ywzj.rvp.client.seeker;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.seeker.RVP_SeekerWeaponUtil;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Infrared;

/**
 * Reuses vanilla infrared occlusion / FOV checks for RVP IR missiles.
 */
public final class RVP_SeekerTargetValidator {

    private RVP_SeekerTargetValidator() {}

    public static boolean isValidIrTarget(WeaponUnit weaponUnit, Entity target, RVP_WeaponData weaponData) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        Entity checked = Infrared.checkTarget(weaponUnit, target);
        if (checked == null) {
            return false;
        }
        float fov = RVP_SeekerWeaponUtil.seekerFov(weaponData);
        WeaponUnit operatorUnit = org.ywzj.vehicle.vehicle.LocalVehiclePlayer.instance.getWeaponUnit();
        if (operatorUnit == null) {
            operatorUnit = weaponUnit.getRootParentWeaponUnit();
        }
        Vec3 origin = RVP_SeekerGeometry.aimOrigin(operatorUnit);
        Vec3 aimDir = RVP_SeekerGeometry.aimDirection(operatorUnit);
        Vec3 toTarget = target.getBoundingBox().getCenter().subtract(origin);
        return Math.toDegrees(VectorUtil.angleBetween(aimDir, toTarget)) <= fov;
    }
}
