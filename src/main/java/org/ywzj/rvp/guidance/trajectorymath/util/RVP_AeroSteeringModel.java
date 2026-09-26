package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * 实体态与虚拟态可共用的无状态气动转向数学模型。
 *
 * <p>制导层只需提供期望方向；本类根据设计过载、归一化动压和绝对转角上限裁决
 * 实际方向变化，并返回供运动阶段结算的诱导阻力损失。所有方法均不访问世界或实体，
 * 也不保存跨 Tick 状态。</p>
 */
public final class RVP_AeroSteeringModel {
    /** 归一化动压下限，保证极低速发射阶段仍保留微弱舵效。 */
    public static final double MIN_DYNAMIC_PRESSURE_FACTOR = 0.05;
    /** 判断速度是否足以形成有效方向的数值阈值。 */
    private static final double MIN_SPEED = 1.0E-8;
    /** 判断输入方向是否为有效非零向量的平方长度阈值。 */
    private static final double MIN_DIRECTION_LENGTH_SQUARED = 1.0E-12;
    /** 判断转角是否需要实际求解的数值阈值。 */
    private static final double MIN_TURN_ANGLE = 1.0E-12;
    /** 计算载荷因子时用于避免可用转角除零的阈值。 */
    private static final double MIN_AVAILABLE_TURN_ANGLE = 1.0E-8;

    /** 工具类不允许实例化。 */
    private RVP_AeroSteeringModel() {
    }

    /**
     * 计算归一化动压因子 {@code densityFactor * (speed / referenceSpeed)^2}。
     *
     * <p>结果只允许在设计动压以下减载，因此上限为 1；下限固定为
     * {@link #MIN_DYNAMIC_PRESSURE_FACTOR}。参考速度为非正有限值时表示显式关闭动压
     * 减载并返回 1；任一参与计算的量为 NaN/Infinity 时保守返回下限。</p>
     *
     * @param speed 当前速率，单位格/Tick
     * @param limits 当前 Tick 的气动转向限制快照
     * @return 0.05～1.0 的归一化动压因子
     */
    public static double resolveDynamicPressureFactor(double speed,
                                                       RVP_AeroSteeringLimits limits) {
        if (limits == null || !Double.isFinite(speed) || speed < 0.0
                || !Double.isFinite(limits.referenceSpeed())
                || !Double.isFinite(limits.densityFactor())) {
            return MIN_DYNAMIC_PRESSURE_FACTOR;
        }
        if (limits.referenceSpeed() <= MIN_SPEED) {
            return 1.0;
        }
        double ratio = speed / limits.referenceSpeed();
        double dynamicPressureFactor = limits.densityFactor() * ratio * ratio;
        if (!Double.isFinite(dynamicPressureFactor)) {
            return MIN_DYNAMIC_PRESSURE_FACTOR;
        }
        return Mth.clamp(dynamicPressureFactor, MIN_DYNAMIC_PRESSURE_FACTOR, 1.0);
    }

    /**
     * 把旧版 {@code turning_factor} 折算为设计动压点的等效法向过载。
     *
     * <p>折算使用旧版插值在 90 度离轴指令下形成的单 Tick 转角。factor 大于等于 1
     * 保留“无过载限制、瞬时转向”语义并返回正无穷；factor 非正或参考速度无效时返回 0。</p>
     *
     * @param turningFactor 旧版单 Tick 方向插值强度
     * @param referenceSpeed 设计动压参考速度，单位格/Tick
     * @return 等效设计过载，单位 G；无过载限制时为正无穷
     */
    public static double equivalentGsFromTurningFactor(float turningFactor,
                                                        double referenceSpeed) {
        if (!Float.isFinite(turningFactor) || !Double.isFinite(referenceSpeed)
                || referenceSpeed <= MIN_SPEED) {
            return 0.0;
        }
        float factor = Mth.clamp(turningFactor, 0.0F, 1.0F);
        if (factor >= 1.0F) {
            return Double.POSITIVE_INFINITY;
        }
        if (factor <= 0.0F) {
            return 0.0;
        }
        double designTurnAngle = Math.atan2(factor, 1.0 - factor);
        double chordLength = 2.0 * referenceSpeed * Math.sin(designTurnAngle * 0.5);
        return chordLength / PhysicsEngine.G;
    }

