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
        // 弹道导弹（PRESET 三段式）分支：GPS 制导 + preset_cruise_altitude > 0 时接管全程制导。
        // 与本体 PRESET 一致，不参与 track envelope / guidance angle 门限检查。
        RVP_PresetBallisticProfile preset = context.active().presetBallistic();
        if (preset != null && preset.active()
                && context.active().guidanceType() == RVP_EnumGuidanceType.GPS) {
            return applyPresetBallistic(context, target, preset);
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
            next = steerPredictiveIntercept(
                    projectile,
                    projectile.position(),
                    current,
                    target,
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

    /**
     * 弹道导弹（PRESET 三段式）全程制导：按上升/巡航/俯冲段生成转向速度。
     *
     * <p>阶段判定无记忆，由几何直接推断（与本体显式 phase 状态机等价）：未到上升段终点
     * 视为上升，否则按本体俯冲判据决定是否俯冲，两者皆否进入高度闭环巡航。</p>
     */
    private static boolean applyPresetBallistic(
            RVP_GuidanceRuntimeContext context,
            Vec3 target,
            RVP_PresetBallisticProfile preset
    ) {
        RVP_BaseBullet projectile = context.projectile();
        if (projectile == null || target == null) {
            return false;
        }
        projectile.rememberGuidancePos(target);
        projectile.setGuidanceTargetPos(target);

        if (!projectile.hasPresetProfileInitialized()) {
            Vec3 launch = projectile.getVirtualMidcourseLaunchPosition();
            Vec3 ascentPos = computePresetAscentPos(projectile.position(), target, launch, preset);
            Vec3 overheadPos = new Vec3(target.x, launch.y + preset.cruiseAltitude(), target.z);
            projectile.initializePresetProfile(launch, ascentPos, overheadPos);
        }
        Vec3 ascentPos = projectile.getPresetAscentPos();
        Vec3 overheadPos = projectile.getPresetOverheadPos();
        Vec3 launchPos = projectile.getPresetLaunchPos();
        if (ascentPos == null || overheadPos == null) {
            return false;
        }

        Vec3 current = projectile.getDeltaMovement();
        double speed = Math.max(projectile.getFlightSpeed(), current.length());
        if (speed <= 1.0E-6) {
            return false;
        }
        float factor = resolveTurningFactor(context);
        Vec3 next;
        if (shouldBeginPresetDive(projectile.position(), target, launchPos, current, preset, factor)) {
            next = steerPresetTerminal(current, projectile.position(), target, speed, factor);
        } else {
            // 弹道导弹抛物线制导：上升+中段合一。整条弹道是一条对称抛物线弧，
            // 最高点（apogee）位于弹道水平中段，爬升/下降角按射程自适应保持平缓，
            // 无陡直线爬升、尖顶与平飞段（弹道导弹形态而非巡航导弹形态）。
            next = steerPresetBallisticArc(current, projectile.position(), target, launchPos, preset, speed, factor);
        }
        if (next == null || next.lengthSqr() <= 1.0E-8) {
            return false;
        }
        projectile.setDeltaMovement(next);
        RVP_ProjectileMotion.applyGuidanceFacing(projectile, next);
        return true;
    }

    /**
     * 终端俯冲段转向：未过顶时正常指向目标；一旦已越过目标或极度接近，
     * 锁定当前水平方向、全力下压坠落，禁止 pure pursuit 翻转掉头导致的绕圈。
     *
     * <p>锁定后导弹不再水平追目标，靠下坠碰撞命中；水平偏差受锁定时刻的
     * 距离阈值（{@code speed*0.5}）约束，配合近炸/碰撞引信在目标附近引爆。</p>
     */
    static Vec3 steerPresetTerminal(
            Vec3 current,
            Vec3 position,
            Vec3 target,
            double speed,
            float turningFactor
    ) {
        if (target == null || speed <= 1.0E-8) {
            return current;
        }
        Vec3 toTarget = target.subtract(position);
        double horizontalSqr = toTarget.x * toTarget.x + toTarget.z * toTarget.z;
        Vec3 velocityHorizontal = new Vec3(current.x, 0, current.z);
        double velocityHorizontalSqr = velocityHorizontal.lengthSqr();
        boolean overshoot = horizontalSqr > 1.0E-8 && velocityHorizontalSqr > 1.0E-8
                && velocityHorizontal.dot(toTarget) < 0.0;
        boolean veryClose = horizontalSqr <= Math.max(1.0, speed * speed * 0.25);
        if (overshoot || veryClose) {
            // 终端锁定：保留当前水平方向分量（禁止 180° 翻转），垂直全力下压。
            Vec3 desired = velocityHorizontalSqr > 1.0E-8
                    ? velocityHorizontal.scale(0.25 / Math.sqrt(velocityHorizontalSqr)).add(0, -1, 0)
                    : new Vec3(0, -1, 0);
            return blendDirection(current, desired.normalize().scale(speed), speed,
                    Math.max(turningFactor, 0.5F));
        }
        return blendDirection(current, toTarget.normalize().scale(speed), speed, turningFactor);
    }

    /** 计算上升段终点：水平前伸 {@code min(maxAscentLead, 25%×水平距离)}，高度 = 发射点Y + 巡航高度。 */
    static Vec3 computePresetAscentPos(
            Vec3 projectilePos,
            Vec3 target,
            Vec3 launch,
            RVP_PresetBallisticProfile preset
    ) {
        if (target == null || launch == null || preset == null) {
            return null;
        }
        Vec3 horizontalToTarget = new Vec3(target.x - launch.x, 0, target.z - launch.z);
        double horizontalDistance = horizontalToTarget.length();
        Vec3 forward = horizontalDistance > 1.0E-6
                ? horizontalToTarget.scale(1.0D / horizontalDistance)
                : new Vec3(projectilePos == null ? 0 : 0, 0, 0);
        if (forward.lengthSqr() <= 1.0E-8) {
            forward = new Vec3(0, 0, 1);
        }
        double ascentLead = Math.min(preset.maxAscentLead(), horizontalDistance * 0.25);
        double cruiseY = launch.y + preset.cruiseAltitude();
        return new Vec3(
                launch.x + forward.x * ascentLead,
                cruiseY,
                launch.z + forward.z * ascentLead
        );
    }

    /** 俯冲段判据（本体公式 + RVP 近似转弯半径）：水平距离 ≤ 俯冲距离，或已越过目标。 */
    static boolean shouldBeginPresetDive(
            Vec3 projectilePos,
            Vec3 target,
            Vec3 launch,
            Vec3 velocity,
            RVP_PresetBallisticProfile preset,
            float turningFactor
    ) {
        if (projectilePos == null || target == null || preset == null) {
            return true;
        }
        double dx = projectilePos.x - target.x;
        double dz = projectilePos.z - target.z;
        double horizontalDistanceSqr = dx * dx + dz * dz;
        double verticalDistance = Math.max(0.0, projectilePos.y - target.y);
        double speed = velocity != null ? velocity.length() : 0.0;
        double turnRadius = resolvePresetTurnRadius(speed, turningFactor);
        double diveDistance = Math.max(preset.diveRadius(), Math.max(
                verticalDistance * preset.diveAltitudeFactor(),
                turnRadius * preset.diveLeadFactor()));
        if (horizontalDistanceSqr <= diveDistance * diveDistance) {
            return true;
        }
        if (launch == null) {
            return false;
        }
        Vec3 route = new Vec3(target.x - launch.x, 0, target.z - launch.z);
        Vec3 remaining = new Vec3(target.x - projectilePos.x, 0, target.z - projectilePos.z);
        return remaining.dot(route) <= 0.0;
    }

    /**
     * RVP 无 G 钳制转向，转弯半径用 {@code speed/turningFactor} 一阶近似并钳制范围。
     * <p>上限从 200 收窄到 80：turning_factor 是方向混合比例而非真实 G，speed/factor 在
     * 高速（speed≥30、factor=0.15）时顶到 200，乘上 dive_lead_factor 后俯冲启动距离
     * 高达 300+ 格，把巡航段整个吃掉（近距离发射"上升完直接俯冲"）。</p>
     */
    private static double resolvePresetTurnRadius(double speed, float turningFactor) {
        double effectiveFactor = Mth.clamp(turningFactor, 0.05F, 1.0F);
        return Mth.clamp(speed / effectiveFactor, 8.0, 80.0);
    }

    /**
     * 弹道导弹抛物线制导（上升+中段合一）：追“目标方向前方 lookAhead + 抛物线高度”的点。
     *
     * <p>弹道为对称抛物线：高度 = {@code launch.y + max(8, apogee×4p(1-p))}，p 为水平进度
     * （0 发射点 → 1 目标），最高点（apogee）在弹道水平中段。apogee 自适应为
     * {@code min(配置巡航高度, 水平射程×0.35)}，保证任意射程下爬升/下降角约 35°，
     * 短射程不会因配置的巡航高度过高而形成 60°+ 陡尖弧。取代旧的“追 ascentPos 陡直线
     * 爬升 → 尖顶 → 高度闭环平飞”巡航式三段制导。</p>
     */
    static Vec3 steerPresetBallisticArc(
            Vec3 current,
            Vec3 position,
            Vec3 target,
            Vec3 launch,
            RVP_PresetBallisticProfile preset,
            double speed,
            float turningFactor
    ) {
        if (target == null || preset == null) {
            return steerPursuit(current, target == null ? Vec3.ZERO : target, speed, turningFactor);
        }
        Vec3 toTargetH = new Vec3(target.x - position.x, 0, target.z - position.z);
        double hDist = toTargetH.length();
        if (hDist <= 1.0E-8) {
            return steerPursuit(current, target.subtract(position), speed, turningFactor);
        }
        Vec3 forwardH = toTargetH.scale(1.0 / hDist);
        double totalH = hDist;
        if (launch != null) {
            double dx = target.x - launch.x;
            double dz = target.z - launch.z;
            double totalSqr = dx * dx + dz * dz;
            if (totalSqr > 1.0E-8) {
                totalH = Math.sqrt(totalSqr);
            }
        }
        double p = Mth.clamp(1.0 - hDist / totalH, 0.0, 1.0);
        // 自适应弹道顶点：过高 apogee 在短射程下会形成陡尖弧（60°+ 爬升）
        double apogee = Math.min(preset.cruiseAltitude(), totalH * 0.35);
        // 抛物线高度（基线 base 起步，顶点 = apogee，末端回到 base）：全程单调平滑、无
        // 硬切换，竖直发射后追点自然略高于自身平滑转上爬，不会“压-拉-压”的蛇形振荡
        double base = Math.min(40.0, apogee * 0.3);
        double targetY = launch == null ? position.y + 8.0
                : launch.y + base + (apogee - base) * 4.0 * p * (1.0 - p);
        // 前方 lookAhead：末端（hDist→0）自动收敛到目标，追点法弹道圆润
        double lookAhead = Math.min(preset.maxAscentLead(), hDist * 0.3);
        Vec3 targetPoint = new Vec3(
                position.x + forwardH.x * lookAhead,
                targetY,
                position.z + forwardH.z * lookAhead);
        // 弹道中段战术机动：水平横向正弦蛇形规避摆动（如 Iskander 末端规避）。
        // 仅中段（p 0.15~0.85）生效、两端渐入渐出，相位基于水平进度 p（与虚拟端一致），
        // 不影响发射初期与俯冲末段，横向偏移 ≤ 幅度，不导致脱靶。
        float maneuverAmp = preset.tacticalManeuverAmplitude();
        if (maneuverAmp > 0f) {
            double fadeIn = Mth.clamp((p - 0.15) / 0.05, 0.0, 1.0);
            double fadeOut = Mth.clamp((0.85 - p) / 0.05, 0.0, 1.0);
            double weight = fadeIn * fadeOut;
            if (weight > 1.0E-4) {
                double phase = Math.PI * 2.0 * p * 5.0; // 每 0.2 进度一个完整周期
                // 目标方向水平法向（发射→目标连线垂直方向），左右交替
                Vec3 lateral = new Vec3(-forwardH.z, 0, forwardH.x);
                double offset = maneuverAmp * Math.sin(phase) * weight;
                targetPoint = targetPoint.add(lateral.scale(offset));
            }
        }
        return steerPursuit(current, targetPoint.subtract(position), speed, turningFactor);
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
                && context.projectile().getFlightTickCount() >= startTick
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

    /**
     * 预测拦截点制导（Predictive Intercept Point, PIP）：
     * 解算弹目相遇时间 t，计算目标未来位置 interceptPos = targetPos + targetVel * t，
     * 然后转向该拦截点。对本体的 intercept() 方法做 RVP 适配。
     *
     * <p>RVP 适配差异：
     * <ul>
     *   <li>无 maxG/dynamicPressure — 用 RVP 的 turningFactor 做速度 blend</li>
     *   <li>无 thrust/mass — 直接用当前 speed</li>
     *   <li>无 MAGIC_NUMBER 降级 — 用 closing-speed 近似做 fallback</li>
     * </ul>
     */
    public static Vec3 steerPredictiveIntercept(
            RVP_BaseBullet projectile,
            Vec3 missilePos,
            Vec3 missileVelocity,
            Vec3 targetPos,
            Vec3 targetVelocity,
            double speed,
            float turningFactor
    ) {
        if (missilePos == null || missileVelocity == null
                || targetPos == null || targetVelocity == null || speed <= 1.0E-8) {
            return null;
        }

        double missileSpeed = missileVelocity.length();
        if (missileSpeed <= 1.0E-8) {
            return null;
        }

        Vec3 relPos = targetPos.subtract(missilePos);
        double targetSpeedSq = targetVelocity.lengthSqr();

        // 解一元二次方程 a*t² + b*t + c = 0 求相遇时间 t
        double a = targetSpeedSq - (missileSpeed * missileSpeed);
        double b = 2.0 * relPos.dot(targetVelocity);
        double c = relPos.lengthSqr();

        double t = -1.0;
        if (Math.abs(a) < 1.0E-8) {
            // 速度相近：线性退化
            if (b < 0) {
                t = -c / b;
            }
        } else {
            double discriminant = b * b - 4.0 * a * c;
            if (discriminant >= 0.0) {
                double sqrtD = Math.sqrt(discriminant);
                double t1 = (-b + sqrtD) / (2.0 * a);
                double t2 = (-b - sqrtD) / (2.0 * a);
                if (t1 > 0.0 && t2 > 0.0) {
                    t = Math.min(t1, t2);
                } else {
                    t = Math.max(t1, t2);
                }
            }
        }

        // Fallback：closing-speed 近似
        if (t <= 0.0) {
            Vec3 relVel = targetVelocity.subtract(missileVelocity);
            double closingSpeed = missileSpeed - relVel.dot(relPos.normalize());
            t = relPos.length() / Math.max(closingSpeed, 0.1);
        }

        // 计算预测拦截点
        Vec3 interceptPos = targetPos.add(targetVelocity.scale(t));

        // 转向拦截点（RVP 的 blend 模型）
        Vec3 desired = interceptPos.subtract(missilePos).normalize().scale(speed);
        return blendDirection(missileVelocity, desired, speed, turningFactor);
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
        Float configured = context.data().getProjectileData().resolveTurningFactor(context.projectile().getFlightTickCount());
        return configured != null ? configured : 0.5f;
    }

    private static boolean shouldUseProportionalNavigation(RVP_GuidanceRuntimeContext context) {
        if (context == null || !context.active().predictTargetPos()) {
            return false;
        }
        RVP_BaseBullet projectile = context.projectile();
        if (projectile == null || projectile.getFlightTickCount() < context.active().predictTargetPosStartTick()) {
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
        int motorTick = projectile.getFlightTickCount() - ignition;
        if (motorTick <= data.getResolvedMotorBurnTime()) {
            return false;
        }
        return projectile.getSecondPulseStartTick() < 0;
    }
}
