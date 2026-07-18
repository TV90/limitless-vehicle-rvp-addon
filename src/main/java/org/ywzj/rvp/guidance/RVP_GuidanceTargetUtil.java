package org.ywzj.rvp.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** Target helpers shared by runtime guidance and projectile state machines. */
public final class RVP_GuidanceTargetUtil {

    private RVP_GuidanceTargetUtil() {}

    @Nullable
    public static Entity getStrictRadarIlluminatedTarget(RVP_BaseBullet projectile) {
        WeaponUnit unit = projectile.getShooterWeaponUnit();
        if (unit == null) {
            return null;
        }
        Entity tracked = RVP_RadarRoleHelper.getEffectiveRfLockedEntity(unit.getRootParentWeaponUnit());
        return tracked != null && tracked.isAlive() ? tracked : null;
    }

    public static boolean isRadarScannableTarget(@Nullable Entity entity) {
        return entity instanceof AbstractVehicle && entity.isAlive()
                || RVP_RadarContactHelper.isHbmMissile(entity);
    }

    public static double angleBetween(Vec3 first, Vec3 second) {
        if (first.lengthSqr() <= 1.0E-6 || second.lengthSqr() <= 1.0E-6) {
            return 180.0;
        }
        double dot = Mth.clamp(first.normalize().dot(second.normalize()), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }
}