    /**
     * 计算当前动压下可用的法向过载。
     *
     * <p>显式 rvpMaxGs 优先；未提供时调用本项目的 turningFactor 折算方法取得设计
     * 过载，再乘归一化动压。负值与非有限显式配置按 0 G 处理，正无穷仅由
     * turningFactor 的瞬转豁免产生。</p>
     *
     * @param speed 当前速率，单位格/Tick
     * @param limits 当前 Tick 的气动转向限制快照
     * @return 当前可用法向过载，单位 G
     */
    public static double availableGs(double speed, RVP_AeroSteeringLimits limits) {
        if (limits == null) {
            return 0.0;
        }
        Double configuredGs = limits.rvpMaxGs();
        double designGs;
        if (configuredGs != null) {
            designGs = Double.isFinite(configuredGs) && configuredGs > 0.0
                    ? configuredGs
                    : 0.0;
        } else {
            // 调用本项目等效过载折算，统一 turningFactor 与显式 G 值的气动预算口径。
            designGs = equivalentGsFromTurningFactor(
                    limits.turningFactor(), limits.referenceSpeed());
        }
        if (Double.isInfinite(designGs)) {
            return Double.POSITIVE_INFINITY;
        }
        // 调用本项目动压解析，使设计过载只能随低动压减载、不能超过配置上限。
        return designGs * resolveDynamicPressureFactor(speed, limits);
    }

    /**
     * 按诱导阻力系数与载荷因子平方计算本 Tick 的速率损失。
     *
     * @param inducedDrag 诱导阻力系数，无量纲
     * @param loadFactor 转角使用率 λ；计算前钳制到 0～1
     * @param speed 当前速率，单位格/Tick
     * @return 待运动阶段扣除的速率，单位格/Tick；输入无效或非正时返回 0
     */
    public static double inducedDragLoss(double inducedDrag, double loadFactor, double speed) {
        if (!Double.isFinite(inducedDrag) || !Double.isFinite(loadFactor)
                || !Double.isFinite(speed) || inducedDrag <= 0.0 || speed <= 0.0) {
            return 0.0;
        }
        double clampedLoadFactor = Mth.clamp(loadFactor, 0.0, 1.0);
        double loss = inducedDrag * clampedLoadFactor * clampedLoadFactor * speed;
        return Double.isFinite(loss) ? loss : 0.0;
    }

    /**
     * 求解单 Tick 气动转向，并把诱导阻力作为独立损失返回。
     *
     * <p>有效输入下输出速度严格保持输入速率。关闭气动模型时调用项目现有转向方法，
     * 保证旧版 rvpMaxGs 优先规则与 turningFactor 插值逐位保留；开启后则按可用 G 值和
     * 可选绝对转角上限执行球面插值。</p>
     *
     * @param current 当前速度向量，单位格/Tick
     * @param desiredDirection 制导期望方向；允许传入未归一化向量
     * @param limits 当前 Tick 的气动转向限制快照
     * @return 包含转向速度、载荷因子、限制来源和诱导阻力损失的不可变结果
     */
    public static RVP_AeroSteeringSolution solve(Vec3 current, Vec3 desiredDirection,
                                                  RVP_AeroSteeringLimits limits) {
        Vec3 safeCurrent = current == null ? Vec3.ZERO : current;
        if (limits == null) {
            return noTurn(safeCurrent);
        }
        if (!limits.enabled()) {
            return solveLegacy(safeCurrent, desiredDirection, limits);
        }
        if (!RVP_BallisticTrajectoryMath.isFinite(safeCurrent)
                || desiredDirection == null
                || !RVP_BallisticTrajectoryMath.isFinite(desiredDirection)) {
            return noTurn(safeCurrent);
        }

        double speed = safeCurrent.length();
        if (speed <= MIN_SPEED
                || desiredDirection.lengthSqr() <= MIN_DIRECTION_LENGTH_SQUARED) {
            return noTurn(safeCurrent);
        }

        Vec3 currentDirection = safeCurrent.scale(1.0 / speed);
        Vec3 desired = desiredDirection.normalize();
        // 调用本项目通用夹角算法，保持反向与近零方向的数值口径一致。
        double commandedTurnAngle = RVP_BallisticTrajectoryMath.angleBetween(
                currentDirection, desired);
        if (commandedTurnAngle <= MIN_TURN_ANGLE) {
            return noTurn(safeCurrent);
        }

        // 调用本项目可用过载计算，让显式 G 值与 turningFactor 折算共享同一动压减载。
        double currentAvailableGs = availableGs(speed, limits);
        if (Double.isInfinite(currentAvailableGs)) {
            // 调用旧版 turningFactor 算法，保留 factor>=1 线导直控弹的瞬转语义。
            Vec3 exemptVelocity = RVP_TrajectorySteeringMath.applyTurningFactor(
                    safeCurrent, desired, speed, limits.turningFactor());
            double actualTurnAngle = RVP_BallisticTrajectoryMath.angleBetween(
                    safeCurrent, exemptVelocity);
            return new RVP_AeroSteeringSolution(
                    exemptVelocity, actualTurnAngle, Math.PI, 0.0,
                    Double.POSITIVE_INFINITY, 0.0,
                    RVP_AeroSteeringSolution.LimitReason.NONE);
        }

        double chordRatio = Mth.clamp(
                currentAvailableGs * PhysicsEngine.G / (2.0 * speed), 0.0, 1.0);
        double aerodynamicTurnLimit = 2.0 * Math.asin(chordRatio);
        double availableTurnAngle = aerodynamicTurnLimit;
        RVP_AeroSteeringSolution.LimitReason activeLimit =
                RVP_AeroSteeringSolution.LimitReason.AERO_G;

        double configuredTurnRate = limits.turnRateLimitDegPerTick();
        if (Double.isFinite(configuredTurnRate) && configuredTurnRate > 0.0) {
            double turnRateLimit = Math.toRadians(configuredTurnRate);
            if (turnRateLimit < availableTurnAngle) {
                availableTurnAngle = turnRateLimit;
                activeLimit = RVP_AeroSteeringSolution.LimitReason.TURN_RATE;
            }
        }

        double appliedTurnAngle = Math.min(commandedTurnAngle, availableTurnAngle);
        double loadFactor = availableTurnAngle > MIN_AVAILABLE_TURN_ANGLE
                ? Mth.clamp(appliedTurnAngle / availableTurnAngle, 0.0, 1.0)
                : 0.0;
        double turnFraction = commandedTurnAngle > MIN_TURN_ANGLE
                ? appliedTurnAngle / commandedTurnAngle
                : 0.0;
        // 调用本项目球面插值，复用完全反向时的确定性正交轴分支，避免产生非有限值。
        Vec3 nextVelocity = RVP_BallisticTrajectoryMath.slerpDirection(
                currentDirection, desired, turnFraction).scale(speed);
        RVP_AeroSteeringSolution.LimitReason limitedBy =
                appliedTurnAngle + MIN_TURN_ANGLE < commandedTurnAngle
                        ? activeLimit
                        : RVP_AeroSteeringSolution.LimitReason.NONE;
        // 调用本项目诱导阻力公式，只返回损失供运动阶段结算，不在转向阶段提前扣速。
        double dragLoss = inducedDragLoss(limits.inducedDrag(), loadFactor, speed);
        return new RVP_AeroSteeringSolution(
                nextVelocity, appliedTurnAngle, availableTurnAngle, loadFactor,
                currentAvailableGs, dragLoss, limitedBy);
    }

