package org.ywzj.rvp.guidance;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_ProjectileMotion;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringLimits;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringSolution;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_BallisticTrajectoryMath;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_TrajectorySteeringMath;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_PIPAttackAngleController;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_PredictiveInterceptMath;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_TargetMotionState;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** New-schema steering math. It never reads legacy steering_data. */
public final class RVP_GuidanceRuntimeMath {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_GuidanceRuntimeMath() {}

    public static boolean applyIntent(
            RVP_GuidanceRuntimeContext context,
            RVP_GuidanceIntent intent
    ) {
        RVP_BaseBullet projectile = context.projectile();
        Entity entity = intent.aimEntity();
        // [RVP] §46（2026-10-01 用户定版）：制导瞄准点 = 目标**最大体积 OBB** 的中心
        // （非载具/广播克隆回退 AABB 中心），长/斜载具不再瞄悬空的 AABB 中心；
        // 下方 PIP 预测消费同一 target 变量自动跟随
        Vec3 target = entity != null && entity.isAlive()
                ? org.ywzj.rvp.util.RVP_AimPointResolver.resolveLargestObbCenter(entity)
                : intent.aimPoint();
        if (entity != null && entity.isAlive() && target != null) {
            projectile.rememberGuidancePos(target);
        }
        if (target == null) {
            return false;
        }
        // 调用本项目弹体的目标运动采样器；PIP/PRESET 共用同一份速度与短时加速度历史，
        // 避免不同制导分支分别读取实体状态而产生一个 Tick 的预测相位差。
        RVP_TargetMotionState targetMotion = entity != null && entity.isAlive()
                ? projectile.updateGuidanceTargetMotion(entity, target)
                : RVP_TargetMotionState.stationary(target);
        // 弹道导弹（PRESET 三段式）分支：GPS 制导 + preset_cruise_altitude > 0 时接管全程制导。
        // 与本体 PRESET 一致，不参与 track envelope / guidance angle 门限检查。
        // 冷发射交接（2026-10-05 定版，纯 fishking 20b76ac5+31c0c988 行为）：PRESET 从
        // flightTick 0 即接管，冷发射窗口内的速度覆盖由运动层接管机制处理
        // （shouldPreserveGuidanceDuringColdLaunch + 粘性标记），窗口末段弹即开始转向；
        // 本会话曾叠加的"点火门/程序转弯/45° 下限"经用户实测劣于纯 fishking 行为，已全部移除。
        RVP_PresetBallisticProfile preset = context.active().presetBallistic();
        if (preset != null && preset.active()
                && context.active().guidanceType() == RVP_EnumGuidanceType.GPS) {
            return applyPresetBallistic(context, target, targetMotion, preset);
        }
        boolean trackEnvelopePassed = RVP_GuidanceRuntimeGeometry.passesTrackEnvelope(projectile, target, context.active());
        boolean irGrace = entity != null
                && context.active().guidanceType() == RVP_EnumGuidanceType.IR
                && projectile.hasIrSeekerGrace();
        if (!trackEnvelopePassed && !irGrace) {
            // 调用本项目惰性诊断，区分包线拒绝与 PIP 执行器没有跟上目标。
            noteGuidanceDecision(context, "REJECTED", "TRACK_ENVELOPE", target, null, null);
            return false;
        }

        projectile.rememberGuidancePos(target);
        projectile.setGuidanceTargetPos(target);
        float factor = resolveTurningFactor(context);
        // 攻顶瞄准点：目标上空 min(|H|, 水平距离) 处，每 tick 重算（恢复 8c30656 无状态算法）
        Vec3 steeringTarget = resolveTopAttackAimPoint(
                projectile.position(), target, context.active().topAttackHeight());
        boolean isTopAttack = context.active().topAttackHeight() != null
                && Math.abs(context.active().topAttackHeight()) > 1.0E-6f;
        // 空空高抛（loft，2026-10-04 用户需求）：瞄准点垂直塑形叠加在现有比例引导之上——
        // 瞄准点 = 目标实时位置 + (0, loft_height_above_target, 0)（相对目标高度差，动对动
        // 跟随目标升降），水平距离进入 [end, end+blend] 区间按 smoothstep 平滑退坡、低于
        // end 归零——末端原生预测拦截（比例引导）无缝接管，全程一条制导链无分支切换。
        // 近距（<end）blend 恒 0 等效无 loft；ARH/AIR 截获后不施加（自主导引头俯冲追击
        // 不受干扰）；与 top_attack_height 互斥建议同时只配一个，同配时 loft 优先。
        Vec3 loftedTarget = null;
        float loftHeight = context.active().loftHeightAboveTarget();
        if (loftHeight > 0f && !projectile.hasAutonomousSeekerCatch()) {
            float loftEnd = context.active().loftEndDistance();
            float loftBlend = context.active().loftBlendDistance();
            double dxLoft = target.x - projectile.position().x;
            double dzLoft = target.z - projectile.position().z;
            double hDistLoft = Math.sqrt(dxLoft * dxLoft + dzLoft * dzLoft);
            double blend = loftBlend > 0f
                    ? Mth.clamp((hDistLoft - loftEnd) / loftBlend, 0.0D, 1.0D)
                    : (hDistLoft > loftEnd ? 1.0D : 0.0D);
            blend = blend * blend * (3.0D - 2.0D * blend);
            loftedTarget = new Vec3(target.x, Mth.lerp(blend, target.y, target.y + loftHeight), target.z);
            steeringTarget = loftedTarget;
            if (isTopAttack) {
                // 同配时 loft 优先：恢复预测拦截可用性（topAttack 会禁用预测拦截分支）
                isTopAttack = false;
            }
        }

        Vec3 current = projectile.getDeltaMovement();
        double speed = Math.max(projectile.getMotionSpeedReference(), current.length());
        if (speed <= 1.0E-6) {
            return false;
        }
        boolean withinGuidanceAngle = passesGuidanceAngle(projectile, steeringTarget, context.active());
        boolean hitlIntent = intent.sourceType() == RVP_EnumGuidanceType.HITL_TV
                || intent.sourceType() == RVP_EnumGuidanceType.HITL_CLOS_TV;
        if (RVP_DebugFlags.HITL.isEnabled() && !projectile.level().isClientSide()) {
            // HITL 转向链诊断（/rvpdebug flags hitl on）：三列对照——服务端收到输入角 /
            // 舵量角 / 弹速方向夹角，用于定位"指令航向冻结（转弯率归零）"发生在哪一环
            Vec3 velNorm = current.lengthSqr() > 1.0E-8 ? current.normalize() : projectile.getLookAngle();
            Vec3 toTargetNorm = steeringTarget.subtract(projectile.position()).normalize();
            double cmdAngle = Math.toDegrees(Math.acos(Mth.clamp(velNorm.dot(toTargetNorm), -1.0, 1.0)));
            float inYaw = Float.NaN;
            float inPitch = Float.NaN;
            float steerYaw = Float.NaN;
            float steerPitch = Float.NaN;
            if (projectile instanceof org.ywzj.rvp.entity.projectile.RVP_MissileEntity missile) {
                inYaw = missile.rvp$getHitlInputYaw();
                inPitch = missile.rvp$getHitlInputPitch();
                steerYaw = missile.rvp$getHitlSteeringYaw();
                steerPitch = missile.rvp$getHitlSteeringPitch();
            }
            LOGGER.info("[RVP-HITL] id={} tick={} cmdAngle={} within={} direct={} "
                            + "inYaw={} inPitch={} steerYaw={} steerPitch={} vel=({}/{}/{})",
                    projectile.getId(), projectile.tickCount,
                    String.format("%.1f", cmdAngle), withinGuidanceAngle, intent.directMotion(),
                    String.format("%.1f", inYaw), String.format("%.1f", inPitch),
                    String.format("%.1f", steerYaw), String.format("%.1f", steerPitch),
                    String.format("%.2f", current.x), String.format("%.2f", current.y), String.format("%.2f", current.z));
        }
        if (!withinGuidanceAngle && !irGrace && !intent.directMotion()) {
            // 电视弹（HITL_TV/DESIGNATE 追踪路径）与自动制导：超角维持原拒绝规则（行为不变）
            // 调用本项目诊断记录速度轴超角，避免将停导误判为控制器弱响应。
            noteGuidanceDecision(context, "REJECTED", "GUIDANCE_ANGLE", steeringTarget, null, null);
            return false;
        }
        if (intent.directMotion()) {
            Vec3 desired = steeringTarget.subtract(projectile.position());
            // HITL 人手直控（2026-09-10）：指令超出 max_guidance_angle 锥角时不再整 tick 拒绝转向——
            // 绕大圈回打（如攻击发射载具）时夹角恒超限，原逻辑表现为"导弹拒转直飞、被强行掰走"。
            // 改为把指令方向钳制到锥角边缘贴边尽量转，随弹体转向夹角收敛后完全跟随指令航向。
            if (!withinGuidanceAngle && !irGrace) {
                desired = RVP_GuidanceRuntimeGeometry.clampToAngle(
                        current.lengthSqr() > 1.0E-8 ? current : projectile.getLookAngle(),
                        desired, context.active().maxGuidanceAngle());
            }
            // 调用本项目直控转向入口；入口内部同样按 rvp_maxg 优先规则选择钳制算法。
            RVP_WireGuidanceSteering.applyFromDirection(projectile, desired, factor);
            return true;
        }
        // 调用本项目弹体数据访问器；配置 rvp_maxg 时先生成完整期望方向，再统一施加 G 钳制。
        Double rvpMaxGs = context.data().getProjectileData().getRvpMaxG();
        // 调用本项目攻角门控；攻角控制器需要原始期望方向，不能先做一次速度插值。
        float steeringFactor = rvpMaxGs != null || RVP_ProjectileMotion.usesAttackAngle(projectile) ? 1.0F : factor;
        Vec3 next;
        boolean pipAttackApplied = false;
        RVP_PredictiveInterceptMath.Solution intercept = null;
        RVP_PIPAttackAngleController.Result pipControl = null;
        String branch = "PURSUIT";
        if (isGpsCruiseActive(context, steeringTarget)) {
            branch = "GPS_CRUISE";
            next = steerGpsCruise(
                    current,
                    steeringTarget.subtract(projectile.position()),
                    speed,
                    steeringFactor,
                    context.active().cruiseLevelingFactor(),
                    projectile.consumeGpsCruiseVerticalResetPending()
            );
        } else if (!isTopAttack && entity != null && canUsePredictiveIntercept(context)) {
            Vec3 pipTarget = loftedTarget != null ? loftedTarget : target;
            RVP_TargetMotionState pipMotion = targetMotion.withPosition(pipTarget);
            // 调用纯 PIP 几何求解一次，保留正根/回退状态供控制器和诊断共用。
            intercept = RVP_PredictiveInterceptMath.solve(projectile.position(), current,
                    pipMotion.position(), pipMotion.velocity(), pipMotion.effectiveAcceleration());
            branch = intercept.usable() ? "PIP" : "PIP_FALLBACK_PURSUIT";
            // 调用既有方向插值；攻角开启时 factor=1，此处仅生成完整期望航迹。
            next = intercept.usable() ? blendDirection(current,
                    intercept.point().subtract(projectile.position()), speed, steeringFactor) : null;
            if (next == null || next.lengthSqr() <= 1.0E-8) {
                // 调用原纯追踪回退；不可解时不伪造有限的 PIP 相遇点。
                next = steerPursuit(
                        current, steeringTarget.subtract(projectile.position()), speed, steeringFactor);
            } else if (RVP_ProjectileMotion.usesAttackAngle(projectile)) {
                // 调用本项目配置与动力快照，冻结试算预算；不提前点火或写运动速度。
                RVP_AeroSteeringLimits limits = context.data().getProjectileData().resolveAeroSteeringLimits(
                        context.data().getProjectileVelocity(), projectile.getY(), factor);
                pipControl = RVP_PIPAttackAngleController.solve(current, projectile.getAttackAngleState(), next,
                        limits, RVP_ProjectileMotion.resolvePIPMotorState(projectile),
                        RVP_ProjectileMotion.resolveAttackAngleMass(projectile));
                if (pipControl.responseLimited()
                        && (pipMotion.velocity().lengthSqr() > 1.0E-8
                        || pipMotion.effectiveAcceleration().lengthSqr() > 1.0E-8)) {
                    double responseLead = RVP_PIPAttackAngleController.estimateResponseLeadTicks(
                            current, limits, RVP_ProjectileMotion.resolveAttackAngleMass(projectile));
                    if (responseLead > 0.0D && intercept.timeTicks() > responseLead) {
                        // 控制器实际受限时，把目标快照前移一个很短的执行器响应窗，再重算一次 PIP；
                        // 正常可达时不额外加提前量，避免破坏原恒速交会几何。
                        RVP_TargetMotionState responseMotion = new RVP_TargetMotionState(
                                pipMotion.predictPosition(responseLead),
                                pipMotion.predictVelocity(responseLead),
                                pipMotion.acceleration(), pipMotion.confidence()).sanitized();
                        RVP_PredictiveInterceptMath.Solution responseIntercept =
                                RVP_PredictiveInterceptMath.solve(projectile.position(), current,
                                        responseMotion.position(), responseMotion.velocity(),
                                        responseMotion.effectiveAcceleration());
                        if (responseIntercept.usable()) {
                            intercept = responseIntercept;
                            next = blendDirection(current,
                                    intercept.point().subtract(projectile.position()), speed, steeringFactor);
                            pipControl = RVP_PIPAttackAngleController.solve(current,
                                    projectile.getAttackAngleState(), next, limits,
                                    RVP_ProjectileMotion.resolvePIPMotorState(projectile),
                                    RVP_ProjectileMotion.resolveAttackAngleMass(projectile));
                            branch = "PIP_ATTACK_ANGLE_RESPONSE";
                        }
                    }
                }
                // 调用本项目单次结果写回，禁止出口再次积分攻角或再次施加旧速度 blend。
                next = RVP_ProjectileMotion.applyAttackAngleSolution(projectile, pipControl.steering());
                pipAttackApplied = true;
                branch = "PIP_ATTACK_ANGLE";
            }
        } else {
            next = steerPursuit(
                    current, steeringTarget.subtract(projectile.position()), speed, steeringFactor);
        }
        if (next == null || next.lengthSqr() <= 1.0E-8) {
            return false;
        }
        // 调用本项目实体气动转向收口；开关关闭时维持旧版 rvpMaxGs/turningFactor 行为。
        if (!pipAttackApplied) {
            next = applyResolvedAeroSteering(
                    projectile, context.data(), current, next, factor, rvpMaxGs);
        }
        projectile.setDeltaMovement(next);
        // 调用本项目姿态收口，开启攻角时保持独立机头、不覆盖刚提交的控制结果。
        RVP_ProjectileMotion.applyGuidanceFacing(projectile, next);
        // 调用本项目惰性诊断，记录真实执行分支与 PIP 暂停原因。
        noteGuidanceDecision(context, branch, isTopAttack ? "TOP_ATTACK"
                        : entity == null ? "NO_ENTITY_TARGET" : predictiveInterceptBlockReason(context),
                steeringTarget, intercept, pipControl);
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
            RVP_TargetMotionState targetMotion,
            RVP_PresetBallisticProfile preset
    ) {
        RVP_BaseBullet projectile = context.projectile();
        if (projectile == null || target == null) {
            return false;
        }
        projectile.rememberGuidancePos(target);
        projectile.setGuidanceTargetPos(target);

        Vec3 movingTarget = resolvePresetMovingTarget(
                projectile.position(), target, targetMotion, projectile.getMotionSpeedReference());

        if (!projectile.hasPresetProfileInitialized()) {
            Vec3 launch = projectile.getVirtualMidcourseLaunchPosition();
            Vec3 ascentPos = computePresetAscentPos(projectile.position(), movingTarget, launch, preset);
            Vec3 overheadPos = new Vec3(movingTarget.x, launch.y + preset.cruiseAltitude(), movingTarget.z);
            projectile.initializePresetProfile(launch, ascentPos, overheadPos);
        }
        Vec3 ascentPos = projectile.getPresetAscentPos();
        Vec3 overheadPos = projectile.getPresetOverheadPos();
        Vec3 launchPos = projectile.getPresetLaunchPos();
        if (ascentPos == null || overheadPos == null) {
            return false;
        }

        Vec3 current = projectile.getDeltaMovement();
        double speed = Math.max(projectile.getMotionSpeedReference(), current.length());
        if (speed <= 1.0E-6) {
            return false;
        }
        float factor = resolveTurningFactor(context);
        // 调用本项目弹体数据访问器；PRESET 实体链也遵守 rvp_maxg 高于 turning_factor。
        Double rvpMaxGs = context.data().getProjectileData().getRvpMaxG();
        // 调用本项目攻角门控，PRESET 同样避免预插值后再二次限转。
        float steeringFactor = rvpMaxGs != null || RVP_ProjectileMotion.usesAttackAngle(projectile) ? 1.0F : factor;
        // 调用本项目弹体数据解析器，使实体 PRESET 俯冲判据使用与最终转向相同的气动预算。
        RVP_AeroSteeringLimits presetLimits = context.data().getProjectileData()
                .resolveAeroSteeringLimits(
                        context.data().getProjectileVelocity(), projectile.getY(), factor);
        Vec3 next;
        if (shouldBeginPresetDive(
                projectile.position(), movingTarget, launchPos, current, preset, presetLimits)) {
            next = steerPresetTerminal(
                    current, projectile.position(), movingTarget, speed, steeringFactor);
        } else {
            // 弹道导弹抛物线制导：上升+中段合一。整条弹道是一条对称抛物线弧，
            // 最高点（apogee）位于弹道水平中段，爬升/下降角按射程自适应保持平缓，
            // 无陡直线爬升、尖顶与平飞段（弹道导弹形态而非巡航导弹形态）。
            next = steerPresetBallisticArc(
                    current, projectile.position(), movingTarget, launchPos, preset, speed, steeringFactor);
        }
        if (next == null || next.lengthSqr() <= 1.0E-8) {
            return false;
        }
        // 调用本项目实体气动转向收口，使 PRESET 与普通制导共享动压和载荷结算。
        next = applyResolvedAeroSteering(
                projectile, context.data(), current, next, factor, rvpMaxGs);
        projectile.setDeltaMovement(next);
        // 标记本 Tick 已写入 PRESET 制导速度，供冷发射运动层在重叠窗口内完成速度接管。
        projectile.markPresetGuidanceMotionAppliedThisTick();
        RVP_ProjectileMotion.applyGuidanceFacing(projectile, next);
        return true;
    }

