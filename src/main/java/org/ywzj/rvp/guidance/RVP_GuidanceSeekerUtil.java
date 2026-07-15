package org.ywzj.rvp.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.ext.WeaponUnitExternalRadarLockExt;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSeekerData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Shared seeker scanning and illumination helpers for guidance sources.
 */
public final class RVP_GuidanceSeekerUtil {

    private RVP_GuidanceSeekerUtil() {}

    public static Entity getIlluminatedTarget(RVP_BaseBullet projectile) {
        return getIlluminatedTarget(projectile, null);
    }

    public static Entity getIlluminatedTarget(RVP_BaseBullet projectile, @Nullable RVP_EnumGuidanceType requestedType) {
        WeaponUnit unit = projectile.getShooterWeaponUnit();
        if (unit == null) {
            return projectile.getTargetEntity();
        }
        WeaponUnit root = unit.getRootParentWeaponUnit();
        if (requestedType == RVP_EnumGuidanceType.IR) {
            Entity tracked = root.getLockedEntity();
            return tracked != null && tracked.isAlive() ? tracked : projectile.getTargetEntity();
        }
        Entity tracked = RVP_RadarRoleHelper.getEffectiveRfLockedEntity(root);
        return tracked != null ? tracked : projectile.getTargetEntity();
    }

    public static boolean isValidIrTrackTarget(
            RVP_BaseBullet projectile,
            RVP_GuidanceEffectiveConfig config,
            Entity target
    ) {
        if (projectile == null || config == null || target == null || !target.isAlive()) {
            return false;
        }
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null) {
            return false;
        }
        if (!RVP_IrLockHelper.isIrLaunchWeapon(data)) {
            return isWithinLaunchCone(projectile, config, target, RVP_IrLockHelper.halfAngleFromFull(config.seeker().resolvedFov()));
        }
        return isWithinLaunchCone(projectile, config, target, data.getMaxGuideHeadAngle());
    }

    private static boolean isWithinLaunchCone(
            RVP_BaseBullet projectile,
            RVP_GuidanceEffectiveConfig config,
            Entity target,
            float angleLimit
    ) {
        if (target == null || config == null) {
            return false;
        }
        RVP_GuidanceSeekerData seeker = config.seeker();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        float range = seeker.resolvedRange();
        if (range > 0f && projectile.position().distanceToSqr(targetCenter) > range * range) {
            return false;
        }
        if (!RVP_GuidanceMath.isTargetPassAltFilter(target, seeker.getLockMinHeight())) {
            return false;
        }
        Vec3 toTarget = targetCenter.subtract(projectile.position());
        Vec3 look = projectile.getLookAngle().normalize();
        if (look.lengthSqr() <= 1.0E-6 || toTarget.lengthSqr() <= 1.0E-6) {
            return true;
        }
        double dot = Mth.clamp(look.dot(toTarget.normalize()), -1.0, 1.0);
        double angle = Math.toDegrees(Math.acos(dot));
        return angle <= Math.max(1f, angleLimit);
    }

    public static boolean isValidEntityTarget(
            RVP_BaseBullet projectile,
            RVP_GuidanceEffectiveConfig config,
            RVP_EnumGuidanceType type,
            Entity target
    ) {
        boolean geometryOk = type == RVP_EnumGuidanceType.IR
                ? isValidIrTrackTarget(projectile, config, target)
                : RVP_GuidanceMath.isWithinSeekerCone(projectile, target, config);
        if (!geometryOk) {
            return false;
        }
        if (type == RVP_EnumGuidanceType.SARH
                && RVP_GuidanceMath.isOnGround(target, config.seeker().getLockMinHeight())) {
            return false;
        }
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.query(
                projectile, target, type, config.seeker());
        if (result.intercepted()) {
            projectile.discard();
            return false;
        }
        if (result.decoyed()) {
            RVP_CountermeasureState.findDecoyTarget(target, 16.0).ifPresent(projectile::setTargetEntity);
            return true;
        }
        return !result.isDenied();
    }

    public static Entity scanSeekerTarget(
            RVP_BaseBullet projectile,
            RVP_GuidanceEffectiveConfig config,
            RVP_EnumGuidanceType type,
            boolean vehicleOnly
    ) {
        RVP_GuidanceSeekerData seeker = config.seeker();
        double range = seeker.resolvedRange();
        double maxAngle = RVP_IrLockHelper.halfAngleFromFull(seeker.resolvedFov());
        AABB box = projectile.getBoundingBox().inflate(range);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : projectile.level().getEntities(projectile, box, RVP_GuidanceMath::isVehicleTarget)) {
            if (entity == projectile.getShooterVehicle()) {
                continue;
            }
            if (vehicleOnly && !(entity instanceof AbstractVehicle)) {
                continue;
            }
            if (projectile.position().distanceToSqr(entity.position()) > range * range) {
                continue;
            }
            if (type == RVP_EnumGuidanceType.SARH
                    && RVP_GuidanceMath.isOnGround(entity, seeker.getLockMinHeight())) {
                continue;
            }
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(projectile.position());
            double angle = angleBetween(projectile.getLookAngle(), toTarget);
            if (angle > maxAngle) {
                continue;
            }
            double score = angle / Math.max(maxAngle, 1.0) + projectile.distanceTo(entity) / Math.max(range, 1.0);
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    public static Entity scanRadarTarget(RVP_BaseBullet projectile, RVP_GuidanceEffectiveConfig config) {
        RVP_GuidanceSeekerData seeker = config.seeker();
        double range = seeker.resolvedRange();
        double maxAngle = RVP_IrLockHelper.halfAngleFromFull(seeker.resolvedFov());
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : Radar.scanTargets(projectile, projectile.position(), range, entityPos -> {
            Vec3 toTarget = entityPos.subtract(projectile.position());
            double angle = angleBetween(projectile.getLookAngle(), toTarget);
            return angle <= maxAngle;
        })) {
            if (entity == projectile.getShooterVehicle()) {
                continue;
            }
            if (!isRadarScannableTarget(entity)) {
                continue;
            }
            if (projectile.position().distanceToSqr(entity.position()) > range * range) {
                continue;
            }
            if (!RVP_GuidanceMath.isTargetPassAltFilter(entity, seeker.getLockMinHeight())) {
                continue;
            }
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(projectile.position());
            double angle = angleBetween(projectile.getLookAngle(), toTarget);
            if (angle > maxAngle) {
                continue;
            }
            double score = angle * 4.0 + projectile.position().distanceTo(entity.position()) / Math.max(range, 1.0);
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    public static boolean isRadarScannableTarget(@Nullable Entity entity) {
        return entity instanceof AbstractVehicle && entity.isAlive()
                || RVP_RadarContactHelper.isHbmMissile(entity);
    }

    public static double angleBetween(Vec3 a, Vec3 b) {
        if (a.lengthSqr() <= 1.0E-6 || b.lengthSqr() <= 1.0E-6) {
            return 180.0;
        }
        Vec3 na = a.normalize();
        Vec3 nb = b.normalize();
        double dot = Mth.clamp(na.dot(nb), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }
}