    /**
     * 在气动模型关闭时执行旧版转向并包装为统一结果。
     *
     * @param current 当前速度
     * @param desiredDirection 期望方向
     * @param limits 当前 Tick 限制快照
     * @return 标记为 DISABLED 且不产生载荷与诱导阻力的结果
     */
    private static RVP_AeroSteeringSolution solveLegacy(
            Vec3 current, Vec3 desiredDirection, RVP_AeroSteeringLimits limits) {
        Vec3 legacyVelocity;
        if (limits.rvpMaxGs() != null) {
            // 调用本项目旧版 G 值转向，确保关闭开关时与现有实体/虚拟链行为一致。
            legacyVelocity = RVP_BallisticTrajectoryMath.applySteering(
                    current, desiredDirection, limits.rvpMaxGs());
        } else {
            double speed = RVP_BallisticTrajectoryMath.isFinite(current)
                    ? current.length()
                    : 0.0;
            // 调用本项目旧版方向插值，保留未配置 rvpMaxGs 时的原始 turningFactor 语义。
            legacyVelocity = RVP_TrajectorySteeringMath.applyTurningFactor(
                    current, desiredDirection, speed, limits.turningFactor());
        }
        double turnAngle = RVP_BallisticTrajectoryMath.isFinite(current)
                && RVP_BallisticTrajectoryMath.isFinite(legacyVelocity)
                ? RVP_BallisticTrajectoryMath.angleBetween(current, legacyVelocity)
                : 0.0;
        return new RVP_AeroSteeringSolution(
                legacyVelocity, turnAngle, turnAngle, 0.0, 0.0, 0.0,
                RVP_AeroSteeringSolution.LimitReason.DISABLED);
    }

    /**
     * 创建未发生转向的零载荷结果。
     *
     * @param velocity 保持不变的速度
     * @return 零转角、零过载、零诱导阻力结果
     */
    private static RVP_AeroSteeringSolution noTurn(Vec3 velocity) {
        return new RVP_AeroSteeringSolution(
                velocity, 0.0, 0.0, 0.0, 0.0, 0.0,
                RVP_AeroSteeringSolution.LimitReason.NONE);
    }
}
