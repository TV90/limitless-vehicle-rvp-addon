package org.ywzj.rvp.guidance;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileMotion;

/** New-schema steering math. It never reads legacy steering_data. */
public final class RVP_GuidanceRuntimeMath {

    private static final double PN_GAIN = 3.0;

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
        boolean trackLimitsPassed = target != null
                && RVP_GuidanceRuntimeGeometry.passesTrackLimits(projectile, target, context.active());
        boolean irGrace = entity != null
                && context.active().guidanceType() == RVP_EnumGuidanceType.IR
                && projectile.hasIrSeekerGrace();
        if (target == null || (!trackLimitsPassed && !irGrace)) {
            return false;
        }

        projectile.rememberGuidancePos(target);
        projectile.setTargetPos(target);

        Vec3 current = projectile.getDeltaMovement();
        double speed = Math.max(projectile.getFlightSpeed(), current.length());
        if (speed <= 1.0E-6) {
            return false;
        }
        float factor = resolveTurningFactor(context);
        Vec3 next;
        if (entity != null && context.active().predictTargetPos()) {
            next = steerProportional(
                    projectile.position(),
                    current,
                    target,
                    entity.getDeltaMovement(),
                    speed,
                    factor
            );
        } else {
            next = steerPursuit(current, target.subtract(projectile.position()), speed, factor);
        }
        if (next == null || next.lengthSqr() <= 1.0E-8) {
            return false;
        }
        projectile.setDeltaMovement(next);
        RVP_ProjectileMotion.applyGuidanceFacing(projectile, next);
        return true;
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
        if (missilePos == null || missileVelocity == null || targetPos == null
                || targetVelocity == null || speed <= 1.0E-8 || missileVelocity.lengthSqr() <= 1.0E-8) {
            return missileVelocity;
        }
        Vec3 relativePosition = targetPos.subtract(missilePos);
        double distanceSqr = relativePosition.lengthSqr();
        if (distanceSqr <= 1.0E-8) {
            return missileVelocity;
        }
        Vec3 relativeVelocity = targetVelocity.subtract(missileVelocity);
        Vec3 lineOfSight = relativePosition.normalize();
        double closingVelocity = -relativeVelocity.dot(lineOfSight);
        if (closingVelocity <= 0.0) {
            return missileVelocity.normalize().scale(speed);
        }

        Vec3 velocityDirection = missileVelocity.normalize();
        Vec3 lineOfSightRate = relativePosition.cross(relativeVelocity).scale(1.0 / distanceSqr);
        Vec3 lateralCommand = lineOfSightRate.cross(velocityDirection).scale(PN_GAIN * closingVelocity);
        lateralCommand = lateralCommand.subtract(velocityDirection.scale(lateralCommand.dot(velocityDirection)));
        Vec3 commanded = missileVelocity.add(lateralCommand);
        return blendDirection(missileVelocity, commanded, speed, turningFactor);
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
}
