package org.ywzj.rvp.guidance;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileMotion;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/** New-schema steering math. It never reads legacy steering_data. */
public final class RVP_GuidanceRuntimeMath {

    private RVP_GuidanceRuntimeMath() {}

    public static boolean applyIntent(
            RVP_GuidanceRuntimeContext context,
            RVP_GuidanceIntent intent
    ) {
        RVP_BaseBullet projectile = context.projectile();
        Entity entity = intent.aimEntity();
        Vec3 target = entity != null && entity.isAlive()
                ? entity.getBoundingBox().getCenter()
                : intent.aimPoint();
        if (entity != null && entity.isAlive() && target != null) {
            projectile.rememberGuidancePos(target);
        }
        boolean trackLimitsPassed = target != null
                && RVP_GuidanceRuntimeGeometry.passesTrackLimits(projectile, target, context.active());
        boolean irGrace = entity != null
                && context.active().guidanceType() == RVP_EnumGuidanceType.IR
                && projectile.hasIrSeekerGrace();
        if (target == null || (!trackLimitsPassed && !irGrace)) {
            return false;
        }

        projectile.rememberGuidancePos(target);
        projectile.setGuidanceTargetPos(target);
        Vec3 steeringTarget = resolveTopAttackAimPoint(
                projectile.position(), target, context.active().topAttackHeight());

        Vec3 current = projectile.getDeltaMovement();
        double speed = Math.max(projectile.getFlightSpeed(), current.length());
        if (speed <= 1.0E-6) {
            return false;
        }
        float factor = resolveTurningFactor(context);
        if (intent.directMotion()) {
            RVP_WireGuidanceSteering.applyFromDirection(
                    projectile, steeringTarget.subtract(projectile.position()), factor);
            return true;
        }
        Vec3 next;
        if (isGpsCruiseActive(context, steeringTarget)) {
            next = steerGpsCruise(
                    current,
                    steeringTarget.subtract(projectile.position()),
                    speed,
                    factor,
                    context.active().cruiseLevelingFactor(),
                    projectile.consumeGpsCruiseVerticalResetPending()
            );
        } else if (entity != null && context.active().predictTargetPos()) {
            next = steerInterceptLikeNative(
                    projectile,
                    current,
                    steeringTarget,
                    entity.getDeltaMovement(),
                    speed,
                    factor
            );
            if (next == null || next.lengthSqr() <= 1.0E-8) {
                next = steerPursuit(current, steeringTarget.subtract(projectile.position()), speed, factor);
            }
        } else {
            next = steerPursuit(current, steeringTarget.subtract(projectile.position()), speed, factor);
        }
        if (next == null || next.lengthSqr() <= 1.0E-8) {
            return false;
        }
        projectile.setDeltaMovement(next);
        RVP_ProjectileMotion.applyGuidanceFacing(projectile, next);
        return true;
    }

    static Vec3 resolveTopAttackAimPoint(Vec3 projectilePos, Vec3 target, Float topAttackHeight) {
        if (projectilePos == null || target == null || topAttackHeight == null
                || Math.abs(topAttackHeight) <= 1.0E-6f) {
            return target;
        }
        double horizontalDistance = Math.sqrt(
                projectilePos.distanceToSqr(target.x, projectilePos.y, target.z));
        double height = Math.copySign(
                Math.min(Math.abs(topAttackHeight), horizontalDistance), topAttackHeight);
        return target.add(0, height, 0);
    }

    static Vec3 steerGpsCruise(
            Vec3 current,
            Vec3 toTarget,
            double speed,
            float turningFactor,
            float levelingFactor,
            boolean resetVertical
    ) {
        Vec3 horizontal = new Vec3(toTarget.x, 0, toTarget.z);
        double horizontalDistance = horizontal.length();
        if (horizontalDistance <= 1.0E-8) {
            return steerPursuit(current, toTarget, speed, turningFactor);
        }
        double currentHorizontalSpeed = Math.sqrt(current.x * current.x + current.z * current.z);
        double desiredHorizontalSpeed = Math.max(currentHorizontalSpeed, speed * 0.01);
        Vec3 desired = horizontal.scale(desiredHorizontalSpeed / horizontalDistance);
        float factor = Math.max(0f, Math.min(1f, turningFactor));
        double nextY = resetVertical ? 0.0 : current.y;
        if (!resetVertical && nextY > 0.0) {
            nextY += (0.0 - nextY) * Math.max(0f, Math.min(1f, levelingFactor));
        }
        return new Vec3(
                current.x + (desired.x - current.x) * factor,
                nextY,
                current.z + (desired.z - current.z) * factor
        );
    }