    /**
     * 为 PRESET 路点加入有限的目标运动提前量。
     *
     * <p>路点只需要覆盖“导弹抵达当前水平距离”的时间窗；每 Tick 重新计算，
     * 因而不会把长时间恒加速度外推固化到整条弹道。目标没有实体历史时退化为固定点。</p>
     */
    private static Vec3 resolvePresetMovingTarget(
            Vec3 projectilePosition, Vec3 target, RVP_TargetMotionState motion, double speed) {
        if (projectilePosition == null || target == null || motion == null) {
            return target;
        }
        double dx = target.x - projectilePosition.x;
        double dz = target.z - projectilePosition.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double referenceSpeed = Math.max(1.0D, Double.isFinite(speed) ? speed : 1.0D);
        double leadTicks = Mth.clamp(horizontalDistance / referenceSpeed, 0.0D, 60.0D);
        Vec3 predicted = motion.predictPosition(leadTicks);
        return RVP_BallisticTrajectoryMath.isFinite(predicted) ? predicted : target;
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
            RVP_AeroSteeringLimits limits
    ) {
        if (projectilePos == null || target == null || preset == null) {
            return true;
        }
        double dx = projectilePos.x - target.x;
        double dz = projectilePos.z - target.z;
        double horizontalDistanceSqr = dx * dx + dz * dz;
        double verticalDistance = Math.max(0.0, projectilePos.y - target.y);
        double speed = velocity != null ? velocity.length() : 0.0;
        double turnRadius;
        if (limits != null && limits.enabled()) {
            // 调用本项目共享转弯半径估算，使实体 PRESET 俯冲点随动压减载同步前移。
            turnRadius = RVP_BallisticTrajectoryMath.resolveTurnRadius(speed, limits);
        } else {
            // 气动开关关闭时保留实体 PRESET 原有的 0.05 最小 factor 与 8～80 格钳制。
            double effectiveFactor = Mth.clamp(
                    limits == null ? 0.5F : limits.turningFactor(), 0.05F, 1.0F);
            turnRadius = Mth.clamp(speed / effectiveFactor, 8.0, 80.0);
        }
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
     * 弹道导弹抛物线制导（上升+中段合一）：追“目标方向前方 lookAhead + 抛物线高度”的点。
     *
     * <p>弹道为对称抛物线：高度 = {@code launch.y + max(8, apogee×4p(1-p))}，p 为水平进度
     * （0 发射点 → 1 目标），最高点（apogee）在弹道水平中段。apogee 自适应为
     * {@code min(配置巡航高度, 水平射程×0.50)}（2026-09-15 应用户要求从 0.35 上调——
     * 抬高弹道顶点，爬升/下降角上限约 45°；当前载具包内仅 9M723 使用 PRESET 弹道），
     * 仍保留射程比例上限，避免短射程下形成过陡尖弧。取代旧的“追 ascentPos 陡直线
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
        // 自适应弹道顶点：过高 apogee 在短射程下会形成陡尖弧；0.50 = 射程的 50% 上限（原 0.35，抬高弹道）
        double apogee = Math.min(preset.cruiseAltitude(), totalH * 0.50);
        // 抛物线高度（基线 base 起步，顶点 = apogee，末端回到 base）：全程单调平滑、无
        // 硬切换，竖直发射后追点自然略高于自身平滑转上爬，不会“压-拉-压”的蛇形振荡
        double base = Math.min(40.0, apogee * 0.3);
        // 前方 lookAhead：末端（hDist→0）自动收敛到目标，追点法弹道圆润
        double lookAhead = Math.min(preset.maxAscentLead(), hDist * 0.3);
        // 前视点的水平坐标已经向前推进，垂直坐标同步采样前视进度，
        // 避免 YJ-20 冷发射结束后追到“前方同高度”的水平点。（并行会话 20b76ac5：
        // 追点高度按前视点自身进度采样，修高度/水平采样错位）
        double lookAheadProgress = RVP_BallisticTrajectoryMath.resolvePresetLookAheadProgress(
                hDist, totalH, lookAhead);
        double targetY = launch == null ? position.y + 8.0
                : RVP_BallisticTrajectoryMath.samplePresetArcHeight(
                        launch.y, base, apogee, lookAheadProgress);
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

    /**
     * 攻顶瞄准点（无状态，恢复自 8c30656 算法）：目标正上方 min(|H|, 水平距离) 高度处，
     * 每 tick 基于弹体当前位置重算。
     *
     * <p>水平距离大于 H 时瞄准点恒在目标上空 H 处 → 导弹持续爬升；
     * 接近到水平距离小于 H 后瞄准点高度随距离线性收缩 → 自然圆滑地越顶俯冲，落角陡。
     * 负 H 经 {@code copySign} 支持低空上升逼近。无任何剖面状态，对移动目标天然自适应。</p>
     */
    static Vec3 resolveTopAttackAimPoint(Vec3 projectilePos, Vec3 target, Float topAttackHeight) {
        if (projectilePos == null || target == null || topAttackHeight == null
                || Math.abs(topAttackHeight) <= 1.0E-6f) {
            return target;
        }
        double dist = horizontalDistance(projectilePos, target);
        double height = Math.copySign(
                Math.min(Math.abs(topAttackHeight), dist), topAttackHeight);
        return target.add(0, height, 0);
    }

    private static double horizontalDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
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
     * PIP 几何解到速度候选的兼容入口；机头/攻角控制在实体 PIP 分支单独适配。
     * 无正根且未接近时返回 null，由调用方执行原有纯追踪回退。
     */
    public static Vec3 steerPredictiveIntercept(
            RVP_BaseBullet projectile, Vec3 missilePos, Vec3 missileVelocity,
            Vec3 targetPos, Vec3 targetVelocity, double speed, float turningFactor
    ) {
        if (!Double.isFinite(speed) || speed <= 1.0E-8) return null;
        // 调用本项目纯几何求解，统一实体、诊断及连续轨迹测试的相遇时间算法。
        var intercept = RVP_PredictiveInterceptMath.solve(missilePos, missileVelocity, targetPos, targetVelocity);
        if (!intercept.usable()) return null;
        // 调用既有方向插值，保留无攻角路径的转向比例契约。
        return blendDirection(missileVelocity, intercept.point().subtract(missilePos), speed, turningFactor);
    }

    private static Vec3 blendDirection(Vec3 current, Vec3 desired, double speed, float turningFactor) {
        // 调用本项目纯数学工具，让实体链与虚拟链共用完全相同的 turningFactor 算法。
        return RVP_TrajectorySteeringMath.applyTurningFactor(
                current, desired, speed, turningFactor);
    }

    private static float resolveTurningFactor(RVP_GuidanceRuntimeContext context) {
        Float configured = context.data().getProjectileData().resolveTurningFactor(context.projectile().getFlightTickCount());
        return configured != null ? configured : 0.5f;
    }

    /**
     * 把实体制导生成的候选速度交给统一气动求解器，并把载荷因子写回弹体。
     *
     * @param projectile 当前弹体
     * @param data 当前武器配置
     * @param current 转向前速度
     * @param desired 制导层生成的候选速度或方向
     * @param turningFactor 当前飞行 Tick 的方向插值强度
     * @param rvpMaxGs 已解析的可选显式最大 G 值
     * @return 最终写入弹体的速度
     */
    private static Vec3 applyResolvedAeroSteering(
            RVP_BaseBullet projectile,
            RVP_WeaponData data,
            Vec3 current,
            Vec3 desired,
            float turningFactor,
            Double rvpMaxGs
    ) {
        // 调用本项目弹体数据解析器，冻结参考速度、密度、诱导阻力和转角上限。
        RVP_AeroSteeringLimits limits = data.getProjectileData().resolveAeroSteeringLimits(
                data.getProjectileVelocity(), projectile.getY(), turningFactor);
        if (projectile.isMissile() && limits.attackAngleEnabled()) {
            // 调用本项目实体攻角适配器，写回弹轴与载荷而不再强制弹轴贴合速度。
            return RVP_ProjectileMotion.applyAttackAngleSteering(projectile, current, desired, limits);
        }
        if (!limits.enabled()) {
            if (rvpMaxGs == null) {
                return desired;
            }
            // 调用本项目旧版 G 值转向，保证阶段 S2 默认关闭时实体行为逐位不变。
            return RVP_BallisticTrajectoryMath.applySteering(current, desired, rvpMaxGs);
        }
        // 调用本项目统一气动求解器，按当前动压裁决实际转角并计算 λ。
        RVP_AeroSteeringSolution solution = RVP_BallisticTrajectoryMath.applyAeroSteering(
                current, desired, limits);
        // 调用本项目弹体载荷记录器，供同 Tick 运动阶段在速度钳制后结算诱导阻力。
        projectile.recordAeroLoadFactor(solution.loadFactor());
        return solution.velocity();
    }

    /**
     * 判断当前制导阶段是否允许复用预测拦截点（PIP）转向。
     *
     * <p>公开此门控供SACLOS STABLE辅助在生成实体意图前复核，确保尚未到启用Tick、
     * 诱饵干扰期或二脉冲等待期仍走原SACLOS视线制导，而不是退化成实体纯追踪。</p>
     */
    public static boolean canUsePredictiveIntercept(RVP_GuidanceRuntimeContext context) {
        // 调用统一原因解析，使 SACLOS STABLE 门控与实体诊断不会出现相反结论。
        return "NONE".equals(predictiveInterceptBlockReason(context));
    }

    /** 解析既有 PIP 门控原因；只读状态，不改变二脉冲或干扰时序。 */
    private static String predictiveInterceptBlockReason(RVP_GuidanceRuntimeContext context) {
        if (context == null || context.active() == null || !context.active().predictTargetPos()) return "PREDICTION_DISABLED";
        RVP_BaseBullet projectile = context.projectile();
        // 调用本项目飞行时钟与干扰状态出口，保持原先起始 Tick 和诱饵期暂停语义。
        if (projectile == null || projectile.getFlightTickCount() < context.active().predictTargetPosStartTick()) {
            return "START_TICK";
        }
        if (projectile.isJammedByDecoy()) return "DECOY";
        // 调用既有二脉冲等待判据，只报告原因，不触发点火。
        return isWaitingForSecondPulse(projectile) ? "SECOND_PULSE_WAIT" : "NONE";
    }

    /** 复用生命周期日志记录真实分支，关闭诊断时不格式化向量或执行额外试算。 */
    private static void noteGuidanceDecision(RVP_GuidanceRuntimeContext context, String branch,
            String reason, Vec3 target, RVP_PredictiveInterceptMath.Solution intercept,
            RVP_PIPAttackAngleController.Result control) {
        // 调用本项目诊断开关与事件入口；详情仅在被追踪时构造，服务端无需客户端类型。
        if (!RVP_ProjectileLifecycleDebug.isEnabled()) return;
        RVP_BaseBullet projectile = context.projectile();
        RVP_ProjectileLifecycleDebug.noteEvent(projectile, RVP_ProjectileLifecycleDebug.Event.GUIDANCE,
                () -> "branch=" + branch + " reason=" + reason
                        + " source=" + context.active().guidanceType()
                        + " targetDistance=" + RVP_ProjectileLifecycleDebug.decimal(projectile.position().distanceTo(target))
                        + " guidanceMaxAngle=" + context.active().maxGuidanceAngle()
                        + " pipStatus=" + (intercept == null ? "NOT_EVALUATED" : intercept.status())
                        + " pipTimeTicks=" + (intercept == null ? "<null>" : intercept.timeTicks())
                        + " pipPoint=" + (intercept == null || intercept.point() == null ? "<null>"
                            : RVP_ProjectileLifecycleDebug.formatVec(intercept.point()))
                        + (control == null ? "" : " bodyCommand=" + RVP_ProjectileLifecycleDebug.formatVec(control.bodyCommand())
                            + " requestedVelocity=" + RVP_ProjectileLifecycleDebug.formatVec(control.requestedVelocity())
                            + " predictedVelocity=" + RVP_ProjectileLifecycleDebug.formatVec(control.predictedVelocity())
                            + " requestedTurnDeg=" + Math.toDegrees(control.requestedTurnRadians())
                            + " aeroTurnDeg=" + Math.toDegrees(control.steering().turnAngleRadians())
                            + " attackAngleDeg=" + Math.toDegrees(control.steering().attackAngleRadians())
                            + " responseLimited=" + control.responseLimited()
                            + " aeroLoadFactor=" + control.steering().loadFactor()));
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
