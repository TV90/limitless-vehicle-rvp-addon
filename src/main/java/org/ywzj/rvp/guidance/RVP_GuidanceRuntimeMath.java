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
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_ProportionalNavigationMath;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_TargetMotionState;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_PointGuidanceController;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** New-schema steering math. It never reads legacy steering_data. */
public final class RVP_GuidanceRuntimeMath {

    /** 复用既有 HITL 输入诊断，不保存任何制导共享状态。 */
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
        // 下方实体 PPN、保留的 PIP 与点制导消费同一真实 target。
        // [RVP] §49（2026-11-01 用户定版）：红外族（IR/AIR）改锁点——舰船仍取最大 OBB 中心，
        // 其余目标在本弹锁定种子下固定抽一块 OBB，弹着点在车体/炮塔等部位之间分散
        Vec3 target = entity != null && entity.isAlive()
                ? resolveAimPoint(projectile, entity, intent.sourceType())
                : intent.aimPoint();
        if (entity != null && entity.isAlive() && target != null) {
            projectile.rememberGuidancePos(target);
        }
        if (target == null) {
            return false;
        }
        // 调用本项目弹体的目标运动采样器；PPN/PIP/PRESET 共用同一份速度与短时加速度历史，
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
        // 主动雷达已进入搜索阶段但没有实体意图时，飞行控制继续使用 GPS 弹道；
        // 只有真正获得实体意图后才交接到末段跟踪，不因一次扫描失败放弃坐标纠偏。
        if (usesPresetPointFallback(context.data().getGuidanceData(), context.active(), entity != null)) {
            return applyPresetBallistic(context, target, targetMotion,
                    RVP_PresetBallisticProfile.of(context.data().getGuidanceData()));
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
        // 空空高抛（loft）：沿用原辅助点与退坡曲线，实体寻的在同一权重下由 PIP 路线衔接至 PPN。
        // 瞄准点 = 目标实时位置 + (0, loft_height_above_target, 0)（相对目标高度差，动对动
        // 跟随目标升降），水平距离进入 [end, end+blend] 区间按 smoothstep 平滑退坡、低于
        // end 归零；PPN 始终观测真实目标，不把辅助高度变化当成目标自身速度。
        // 近距（<end）blend 恒 0 等效无 loft；ARH/AIR 截获后不施加（自主导引头俯冲追击
        // 不受干扰）；与 top_attack_height 互斥建议同时只配一个，同配时 loft 优先。
        Vec3 loftedTarget = null;
        double loftWeight = 0.0;
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
            loftWeight = blend;
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
        boolean attackApplied = false;
        RVP_PredictiveInterceptMath.Solution intercept = null;
        RVP_PIPAttackAngleController.Result pipControl = null;
        RVP_ProportionalNavigationMath.Solution ppn = null;
        double leadWeight = 0.0;
        String branch = "PURSUIT";
        // 调用严格资格入口：只替换有实体观测的 IR/AIR/ARH/SARH 导弹，其他路径继续原分派。
        if (!isTopAttack && usesEntityPPN(context, intent)) {
            // 调用无状态 PPN，只使用实际速度和真实目标；不外推相遇点，也不读取目标加速度。
            ppn = RVP_ProportionalNavigationMath.solve(projectile.position(), current,
                    targetMotion.position(), targetMotion.velocity(), context.active().predictTargetPosGain());
            next = ppn.requestedVelocity();
            boolean attackAngle = RVP_ProjectileMotion.usesAttackAngle(projectile);
            RVP_AeroSteeringLimits limits = context.data().getProjectileData().resolveAeroSteeringLimits(
                    context.data().getProjectileVelocity(), projectile.getY(), factor);
            if (!limits.enabled() && rvpMaxGs == null) {
                // 旧式执行器的 factor 只作用于 PPN 请求一次；PIP 路线已自行应用，不能混合后再乘一次。
                next = blendDirection(current, next, current.length(), factor);
            }
            branch = "PPN";
            // 前置前馈：求旧 PIP 分支对同一 steeringTarget 生成的单 Tick 请求，再按剩余相遇时间退坡到纯 PPN。
            // 目标取 steeringTarget（高抛时为辅助点），与旧 PIP/高抛路线同源，避免边界跳变。
            RVP_TargetMotionState leadMotion = targetMotion.withPosition(steeringTarget);
            intercept = RVP_PredictiveInterceptMath.solve(projectile.position(), current,
                    leadMotion.position(), leadMotion.velocity(), leadMotion.effectiveAcceleration());
            leadWeight = intercept.usable() && ppn.status() == RVP_ProportionalNavigationMath.Status.ACTIVE
                    ? resolveLeadWeight(intercept.timeTicks(),
                        context.data().getGuidanceData().getPpnLeadFeedforwardTicks())
                    : 0.0;
            Vec3 pipRequest = null;
            if (intercept.usable() && (leadWeight > 0.0 || loftWeight > 0.0)) {
                // 调用旧 PIP 分支同一方向插值；攻角路径再走同一单步反解，保持原受限响应补偿。
                Vec3 pipDirection = blendDirection(current,
                        intercept.point().subtract(projectile.position()), speed, steeringFactor);
                if (attackAngle) {
                    var pip = solvePIPControl(context, current, pipDirection, leadMotion, intercept, limits);
                    intercept = pip.intercept();
                    pipControl = pip.control();
                    pipRequest = pipControl.requestedVelocity();
                } else {
                    pipRequest = pipDirection;
                }
            }
            if (leadWeight > 0.0 && pipRequest != null) {
                // 调用球面请求衔接，只混合指令，不提前执行姿态；末段交给纯 PPN。
                next = RVP_ProportionalNavigationMath.blendDirectionRequest(current, next, pipRequest, leadWeight);
            }
            if (loftWeight > 0.0) {
                Vec3 routeRequest = pipRequest != null ? pipRequest
                        : steerPursuit(current, steeringTarget.subtract(projectile.position()), speed, steeringFactor);
                // 调用球面请求衔接；高抛权重仍按原曲线退坡，前馈只塑形其中的 PPN 分量。
                next = RVP_ProportionalNavigationMath.blendDirectionRequest(current, next, routeRequest, loftWeight);
                branch = loftWeight >= 1.0 ? "LOFT_PIP" : "LOFT_PIP_PPN_BLEND";
            }
            if (attackAngle) {
                // 纯 PIP/纯高抛请求可以直接复用上一段已算好的姿态；混合请求需要重新反解一次。
                boolean reusePipControl = pipControl != null
                        && (loftWeight >= 1.0 || (loftWeight <= 0.0 && leadWeight >= 1.0));
                if (!reusePipControl) {
                    // 调用已生成航迹请求入口，禁止再乘一次 turning_factor 或叠加一次 PIP 提前量。
                    pipControl = RVP_PIPAttackAngleController.solveVelocityRequest(current,
                            projectile.getAttackAngleState(), next, limits,
                            RVP_ProjectileMotion.resolvePIPMotorState(projectile),
                            RVP_ProjectileMotion.resolveAttackAngleMass(projectile));
                }
                // 调用单次提交入口；推力、重力与诱导阻力仍由原运动阶段结算。
                next = RVP_ProjectileMotion.applyAttackAngleSolution(projectile, pipControl.steering());
                attackApplied = true;
                branch += "_ATTACK_ANGLE";
            }
        } else if (isGpsCruiseActive(context, steeringTarget)) {
            branch = "GPS_CRUISE";
            next = steerGpsCruise(
                    current,
                    steeringTarget.subtract(projectile.position()),
                    speed,
                    steeringFactor,
                    context.active().cruiseLevelingFactor(),
                    projectile.consumeGpsCruiseVerticalResetPending()
            );
        } else if (!isTopAttack && RVP_ProjectileMotion.usesAttackAngle(projectile)
                && context.data().getGuidanceData().getGuidanceType() == RVP_EnumGuidanceType.GPS
                && targetMotion.velocity().lengthSqr() <= 1.0E-8
                && targetMotion.effectiveAcceleration().lengthSqr() <= 1.0E-8) {
            // 调用固定点控制器；静止实体与相同坐标共享收敛规则，不再取决于是否存在 Entity。
            RVP_AeroSteeringLimits limits = context.data().getProjectileData().resolveAeroSteeringLimits(
                    context.data().getProjectileVelocity(), projectile.getY(), factor);
            var solution = RVP_PointGuidanceController.solve(projectile.position(), current, steeringTarget,
                    projectile.getAttackAngleState(), limits, RVP_ProjectileMotion.resolvePIPMotorState(projectile),
                    RVP_ProjectileMotion.resolveAttackAngleMass(projectile));
            next = RVP_ProjectileMotion.applyAttackAngleSolution(projectile, solution);
            attackApplied = true;
            branch = "GPS_POINT_INTERCEPT";
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
                // 调用原 PIP 单步反解，其他制导路径保持原响应补偿和参数语义。
                var pip = solvePIPControl(context, current, next, pipMotion, intercept, limits);
                intercept = pip.intercept();
                pipControl = pip.control();
                // 调用本项目单次结果写回，禁止出口再次积分攻角或再次施加旧速度 blend。
                next = RVP_ProjectileMotion.applyAttackAngleSolution(projectile, pipControl.steering());
                attackApplied = true;
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
        if (!attackApplied) {
            if (RVP_ProjectileMotion.isPreIgnitionAttitudeLocked(projectile)) {
                // 冷发射点火前允许继续计算目标/PIP 状态，但不把任何制导结果写入速度方向。
                next = current;
            } else {
                next = applyResolvedAeroSteering(
                        projectile, context.data(), current, next, factor, rvpMaxGs);
            }
        }
        projectile.setDeltaMovement(next);
        // 调用本项目姿态收口，开启攻角时保持独立机头、不覆盖刚提交的控制结果。
        RVP_ProjectileMotion.applyGuidanceFacing(projectile, next);
        // 调用本项目惰性诊断，记录真实执行分支与 PIP 暂停原因。
        noteGuidanceDecision(context, branch, isTopAttack ? "TOP_ATTACK"
                        : entity == null ? "NO_ENTITY_TARGET" : predictiveInterceptBlockReason(context),
                steeringTarget, intercept, pipControl, ppn, loftWeight, leadWeight);
        return true;
    }

    /** 只接受本轮授权的实体寻的类型；坐标、人工指令、电视和 SACLOS 辅助不接入 PPN。 */
    private static boolean isEntityPPNSource(RVP_EnumGuidanceType type) {
        return type == RVP_EnumGuidanceType.IR || type == RVP_EnumGuidanceType.AIR
                || type == RVP_EnumGuidanceType.ARH || type == RVP_EnumGuidanceType.SARH;
    }

    /** 复用原预测开关、总飞行时间、诱饵和二脉冲等待门控，不新增配置或武器 ID 特判。 */
    private static boolean usesEntityPPN(RVP_GuidanceRuntimeContext context, RVP_GuidanceIntent intent) {
        // 调用原资格判定，只改变被授权分支内部的制导律，不改变捕获与反制时序。
        return context.projectile().isMissile() && intent.aimEntity() != null && intent.aimEntity().isAlive()
                && !intent.directMotion() && isEntityPPNSource(context.active().guidanceType())
                && intent.sourceType() == context.active().guidanceType() && canUsePredictiveIntercept(context);
    }

    /**
     * 前置前馈的退坡权重：剩余相遇时间大于 fullTicks 时保持 1，降到 fullTicks/3 时归零。
     *
     * @param timeTicks 相遇解剩余时间，单位 Tick
     * @param fullTicks 前馈保持全额的剩余时间，单位 Tick；0 表示关闭
     * @return 0～1 的 smoothstep 权重
     */
    private static double resolveLeadWeight(double timeTicks, float fullTicks) {
        if (!Double.isFinite(timeTicks) || !Float.isFinite(fullTicks) || fullTicks <= 0f) {
            return 0.0;
        }
        double full = fullTicks;
        double near = full / 3.0;
        double normalized = Mth.clamp((timeTicks - near) / (full - near), 0.0, 1.0);
        return normalized * normalized * (3.0 - 2.0 * normalized);
    }

    /**
     * @param intercept 保留原预测点及受限响应补偿后的几何诊断
     * @param control 尚未提交实体状态的原 PIP 机头控制结果
     */
    private record PIPControl(RVP_PredictiveInterceptMath.Solution intercept,
                              RVP_PIPAttackAngleController.Result control) {}

    /** 原 PIP 与高抛路线共用原有反解，只试算，不消耗燃料或推进第二次姿态。 */
    private static PIPControl solvePIPControl(RVP_GuidanceRuntimeContext context, Vec3 current, Vec3 desired,
            RVP_TargetMotionState motion, RVP_PredictiveInterceptMath.Solution intercept,
            RVP_AeroSteeringLimits limits) {
        RVP_BaseBullet projectile = context.projectile();
        // 调用原姿态反解器，冻结与正式运动相同的动力状态和质量。
        var motor = RVP_ProjectileMotion.resolvePIPMotorState(projectile);
        double mass = RVP_ProjectileMotion.resolveAttackAngleMass(projectile);
        var control = RVP_PIPAttackAngleController.solve(current, projectile.getAttackAngleState(),
                desired, limits, motor, mass);
        if (control.responseLimited()
                && (motion.velocity().lengthSqr() > 1.0E-8 || motion.effectiveAcceleration().lengthSqr() > 1.0E-8)) {
            // 调用原有限响应窗估计，其他 PIP 路径不改变补偿条件或未来目标外推规则。
            double lead = RVP_PIPAttackAngleController.estimateResponseLeadTicks(current, limits, mass);
            if (lead > 0.0 && intercept.timeTicks() > lead) {
                var response = new RVP_TargetMotionState(motion.predictPosition(lead), motion.predictVelocity(lead),
                        motion.acceleration(), motion.confidence()).sanitized();
                // 调用原 PIP 求解器；PPN 末段不经过这个方法。
                var adjusted = RVP_PredictiveInterceptMath.solve(projectile.position(), current,
                        response.position(), response.velocity(), response.effectiveAcceleration());
                if (adjusted.usable()) {
                    intercept = adjusted;
                    // 调用原方向插值；攻角分支这里的 factor 恒为 1，仅生成完整方向。
                    Vec3 adjustedDirection = blendDirection(current, adjusted.point().subtract(projectile.position()),
                            Math.max(projectile.getMotionSpeedReference(), current.length()), 1.0F);
                    control = RVP_PIPAttackAngleController.solve(current, projectile.getAttackAngleState(),
                            adjustedDirection, limits, motor, mass);
                }
            }
        }
        return new PIPControl(intercept, control);
    }

    /**
     * 实体意图的制导瞄准点。
     *
     * <p>§49（2026-11-01 用户定版）：只有红外族（IR/AIR）在舰船阈值以下改锁随机 OBB；
     * 其它制导类型与两族的大目标一律保持"最大 OBB 中心"，行为零变化。</p>
     */
    private static Vec3 resolveAimPoint(
            RVP_BaseBullet projectile,
            Entity entity,
            RVP_EnumGuidanceType sourceType
    ) {
        if (sourceType == RVP_EnumGuidanceType.HITL_TV && projectile instanceof org.ywzj.rvp.entity.projectile.RVP_MissileEntity missile) {
            // 调用 TV 独立骨锁点，实体运动/PIP 仍消费同一目标实体和实际选定点。
            return missile.rvp$resolveHitlAimPoint(entity);
        }
        if (sourceType == RVP_EnumGuidanceType.IR || sourceType == RVP_EnumGuidanceType.AIR) {
            return org.ywzj.rvp.util.RVP_AimPointResolver.resolveIrLockPoint(entity,
                    org.ywzj.rvp.util.RVP_AimPointResolver.lockSeed(projectile.getId(), entity),
                    org.ywzj.rvp.util.RVP_AimPointResolver.resolveLargestObbCenter(entity));
        }
        return org.ywzj.rvp.util.RVP_AimPointResolver.resolveLargestObbCenter(entity);
    }

    /** 主段为预设 GPS、末段允许记忆制导且尚无实体意图时，保留原坐标飞行控制。 */
    static boolean usesPresetPointFallback(org.ywzj.rvp.weapon.data.RVP_GuidanceData main,
            RVP_GuidanceActiveConfig active, boolean hasEntityIntent) {
        return !hasEntityIntent && active.phase() == RVP_GuidancePhase.TERMINAL
                && active.enableInertialGuidance()
                && (active.guidanceType() == RVP_EnumGuidanceType.ARH
                    || active.guidanceType() == RVP_EnumGuidanceType.AIR)
                && main.getGuidanceType() == RVP_EnumGuidanceType.GPS
                && RVP_PresetBallisticProfile.of(main).active();
    }

    /**
     * PRESET 全程制导：点火后按共享曲线生成中段追点，预留转向距离后进入固定点俯冲。
     * <p>攻角模式把期望速度反算成机头指令，每刻仅执行一次姿态与气动力求解。</p>
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
            // 调用本项目运动时序解析入口，取得发动机实际开始工作的飞行 Tick；
            // PRESET 参考点必须等到这个 Tick 的实体当前位置确定后再冻结。
            int ignitionTick = RVP_ProjectileMotion.resolveMotorIgnitionTick(
                    projectile, context.data());
            if (projectile.getFlightTickCount() < ignitionTick) {
                // 点火前只保留冷发射/延迟点火运动，不能提前用生成点冻结整条 PRESET 弧线。
                return true;
            }
            // 使用当前服务端权威位置作为实际点火点，而不是弹体生成位置。
            Vec3 launch = projectile.position();
            Vec3 ascentPos = computePresetAscentPos(projectile.position(), movingTarget, launch, preset);
            Vec3 overheadPos = new Vec3(movingTarget.x, launch.y + preset.cruiseAltitude(), movingTarget.z);
            // 调用本项目弹体状态入口，保存以实际点火点为原点的 PRESET 参考轨迹。
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
        boolean diving = shouldBeginPresetDive(
                projectile.position(), movingTarget, launchPos, current, preset, presetLimits);
        Vec3 next;
        if (diving) {
            next = steerPresetTerminal(
                    current, projectile.position(), movingTarget, speed, steeringFactor);
        } else {
            // 弹道导弹抛物线制导：上升+中段合一。整条弹道是一条对称抛物线弧，
            // 最高点（apogee）位于弹道水平中段，爬升/下降角按射程自适应保持平缓，
            // 无陡直线爬升、尖顶与平飞段（弹道导弹形态而非巡航导弹形态）。
            // 调用共享中段追点，以实际速度/气动响应扩展前视窗口。
            Vec3 desired = RVP_BallisticTrajectoryMath.resolvePresetArcDirection(
                    projectile.position(), current, movingTarget, preset.at(launchPos), presetLimits);
            next = steerPursuit(current, desired, speed, steeringFactor);
        }
        if (next == null || next.lengthSqr() <= 1.0E-8) {
            return false;
        }
        // 调用本项目实体气动转向收口，使 PRESET 与普通制导共享动压和载荷结算。
        if (RVP_ProjectileMotion.isPreIgnitionAttitudeLocked(projectile)) {
            // 冷发射点火前保留冷发射速度轴，PRESET 路点只用于后续点火后的制导接管。
            next = current;
        } else if (RVP_ProjectileMotion.usesAttackAngle(projectile)) {
            // 调用共同机头反解：中段沿抛物线追点，俯冲按剩余交会时间消除横向速度。
            var motor = RVP_ProjectileMotion.resolvePIPMotorState(projectile);
            double mass = RVP_ProjectileMotion.resolveAttackAngleMass(projectile);
            var solution = diving ? RVP_PointGuidanceController.solve(projectile.position(), current,
                    movingTarget, projectile.getAttackAngleState(), presetLimits, motor, mass)
                    : RVP_PIPAttackAngleController.solve(current, projectile.getAttackAngleState(), next,
                            presetLimits, motor, mass).steering();
            next = RVP_ProjectileMotion.applyAttackAngleSolution(projectile, solution);
        } else {
            next = applyResolvedAeroSteering(
                    projectile, context.data(), current, next, factor, rvpMaxGs);
        }
        projectile.setDeltaMovement(next);
        // 标记本 Tick 已写入 PRESET 制导速度，供冷发射运动层在重叠窗口内完成速度接管。
        if (!RVP_ProjectileMotion.isPreIgnitionAttitudeLocked(projectile)) {
            // 点火前没有真正写入 PRESET 速度，不能设置接管标记以免运动层保留错误方向。
            projectile.markPresetGuidanceMotionAppliedThisTick();
        }
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

    /** 末段方向委托共用三维判据，高空水平过顶继续修正，不锁死朝外漂移。 */
    static Vec3 steerPresetTerminal(Vec3 current, Vec3 position, Vec3 target,
            double speed, float turningFactor) {
        if (target == null || speed <= 1.0E-8) return current;
        // 调用实体/虚拟共用方向生成器；这里只生成请求，不提前结算气动能力。
        Vec3 desired = RVP_BallisticTrajectoryMath.resolvePresetTerminalDirection(position, current, target);
        return blendDirection(current, desired, speed, turningFactor);
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
        if (projectilePos == null || target == null || preset == null) return true;
        // 调用统一俯冲门，消除实体与虚拟路径的提前量差异。
        return RVP_BallisticTrajectoryMath.shouldBeginPresetDive(projectilePos,
                velocity == null ? Vec3.ZERO : velocity, target, preset.at(launch), limits);
    }

    /**
     * 不带攻角响应参数的兼容入口：委托共享曲线生成器沿起终点高度连线叠加中段抬升。
     * <p>真实运行入口直接传入气动限制，以便按速度和响应时间调整前视及短射程抬升。</p>
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
        if (target == null || preset == null) return current;
        // 调用统一中段方向生成器；无攻角便利入口保持旧前视参数，正式实体传实际气动限制。
        Vec3 desired = RVP_BallisticTrajectoryMath.resolvePresetArcDirection(
                position, current, target, preset.at(launch), null);
        return steerPursuit(current, desired, speed, turningFactor);
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
     * 判断当前阶段是否允许自动实体拦截；实体寻的用 PPN，保留路径继续使用 PIP。
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
        // 调用统一诊断出口，原有拒绝分支不伪造 PPN 观测。
        noteGuidanceDecision(context, branch, reason, target, intercept, control, null, 0.0, 0.0);
    }

    /** PPN、前置前馈与高抛衔接的诊断扩展；关闭诊断时不格式化原始观测或执行额外试算。 */
    private static void noteGuidanceDecision(RVP_GuidanceRuntimeContext context, String branch,
            String reason, Vec3 target, RVP_PredictiveInterceptMath.Solution intercept,
            RVP_PIPAttackAngleController.Result control, RVP_ProportionalNavigationMath.Solution ppn,
            double loftWeight, double leadWeight) {
        // 调用本项目诊断开关与事件入口；详情仅在被追踪时构造，服务端无需客户端类型。
        if (!RVP_ProjectileLifecycleDebug.isEnabled()) return;
        RVP_BaseBullet projectile = context.projectile();
        RVP_ProjectileLifecycleDebug.noteEvent(projectile, RVP_ProjectileLifecycleDebug.Event.GUIDANCE,
                () -> "branch=" + branch + " reason=" + reason
                        + " source=" + context.active().guidanceType()
                        + " targetDistance=" + RVP_ProjectileLifecycleDebug.decimal(projectile.position().distanceTo(target))
                        + " guidanceMaxAngle=" + context.active().maxGuidanceAngle()
                        + (ppn == null ? "" : " ppnStatus=" + ppn.status()
                            + " ppnGain=" + context.active().predictTargetPosGain()
                            + " losRate=" + RVP_ProjectileLifecycleDebug.formatVec(ppn.lineOfSightRate())
                            + " closingSpeed=" + ppn.closingSpeed()
                            + " requestedNormalAcceleration=" + RVP_ProjectileLifecycleDebug.formatVec(ppn.acceleration())
                            + " ppnRequestedVelocity=" + RVP_ProjectileLifecycleDebug.formatVec(ppn.requestedVelocity())
                            + " loftRouteWeight=" + loftWeight
                            + " leadBlendWeight=" + leadWeight)
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