    private static boolean isGpsCruiseActive(RVP_GuidanceRuntimeContext context, Vec3 target) {
        Integer startTick = context.active().cruiseStartTick();
        return context.active().guidanceType() == RVP_EnumGuidanceType.GPS
                && startTick != null
                && context.projectile().tickCount >= startTick
                && context.projectile().horizontalDistanceTo(target)
                > context.active().cruiseEndHorizontalDist();
    }

    public static Vec3 steerPursuit(Vec3 current, Vec3 toTarget, double speed, float turningFactor) {
        if (toTarget == null || toTarget.lengthSqr() <= 1.0E-8 || speed <= 1.0E-8) {
            return current;
        }
        Vec3 desired = toTarget.normalize().scale(speed);
        return blendDirection(current, desired, speed, turningFactor);
    }

    public static Vec3 steerProportional(
            Vec3 missilePos,
            Vec3 missileVelocity,
            Vec3 targetPos,
            Vec3 targetVelocity,
            double speed,
            float turningFactor
    ) {
        return steerPursuit(
                missileVelocity,
                targetPos == null || missilePos == null ? null : targetPos.subtract(missilePos),
                speed,
                turningFactor
        );
    }

    public static Vec3 steerInterceptLikeNative(
            RVP_BaseBullet projectile,
            Vec3 current,
            Vec3 targetPos,
            Vec3 targetVelocity,
            double speed,
            float turningFactor
    ) {
        if (projectile == null || current == null || targetPos == null || targetVelocity == null || speed <= 1.0E-8) {
            return null;
        }
        RVP_InterceptSolver.Solution solution = RVP_InterceptSolver.solve(
                projectile.position(),
                current,
                speed,
                targetPos,
                targetVelocity
        );
        Vec3 interceptPos = solution.interceptPos() != null ? solution.interceptPos() : targetPos;
        Vec3 toIntercept = interceptPos.subtract(projectile.position());
        if (toIntercept.lengthSqr() <= 1.0E-8) {
            return null;
        }

        Vec3 targetDir = toIntercept.normalize();
        double acceleration = resolveGuidanceAcceleration(projectile);
        Vec3 desiredDir;
        if (acceleration > 1.0E-8) {
            double dot = current.dot(targetDir);
            double magSq = current.lengthSqr();
            double discriminant = dot * dot - (magSq - acceleration * acceleration);
            if (discriminant < 0.0) {
                desiredDir = targetDir.scale(dot * PhysicsEngine.MAGIC_NUMBER * 4.0).subtract(current);
            } else {
                desiredDir = targetDir.scale(dot + Math.sqrt(discriminant)).subtract(current);
            }
        } else {
            desiredDir = toIntercept;
        }
        if (desiredDir.lengthSqr() <= 1.0E-8) {
            desiredDir = toIntercept;
        }
        return blendDirection(current, desiredDir, speed, turningFactor);
    }

    private static Vec3 blendDirection(Vec3 current, Vec3 desired, double speed, float turningFactor) {
        if (desired == null || desired.lengthSqr() <= 1.0E-8) {
            return current;
        }
        float factor = Math.max(0f, Math.min(1f, turningFactor));
        if (current == null || current.lengthSqr() <= 1.0E-8) {
            return desired.normalize().scale(speed);
        }
        Vec3 blended = current.normalize().scale(1.0 - factor).add(desired.normalize().scale(factor));
        if (blended.lengthSqr() <= 1.0E-8) {
            return current.normalize().scale(speed);
        }
        return blended.normalize().scale(speed);
    }

    private static float resolveTurningFactor(RVP_GuidanceRuntimeContext context) {
        Float configured = context.data().getProjectileData().resolveTurningFactor(context.projectile().tickCount);
        return configured != null ? configured : 0.5f;
    }

    private static double resolveGuidanceAcceleration(RVP_BaseBullet projectile) {
        if (projectile == null || projectile.getRvpData() == null) {
            return 0.0;
        }
        if (!projectile.getRvpData().usesPropulsion()) {
            return 0.0;
        }
        float mass = Math.max(projectile.getRvpData().getResolvedMass(), 1.0E-6f);
        float thrust = projectile.getRvpData().getResolvedThrust();
        if (thrust <= 0f) {
            return 0.0;
        }
        return thrust / mass;
    }
}
