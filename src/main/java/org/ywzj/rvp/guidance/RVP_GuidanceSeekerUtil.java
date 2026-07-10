package org.ywzj.rvp.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.ext.WeaponUnitExternalRadarLockExt;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSeekerData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Shared seeker scanning and illumination helpers for guidance sources.
 */
public final class RVP_GuidanceSeekerUtil {

    private RVP_GuidanceSeekerUtil() {}

    public static Entity getIlluminatedTarget(RVP_BaseBullet projectile) {
        WeaponUnit unit = projectile.getShooterWeaponUnit();
        if (unit == null) {
            return projectile.getTargetEntity();
        }
        WeaponUnit root = unit.getRootParentWeaponUnit();
        var radarUnit = RVP_RadarRoleHelper.getLockedRadar(root);
        if (radarUnit != null && radarUnit.getLockedEntity() != null) {
            return radarUnit.getLockedEntity();
        }
        if (root instanceof WeaponUnitExternalRadarLockExt ext) {
            int externalLockedId = ext.ywzj_rvp$getExternalRadarLockedEntityId();
            if (externalLockedId != Integer.MIN_VALUE) {
                Entity externalLocked = root.getVehicle().level().getEntity(externalLockedId);
                if (externalLocked != null && externalLocked.isAlive()) {
                    return externalLocked;
                }
            }
        }
        Entity tracked = root.getLockedEntity();
        return tracked != null && tracked.isAlive() ? tracked : projectile.getTargetEntity();
    }

    public static boolean isValidEntityTarget(
            RVP_BaseBullet projectile,
            RVP_GuidanceEffectiveConfig config,
            RVP_EnumGuidanceType type,
            Entity target
    ) {
        if (!RVP_GuidanceMath.isWithinSeekerCone(projectile, target, config)) {
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

    public static boolean isValidEntityTrack(
            RVP_BaseBullet projectile,
            RVP_GuidanceEffectiveConfig config,
            RVP_EnumGuidanceType type,
            Entity target
    ) {
        if (!RVP_GuidanceMath.isWithinTrackCone(projectile, target, config)) {
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
        double maxAngle = seeker.resolvedFov();
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
        double maxAngle = seeker.resolvedFov();
        AABB box = projectile.getBoundingBox().inflate(range);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : projectile.level().getEntities(projectile, box, RVP_GuidanceMath::isVehicleTarget)) {
            if (entity == projectile.getShooterVehicle()) {
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
