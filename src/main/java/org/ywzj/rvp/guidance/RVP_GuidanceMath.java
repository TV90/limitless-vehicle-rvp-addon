package org.ywzj.rvp.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileMotion;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * Steering math adapted to RVP projectiles.
 */
public final class RVP_GuidanceMath {

    private RVP_GuidanceMath() {}

    public static boolean guidanceToPos(RVP_BaseBullet projectile, Vec3 target) {
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null || target == null) {
            return false;
        }
        if (data.getTickEndHoming() > 0 && projectile.tickCount > data.getTickEndHoming()) {
            return false;
        }

        Vec3 toTarget = target.subtract(projectile.position());
        double distance = toTarget.length();
        if (distance < 1.0E-6) {
            return false;
        }

        Vec3 velocity = projectile.getDeltaMovement();
        double speed = Math.max(projectile.getFlightSpeed(), velocity.length());
        Vec3 desired = toTarget.scale(speed / distance);

        Vector3f missileDir = new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z);
        Vector3f targetDir = new Vector3f((float) toTarget.x, (float) toTarget.y, (float) toTarget.z);
        if (missileDir.lengthSquared() < 1.0E-6f) {
            missileDir.set((float) desired.x, (float) desired.y, (float) desired.z);
        }
        double angle = Math.abs(missileDir.angle(targetDir));
        if (angle > Math.toRadians(data.getMaxDegreeOfMissile())) {
            return false;
        }

        double turning = data.getTurningFactor();
        Vec3 next = new Vec3(
                velocity.x + (desired.x - velocity.x) * turning,
                velocity.y + (desired.y - velocity.y) * turning,
                velocity.z + (desired.z - velocity.z) * turning
        );
        applyVelocityAndRotation(projectile, next);
        return true;
    }

    public static boolean directToPos(RVP_BaseBullet projectile, Vec3 target) {
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null || target == null) {
            return false;
        }
        Vec3 toTarget = target.subtract(projectile.position());
        if (toTarget.lengthSqr() <= 1.0E-6) {
            return false;
        }
        double speed = Math.max(projectile.getFlightSpeed(), projectile.getDeltaMovement().length());
        applyVelocityAndRotation(projectile, toTarget.normalize().scale(Math.max(speed, 0.01)));
        projectile.rememberGuidancePos(target);
        return true;
    }

    public static boolean guidanceToTarget(RVP_BaseBullet projectile, Entity target) {
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null || target == null || !target.isAlive()) {
            return false;
        }

        Vec3 targetPos = target.position().add(0, target.getBbHeight() * 0.5, 0);
        if (data.isPredictTargetPos()) {
            double speed = Math.max(projectile.getDeltaMovement().length(), projectile.getFlightSpeed());
            double time = speed <= 1.0E-4 ? 0 : projectile.position().distanceTo(targetPos) / speed;
            targetPos = targetPos.add(target.getDeltaMovement().scale(time));
        }

        if (data.isSemiActiveRadar()) {
            Entity viewer = projectile.getShooterVehicle() != null ? projectile.getShooterVehicle() : projectile.getOwner();
            if (viewer != null) {
                double semiAngle = angleFromViewer(viewer, targetPos);
                if (semiAngle > data.getMaxLockOnAngle()
                        || projectile.position().distanceTo(targetPos) > data.getMaxLockOnRange()) {
                    projectile.clearTarget();
                    return false;
                }
            }
        }

        projectile.rememberGuidancePos(targetPos);
        return guidanceToPos(projectile, targetPos);
    }

    public static boolean isWithinSeekerCone(RVP_BaseBullet projectile, Entity target, RVP_WeaponData data) {
        if (target == null || data == null) {
            return false;
        }
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        double range = Math.max(data.getMaxLockOnRange(), data.getSeekerData().getRange());
        if (projectile.position().distanceToSqr(targetCenter) > range * range) {
            return false;
        }
        Vec3 toTarget = targetCenter.subtract(projectile.position());
        Vec3 look = projectile.getLookAngle().normalize();
        if (look.lengthSqr() <= 1.0E-6 || toTarget.lengthSqr() <= 1.0E-6) {
            return true;
        }
        double dot = Mth.clamp(look.dot(toTarget.normalize()), -1.0, 1.0);
        double angle = Math.toDegrees(Math.acos(dot));
        return angle <= Math.max(data.getMaxLockOnAngle(), data.getSeekerData().getFov());
    }

    public static boolean isOnGround(Entity entity, float minHeight) {
        if (entity == null) {
            return true;
        }
        int groundY = entity.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                entity.getBlockX(), entity.getBlockZ());
        return entity.getY() - groundY < minHeight;
    }

    public static boolean isVehicleTarget(Entity entity) {
        return entity instanceof AbstractVehicle && entity.isAlive();
    }

    private static void applyVelocityAndRotation(RVP_BaseBullet projectile, Vec3 velocity) {
        projectile.setDeltaMovement(velocity);
        RVP_ProjectileMotion.applyGuidanceFacing(projectile, velocity);
    }

    private static double angleFromViewer(Entity viewer, Vec3 pos) {
        Vec3 look = viewer.getLookAngle().normalize();
        Vec3 toTarget = pos.subtract(viewer.position()).normalize();
        double dot = Mth.clamp(look.dot(toTarget), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }
}
