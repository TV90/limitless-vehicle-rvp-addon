package org.ywzj.rvp.guidance;

import net.minecraft.world.entity.Entity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileMotion;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

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
        if (target == null) {
            return false;
        }
        boolean trackEnvelopePassed = RVP_GuidanceRuntimeGeometry.passesTrackEnvelope(projectile, target, context.active());
        boolean irGrace = entity != null
                && context.active().guidanceType() == RVP_EnumGuidanceType.IR
                && projectile.hasIrSeekerGrace();
        if (!trackEnvelopePassed && !irGrace) {
            return false;
        }

        projectile.rememberGuidancePos(target);
        projectile.setGuidanceTargetPos(target);
        float factor = resolveTurningFactor(context);
        // 进入目标 20 格半径圆柱内，直接俯冲并大幅提升转向能力
        double horizontalDistToTarget = horizontalDistance(projectile.position(), target);
        boolean inTerminalDiveCylinder = context.active().topAttackHeight() != null
                && context.active().topAttackHeight() > 0f
                && horizontalDistToTarget <= 20.0D;
        Vec3 steeringTarget;
        if (inTerminalDiveCylinder) {
            steeringTarget = target;
            factor = Math.max(factor, 0.8f);
        } else {
            steeringTarget = resolveTopAttackAimPoint(
                    projectile, target, context.active().topAttackHeight(), factor);
        }
        if (context.active().topAttackHeight() != null
                && context.active().topAttackHeight() > 0f
                && projectile.hasReachedTopAttackApex()) {
            factor = resolveTopAttackTerminalTurningFactor(
                    projectile.position(), target, projectile.getDeltaMovement(), factor);
        }

        Vec3 current = projectile.getDeltaMovement();
        double speed = Math.max(projectile.getFlightSpeed(), current.length());
        if (speed <= 1.0E-6) {
            return false;
        }
        if (!passesGuidanceAngle(projectile, steeringTarget, context.active()) && !irGrace) {
            return false;
        }
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
        } else if (entity != null && shouldUseProportionalNavigation(context)) {
            next = steerProportional(
                    projectile,
                    projectile.position(),
                    current,
                    steeringTarget,
                    target,
                    entity.getDeltaMovement(),
                    speed,
                    factor,
                    context.active()
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

    static Vec3 resolveTopAttackAimPoint(
            RVP_BaseBullet projectile,
            Vec3 target,
            Float topAttackHeight,
            float turningFactor
    ) {
        if (projectile == null || target == null || topAttackHeight == null
                || Math.abs(topAttackHeight) <= 1.0E-6f) {
            return target;
        }
        // 极近距离直接俯冲，跳过攻顶弹道
        double distToTarget = horizontalDistance(projectile.position(), target);
        if (topAttackHeight > 0f && distToTarget < 8.0D) {
            return target;
        }
        if (topAttackHeight < 0f) {
            return resolveDescendingApproachAimPoint(projectile.position(), target, topAttackHeight);
        }

        Vec3 apex = projectile.getTopAttackApexPos();
        if (apex == null) {
            Vec3 launch = projectile.position();
            apex = computeTopAttackApex(launch, target, topAttackHeight);
            projectile.initializeTopAttackProfile(launch, target, apex);
        }
        if (projectile.hasReachedTopAttackApex()) {
            return target;
        }

        Vec3 launch = projectile.getTopAttackLaunchPos();
        Vec3 initialTarget = projectile.getTopAttackInitialTargetPos();
        if (shouldEnterTopAttackTerminal(
                projectile.position(), target, launch, initialTarget,
                projectile.getDeltaMovement(), turningFactor)) {
            projectile.markTopAttackApexReached();
            return target;
        }
        boolean passedMidpoint = hasPassedTopAttackMidpoint(projectile.position(), launch, initialTarget);
        double speed = Math.max(projectile.getFlightSpeed(), projectile.getDeltaMovement().length());
        double altitudeTolerance = Mth.clamp(speed * 1.5D, 4.0D, 24.0D);
        if (passedMidpoint && projectile.getY() >= apex.y - altitudeTolerance) {
            projectile.markTopAttackApexReached();
            return target;
        }
        if (!passedMidpoint) {
            return apex;
        }

        Vec3 horizontalAxis = horizontalDirection(launch, initialTarget);
        if (horizontalAxis.lengthSqr() <= 1.0E-8D) {
            return apex;
        }
        double forwardLook = Mth.clamp(speed * 3.0D, 4.0D, 64.0D);
        double remainingAlongAxis = remainingDistanceAlongAxis(projectile.position(), launch, initialTarget);
        double turnInDistance = resolveTopAttackTurnInDistance(speed, turningFactor);
        forwardLook = Math.min(forwardLook, Math.max(remainingAlongAxis - turnInDistance, 0.0D));
        return new Vec3(
                projectile.getX() + horizontalAxis.x * forwardLook,
                apex.y,
                projectile.getZ() + horizontalAxis.z * forwardLook
        );
    }

    static Vec3 computeTopAttackApex(Vec3 launch, Vec3 target, float topAttackHeight) {
        if (launch == null || target == null) {
            return target;
        }
        double horizontalDistance = horizontalDistance(launch, target);
        // 近距离时按比例缩减顶点高度，避免导弹冲过目标
        double effectiveHeight = Math.min(Math.max(topAttackHeight, 0f), horizontalDistance);
        double apexRatio = 0.5D;
        return new Vec3(
                launch.x + (target.x - launch.x) * apexRatio,
                target.y + effectiveHeight,
                launch.z + (target.z - launch.z) * apexRatio
        );
    }

    static boolean hasPassedTopAttackMidpoint(Vec3 projectilePos, Vec3 launch, Vec3 initialTarget) {
        if (projectilePos == null || launch == null || initialTarget == null) {
            return false;
        }
        Vec3 axis = new Vec3(initialTarget.x - launch.x, 0.0D, initialTarget.z - launch.z);
        double axisLengthSqr = axis.lengthSqr();
        if (axisLengthSqr <= 1.0E-8D) {
            return true;
        }
        Vec3 fromLaunch = new Vec3(projectilePos.x - launch.x, 0.0D, projectilePos.z - launch.z);
        return fromLaunch.dot(axis) >= axisLengthSqr * 0.5D;
    }

    static boolean shouldEnterTopAttackTerminal(
            Vec3 projectilePos,
            Vec3 target,
            Vec3 launch,
            Vec3 initialTarget,
            Vec3 velocity,
            float turningFactor
    ) {
        if (projectilePos == null || target == null || launch == null || initialTarget == null) {
            return true;
        }
        double horizontalDistance = horizontalDistance(projectilePos, target);
        double speed = velocity != null ? velocity.length() : 0.0D;
        double turnInDistance = resolveTopAttackTurnInDistance(speed, turningFactor);
        if (horizontalDistance <= turnInDistance) {
            return true;
        }
        return remainingDistanceAlongAxis(projectilePos, launch, initialTarget) <= 0.0D;
    }

    static double resolveTopAttackTurnInDistance(double speed, float turningFactor) {
        double effectiveFactor = Mth.clamp(turningFactor, 0.05F, 1.0F);
        double responseTicks = Mth.clamp(1.0D / effectiveFactor, 2.0D, 12.0D);
        // 近距离时降低下限，让导弹更早进入俯冲
        return Mth.clamp(Math.max(speed, 0.0D) * responseTicks * 1.5D, 4.0D, 160.0D);
    }

    static float resolveTopAttackTerminalTurningFactor(
            Vec3 projectilePos, Vec3 target, Vec3 velocity, float turningFactor) {
        if (projectilePos == null || target == null) {
            return turningFactor;
        }
        double speed = velocity != null ? velocity.length() : 0.0D;
        double distance = horizontalDistance(projectilePos, target);
        double responseWindow = Mth.clamp(speed * 12.0D, 48.0D, 240.0D);
        double urgency = 1.0D - Mth.clamp(distance / responseWindow, 0.0D, 1.0D);
        float terminalFloor = (float) Mth.lerp(urgency, 0.35D, 0.60D);
        return Math.max(turningFactor, terminalFloor);
    }

    private static double remainingDistanceAlongAxis(Vec3 projectilePos, Vec3 launch, Vec3 initialTarget) {
        if (projectilePos == null || launch == null || initialTarget == null) {
            return 0.0D;
        }
        Vec3 axis = new Vec3(initialTarget.x - launch.x, 0.0D, initialTarget.z - launch.z);
        double axisLength = axis.length();
        if (axisLength <= 1.0E-8D) {
            return 0.0D;
        }
        Vec3 fromLaunch = new Vec3(projectilePos.x - launch.x, 0.0D, projectilePos.z - launch.z);
        return axisLength - fromLaunch.dot(axis.scale(1.0D / axisLength));
    }

    private static double horizontalDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Vec3 horizontalDirection(Vec3 launch, Vec3 target) {
        if (launch == null || target == null) {
            return Vec3.ZERO;
        }
        Vec3 axis = new Vec3(target.x - launch.x, 0.0D, target.z - launch.z);
        return axis.lengthSqr() > 1.0E-8D ? axis.normalize() : Vec3.ZERO;
    }

    private static boolean passesGuidanceAngle(
            RVP_BaseBullet projectile,
            Vec3 steeringTarget,
            RVP_GuidanceActiveConfig config
    ) {
        if (projectile == null || steeringTarget == null || config == null) {
            return false;
        }
        Vec3 axis = projectile.getDeltaMovement();
        if (axis == null || axis.lengthSqr() <= 1.0E-8D) {
            axis = projectile.getLookAngle();
        }
        return RVP_GuidanceRuntimeGeometry.withinAngle(
                axis,
                steeringTarget.subtract(projectile.position()),
                config.maxGuidanceAngle()
        );
    }

    private static Vec3 resolveDescendingApproachAimPoint(Vec3 projectilePos, Vec3 target, float topAttackHeight) {
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
            RVP_BaseBullet projectile,
            Vec3 missilePos,
            Vec3 missileVelocity,
            Vec3 aimPoint,
            Vec3 targetPos,
            Vec3 targetVelocity,
            double speed,
            float turningFactor,
            RVP_GuidanceActiveConfig config
    ) {
        if (projectile == null || missilePos == null || missileVelocity == null
                || aimPoint == null || targetPos == null || targetVelocity == null
                || speed <= 1.0E-8 || config == null) {
            return null;
        }
        Vec3 base = steerPursuit(missileVelocity, aimPoint.subtract(missilePos), speed, turningFactor);
        Vec3 pnDelta = proportionalNavigationDelta(
                projectile,
                missileVelocity,
                targetPos,
                targetVelocity,
                config.predictTargetPosGain(),
                config.maxLateralAccel(),
                turningFactor
        );
        Vec3 next = base.add(pnDelta);
        if (next.lengthSqr() <= 1.0E-8) {
            return base;
        }
        return next.normalize().scale(speed);
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

    private static boolean shouldUseProportionalNavigation(RVP_GuidanceRuntimeContext context) {
        if (context == null || !context.active().predictTargetPos()) {
            return false;
        }
        RVP_BaseBullet projectile = context.projectile();
        if (projectile == null || projectile.tickCount < context.active().predictTargetPosStartTick()) {
            return false;
        }
        return !isWaitingForSecondPulse(projectile);
    }

    private static boolean isWaitingForSecondPulse(RVP_BaseBullet projectile) {
        if (projectile == null || !projectile.isMissile()) {
            return false;
        }
        RVP_WeaponData data = projectile.getRvpData();
        if (data == null || !data.getProjectileData().usesSecondPulse()) {
            return false;
        }
        int ignition = data.getResolvedIgnitionDelayTick();
        int motorTick = projectile.tickCount - ignition;
        if (motorTick <= data.getResolvedMotorBurnTime()) {
            return false;
        }
        return projectile.getSecondPulseStartTick() < 0;
    }

    private static Vec3 proportionalNavigationDelta(
            RVP_BaseBullet projectile,
            Vec3 missileVelocity,
            Vec3 targetPos,
            Vec3 targetVelocity,
            double gain,
            double maxLateralAccel,
            float turningFactor
    ) {
        if (projectile == null || missileVelocity == null || targetPos == null || targetVelocity == null || gain <= 0.0) {
            return Vec3.ZERO;
        }
        double speed = missileVelocity.length();
        if (speed <= 1.0E-6) {
            return Vec3.ZERO;
        }

        Vec3 relPos = targetPos.subtract(projectile.position());
        double relPosLenSqr = relPos.lengthSqr();
        if (relPosLenSqr <= 1.0E-6) {
            return Vec3.ZERO;
        }

        Vec3 relVel = targetVelocity.subtract(missileVelocity);
        Vec3 los = relPos.normalize();
        double closingVelocity = -relVel.dot(los);
        if (closingVelocity <= 0.0) {
            return Vec3.ZERO;
        }

        Vec3 vHat = missileVelocity.normalize();
        Vec3 omega = relPos.cross(relVel).scale(1.0 / relPosLenSqr);
        Vec3 lateralAccel = omega.cross(vHat).scale(gain * closingVelocity);
        double parallel = lateralAccel.dot(vHat);
        if (Math.abs(parallel) > 1.0E-9) {
            lateralAccel = lateralAccel.subtract(vHat.scale(parallel));
        }

        double lateralLen = lateralAccel.length();
        if (maxLateralAccel > 0.0 && lateralLen > maxLateralAccel) {
            lateralAccel = lateralAccel.scale(maxLateralAccel / lateralLen);
        }

        if (turningFactor <= 0.0f) {
            return Vec3.ZERO;
        }
        return lateralAccel.scale(Math.max(0f, Math.min(1f, turningFactor)));
    }
}
