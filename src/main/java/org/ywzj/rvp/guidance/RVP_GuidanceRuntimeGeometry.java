package org.ywzj.rvp.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_Range;

public final class RVP_GuidanceRuntimeGeometry {

    private static final double DEFAULT_SCAN_RADIUS = 512.0;

    private RVP_GuidanceRuntimeGeometry() {}

    public static boolean passesTrackLimits(
            RVP_BaseBullet projectile,
            Vec3 target,
            RVP_GuidanceActiveConfig config
    ) {
        if (projectile == null || target == null || config == null) {
            return false;
        }
        double distance = projectile.position().distanceTo(target);
        if (!contains(config.targetDistanceRange(), distance)) {
            return false;
        }
        if (!contains(config.altitudeRange(), altitudeAgl(projectile, target))) {
            return false;
        }
        return withinAngle(projectile.getLookAngle(), target.subtract(projectile.position()),
                config.maxGuidanceAngle());
    }

    public static boolean passesTrackLimits(
            RVP_BaseBullet projectile,
            Entity target,
            RVP_GuidanceActiveConfig config
    ) {
        return target != null && target.isAlive()
                && passesTrackLimits(projectile, target.getBoundingBox().getCenter(), config);
    }

    public static boolean passesAcquireLimits(
            RVP_BaseBullet projectile,
            Entity target,
            RVP_GuidanceActiveConfig config
    ) {
        if (projectile == null || target == null || !target.isAlive() || config == null) {
            return false;
        }
        Vec3 center = target.getBoundingBox().getCenter();
        double distance = projectile.position().distanceTo(center);
        if (!contains(config.targetDistanceRange(), distance)) {
            return false;
        }
        if (!contains(config.altitudeRange(), altitudeAgl(projectile, center))) {
            return false;
        }
        return withinAngle(projectile.getLookAngle(), center.subtract(projectile.position()),
                config.maxLockHalfAngle());
    }

    public static double resolveScanRadius(RVP_Range<Float> range) {
        if (range == null) {
            return DEFAULT_SCAN_RADIUS;
        }
        double maximum = 0.0;
        for (RVP_Range.Interval<Float> interval : range.intervals()) {
            if (interval.upper() == null) {
                return Math.max(DEFAULT_SCAN_RADIUS, maximum);
            }
            maximum = Math.max(maximum, interval.upper());
        }
        return Math.max(maximum, 1.0);
    }

    public static boolean withinAngle(Vec3 axis, Vec3 toTarget, double maxAngle) {
        if (axis == null || toTarget == null || axis.lengthSqr() <= 1.0E-8 || toTarget.lengthSqr() <= 1.0E-8) {
            return true;
        }
        double dot = Mth.clamp(axis.normalize().dot(toTarget.normalize()), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot)) <= Math.max(maxAngle, 0.0) + 1.0E-6;
    }

    private static boolean contains(RVP_Range<Float> range, double value) {
        return range == null || range.contains((float) value);
    }

    private static double altitudeAgl(RVP_BaseBullet projectile, Vec3 target) {
        int x = Mth.floor(target.x);
        int z = Mth.floor(target.z);
        int groundY = projectile.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return target.y - groundY;
    }
}
