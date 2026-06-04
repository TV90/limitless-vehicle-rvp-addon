package org.ywzj.rvp.guidance;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/**
 * Missile-only guidance sources. These modes are not generic projectile
 * behavior even though they share the common RVP projectile runtime.
 */
public final class RVP_MissileGuidance {

    private RVP_MissileGuidance() {}

    public static boolean applyTv(RVP_MissileEntity missile, RVP_WeaponData data, RVP_GuidanceData.Source source) {
        if (!missile.rvp$isTVGuidanceActive()) {
            return false;
        }
        Vec3 direction = missile.rvp$getTVLookDirection();
        if (direction == null || direction.lengthSqr() <= 1.0E-6) {
            return false;
        }
        Vec3 target = missile.position().add(direction.normalize().scale(Math.max(data.getSeekerData().getRange(), 256.0)));
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.queryPoint(
                missile, target, RVP_EnumGuidanceType.TV, data.getSeekerData());
        if (result.isDenied()) {
            return false;
        }
        missile.setTargetPos(target);
        return source.isTakeOverMotion()
                ? RVP_GuidanceMath.directToPos(missile, target)
                : RVP_GuidanceMath.guidanceToPos(missile, target);
    }

    public static boolean applyActiveRadar(RVP_MissileEntity missile, RVP_WeaponData data) {
        Entity target = missile.getTargetEntity();
        if (target != null && target.isAlive()) {
            if (!isValidRadarTarget(missile, data, target)) {
                missile.clearTarget();
                return false;
            }
            target = missile.getTargetEntity();
            if (target == null || !target.isAlive()) {
                return false;
            }
            missile.setTargetPos(target.position().add(0, target.getBbHeight() * 0.5, 0));
            return RVP_GuidanceMath.guidanceToTarget(missile, target);
        }

        if (missile.tickCount % data.getScanInterval() != 0) {
            return false;
        }
        Entity scanned = scanRadarTarget(missile, data);
        if (scanned == null) {
            return false;
        }
        missile.setTargetEntity(scanned);
        return RVP_GuidanceMath.guidanceToTarget(missile, scanned);
    }

    private static boolean isValidRadarTarget(RVP_MissileEntity missile, RVP_WeaponData data, Entity target) {
        if (!RVP_GuidanceMath.isWithinSeekerCone(missile, target, data)) {
            return false;
        }
        if (RVP_GuidanceMath.isOnGround(target, data.getLockMinHeight())) {
            return false;
        }
        RVP_CountermeasureState.Result result = RVP_CountermeasureState.query(
                missile, target, RVP_EnumGuidanceType.ARH, data.getSeekerData());
        if (result.intercepted()) {
            missile.discard();
            return false;
        }
        if (result.decoyed()) {
            RVP_CountermeasureState.findDecoyTarget(target, 16.0).ifPresent(missile::setTargetEntity);
            return true;
        }
        return !result.isDenied();
    }

    private static Entity scanRadarTarget(RVP_MissileEntity missile, RVP_WeaponData data) {
        double range = Math.max(data.getMaxLockOnRange(), data.getSeekerData().getRange());
        double maxAngle = Math.max(data.getMaxLockOnAngle(), data.getSeekerData().getFov());
        AABB box = missile.getBoundingBox().inflate(range);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : missile.level().getEntities(missile, box, RVP_GuidanceMath::isVehicleTarget)) {
            if (entity == missile.getShooterVehicle()) {
                continue;
            }
            if (missile.position().distanceToSqr(entity.position()) > range * range) {
                continue;
            }
            if (RVP_GuidanceMath.isOnGround(entity, data.getLockMinHeight())) {
                continue;
            }
            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(missile.position());
            double angle = angleBetween(missile.getLookAngle(), toTarget);
            if (angle > maxAngle) {
                continue;
            }
            double score = angle * 4.0 + missile.position().distanceTo(entity.position()) / Math.max(range, 1.0);
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    private static double angleBetween(Vec3 a, Vec3 b) {
        if (a.lengthSqr() <= 1.0E-6 || b.lengthSqr() <= 1.0E-6) {
            return 180.0;
        }
        double dot = a.normalize().dot(b.normalize());
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }
}
