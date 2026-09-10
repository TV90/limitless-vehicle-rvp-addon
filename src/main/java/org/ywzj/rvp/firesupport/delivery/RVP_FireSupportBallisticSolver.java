package org.ywzj.rvp.firesupport.delivery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.physics.RVP_UnguidedBallisticMath;

/** 为炮火支援反解真实 RVP 离散弹道的无世界状态工具。 */
public final class RVP_FireSupportBallisticSolver {
    /** 角度粗搜索步长，兼顾高低弹道检出率与服务端开销。 */ private static final double ANGLE_STEP_DEGREES = 0.25D;
    /** 二分校正次数。 */ private static final int BISECTION_STEPS = 24;
    /** 允许纯数学轨迹偏离目标中心的最大距离，单位格。 */ public static final double MAX_PREDICTION_ERROR_METERS = 0.5D;
    /** 防止恶意超长 life 让单次求解占用过多 CPU 的 Tick 上限。 */ private static final int MAX_SOLVER_TICKS = 4096;

    private RVP_FireSupportBallisticSolver() {}

    /**
     * 固定武器标称速度求解高/低弹道；高弹道顶点超限时自动选择低弹道。
     */
    @Nullable
    public static GroundSolution solveGround(RVP_WeaponData weapon, RVP_EnumWeaponKind kind,
                                             Vec3 launch, Vec3 target, double maxApexAboveImpact) {
        return solveGround(weapon, kind, launch, target, maxApexAboveImpact, 0.0D);
    }

    /**
     * 使用可选初速覆盖求解高/低弹道；覆盖值为 0 时沿用武器解析初速及其 0.01 最小值兜底。
     */
    @Nullable
    public static GroundSolution solveGround(RVP_WeaponData weapon, RVP_EnumWeaponKind kind,
                                             Vec3 launch, Vec3 target, double maxApexAboveImpact,
                                             double entrySpeedMetersPerTick) {
        return diagnoseGround(weapon, kind, launch, target, maxApexAboveImpact,
                entrySpeedMetersPerTick).solution();
    }

    /** 求解地射轨迹并保留失败原因；诊断不会改变正式轨迹选择。 */
    public static GroundSolveDiagnostic diagnoseGround(RVP_WeaponData weapon, RVP_EnumWeaponKind kind,
                                                       Vec3 launch, Vec3 target, double maxApexAboveImpact,
                                                       double entrySpeedMetersPerTick) {
        if (weapon == null || kind == null || launch == null || target == null) {
            return GroundSolveDiagnostic.failure(GroundFailureReason.INVALID_INPUT,
                    Double.NaN, Double.NaN, 0, 0, 0);
        }
        Vec3 horizontalDelta = new Vec3(target.x - launch.x, 0.0D, target.z - launch.z);
        double distance = horizontalDelta.length();
        double speed = entrySpeedMetersPerTick > 0.0D
                ? entrySpeedMetersPerTick : weapon.resolveMuzzleSpeed(kind);
        if (!Double.isFinite(distance) || distance < 1.0E-6D) {
            return GroundSolveDiagnostic.failure(GroundFailureReason.INVALID_HORIZONTAL_DISTANCE,
                    distance, speed, 0, 0, 0);
        }
        if (!Double.isFinite(speed) || speed <= 0.0D) {
            return GroundSolveDiagnostic.failure(GroundFailureReason.INVALID_SPEED,
                    distance, speed, 0, 0, 0);
        }

        List<GroundSolution> roots = new ArrayList<>();
        Evaluation previous = null;
        double previousAngle = 0.0D;
        int evaluatedAngles = 0;
        int reachableEvaluations = 0;
        int signChangeCount = 0;
        for (double angle = ANGLE_STEP_DEGREES; angle < 90.0D; angle += ANGLE_STEP_DEGREES) {
            Evaluation current = evaluateGround(weapon, kind, distance, launch.y, target.y, speed, angle);
            evaluatedAngles++;
            if (current != null) reachableEvaluations++;
            if (current != null && Math.abs(current.verticalError()) <= MAX_PREDICTION_ERROR_METERS) {
                roots.add(toGroundSolution(horizontalDelta, launch, current, angle, speed, weapon, kind));
            }
            if (previous != null && current != null && Math.signum(previous.verticalError()) != Math.signum(current.verticalError())) {
                signChangeCount++;
                roots.add(refineRoot(weapon, kind, horizontalDelta, launch, target, speed,
                        previousAngle, angle, previous, current));
            }
            previous = current;
            previousAngle = angle;
        }
        roots = roots.stream().filter(solution -> solution != null
                        && solution.predictionErrorMeters() <= MAX_PREDICTION_ERROR_METERS)
                .sorted(Comparator.comparingDouble(GroundSolution::launchAngleDegrees)).toList();
        if (roots.isEmpty()) {
            GroundFailureReason reason = reachableEvaluations == 0
                    ? GroundFailureReason.NO_FORWARD_TRAJECTORY
                    : signChangeCount > 0
                            ? GroundFailureReason.PREDICTION_ERROR
                            : GroundFailureReason.NO_HEIGHT_INTERSECTION;
            return GroundSolveDiagnostic.failure(reason, distance, speed, evaluatedAngles,
                    reachableEvaluations, signChangeCount);
        }
        GroundSolution low = roots.get(0);
        GroundSolution high = roots.get(roots.size() - 1);
        GroundSolution selected = high.apexY() - target.y <= maxApexAboveImpact ? high : low;
        return GroundSolveDiagnostic.success(selected, distance, speed, evaluatedAngles,
                reachableEvaluations, signChangeCount);
    }

    /** 计算给定高度和载机水平速度下的无制导炸弹释放距离。 */
    @Nullable
    public static AirSolution solveAirRelease(RVP_WeaponData weapon, double releaseY, double targetY,
                                              double carrierSpeedMetersPerTick) {
        return solveAirRelease(weapon, RVP_EnumWeaponKind.BOMB, releaseY, targetY, carrierSpeedMetersPerTick);
    }

    /**
     * 按具体 RVP 弹种计算目标上游投放距离；炸弹沿用本体炸弹重力，其余实体弹体沿用通用投射物步进。
     */
    @Nullable
    public static AirSolution solveAirRelease(RVP_WeaponData weapon, RVP_EnumWeaponKind kind,
                                              double releaseY, double targetY,
                                              double carrierSpeedMetersPerTick) {
        if (weapon == null || !Double.isFinite(releaseY) || !Double.isFinite(targetY)
                || !Double.isFinite(carrierSpeedMetersPerTick) || carrierSpeedMetersPerTick <= 0.0D
                || releaseY <= targetY || kind == null) return null;
        Vec3 position = new Vec3(0.0D, releaseY, 0.0D);
        Vec3 velocity = new Vec3(carrierSpeedMetersPerTick, 0.0D, 0.0D);
        double previousX = 0.0D;
        double previousY = releaseY;
        int maxTicks = Math.min(Math.max(weapon.getLife(), 1), MAX_SOLVER_TICKS);
        for (int tick = 1; tick <= maxTicks; tick++) {
            RVP_UnguidedBallisticMath.Step step = kind == RVP_EnumWeaponKind.BOMB
                    ? RVP_UnguidedBallisticMath.stepBomb(position, velocity, weapon)
                    : RVP_UnguidedBallisticMath.stepProjectile(position, velocity, weapon);
            position = step.position();
            velocity = step.velocity();
            if (position.y <= targetY) {
                double fraction = previousY == position.y ? 1.0D
                        : (previousY - targetY) / (previousY - position.y);
                double crossingX = previousX + (position.x - previousX) * fraction;
                return new AirSolution(crossingX, tick, Math.abs(position.y - targetY));
            }
            previousX = position.x;
            previousY = position.y;
        }
        return null;
    }

    /**
     * 从实际挂架位置和当前载机速度重新求解三维空投初速度。
     *
     * <p>该方法不把飞机速度重复叠加到 SpawnContext；返回的 initializedMotion 已经包含
     * 法向补偿和当前载机切线速度。求解失败表示当前姿态暂时不适合投放，而不是永久的
     * TRAJECTORY_UNREACHABLE。</p>
     */
    @Nullable
    public static ActualAirSolution solveActualAirRelease(RVP_WeaponData weapon,
                                                          RVP_EnumWeaponKind kind,
                                                          Vec3 release,
                                                          Vec3 target,
                                                          Vec3 carrierMotion) {
        if (weapon == null || kind == null || release == null || target == null || carrierMotion == null
                || !finite(release) || !finite(target) || !finite(carrierMotion)
                || release.y <= target.y || weapon.getLife() <= 0) return null;
        Vec3 horizontalDelta = new Vec3(target.x - release.x, 0.0D, target.z - release.z);
        if (horizontalDelta.lengthSqr() <= 1.0E-8D) return null;
        int maxTicks = Math.min(Math.max(weapon.getLife(), 1), MAX_SOLVER_TICKS);
        Vec3 targetDirection = horizontalDelta.normalize();
        // 相对飞机的发射速度只表达武器自身初速；载机当前完整世界速度在最终实体初速度中单独叠加。
        Vec3 relativeBaseMotion = Vec3.ZERO;
        if (kind != RVP_EnumWeaponKind.BOMB) {
            double muzzleSpeed = weapon.resolveMuzzleSpeed(kind);
            if (Double.isFinite(muzzleSpeed) && muzzleSpeed > 0.0D) {
                relativeBaseMotion = targetDirection.scale(muzzleSpeed);
            }
        }
        Vec3 baseWorldMotion = carrierMotion.add(relativeBaseMotion);
        int verticalCrossingTick = findVerticalCrossingTick(
                weapon, kind, release, baseWorldMotion, target.y, maxTicks);
        if (verticalCrossingTick < 0) return null;
        Candidate best = null;
        int firstCandidateTick = Math.max(1, verticalCrossingTick - 8);
        int lastCandidateTick = Math.min(maxTicks, verticalCrossingTick + 8);
        for (int flightTicks = firstCandidateTick; flightTicks <= lastCandidateTick; flightTicks++) {
            // 迭代变量是相对飞机速度；飞机切向和转弯法向速度始终通过 carrierMotion 进入世界速度。
            Vec3 relativeInitial = relativeBaseMotion;
            for (int iteration = 0; iteration < 8; iteration++) {
                Vec3 initial = carrierMotion.add(relativeInitial);
                Vec3 predicted = simulateToTick(weapon, kind, release, initial, flightTicks);
                Vec3 error = target.subtract(predicted);
                // 目的：修正相对投射速度而不是覆盖世界速度；载机的切向、法向和竖直速度先进入模拟，
                // 再由相对速度补足剩余三维误差，避免转弯时侧向速度被水平瞄准初值抹掉。
                relativeInitial = relativeInitial.add(error.scale(1.0D / Math.max(1, flightTicks)));
            }
            Vec3 initial = carrierMotion.add(relativeInitial);
            Vec3 predicted = simulateToTick(weapon, kind, release, initial, flightTicks);
            double error = predicted.distanceTo(target);
            if (best == null || error < best.error) best = new Candidate(initial, flightTicks, error);
            if (error <= MAX_PREDICTION_ERROR_METERS) {
                return new ActualAirSolution(initial, flightTicks, error);
            }
        }
        return best != null && best.error <= 2.0D
                ? new ActualAirSolution(best.motion, best.flightTicks, best.error) : null;
    }

    /**
     * 先按当前载机竖直速度寻找穿越目标高度的 Tick，避免对整个 life 做平方级离散搜索。
     * 水平法向补偿不会改变该竖直交会时间，随后只在邻域内做三维修正。
     */
    private static int findVerticalCrossingTick(RVP_WeaponData weapon, RVP_EnumWeaponKind kind,
                                                 Vec3 release, Vec3 initial, double targetY, int maxTicks) {
        Vec3 position = release;
        Vec3 velocity = initial;
        for (int tick = 1; tick <= maxTicks; tick++) {
            RVP_UnguidedBallisticMath.Step step = kind == RVP_EnumWeaponKind.BOMB
                    ? RVP_UnguidedBallisticMath.stepBomb(position, velocity, weapon)
                    : RVP_UnguidedBallisticMath.stepProjectile(position, velocity, weapon);
            position = step.position();
            velocity = step.velocity();
            if (position.y <= targetY) return tick;
        }
        return -1;
    }

    /** 按现有离散物理规则推进实际空投候选速度。 */
    private static Vec3 simulateToTick(RVP_WeaponData weapon, RVP_EnumWeaponKind kind,
                                       Vec3 release, Vec3 initial, int ticks) {
        Vec3 position = release;
        Vec3 velocity = initial;
        for (int tick = 0; tick < ticks; tick++) {
            RVP_UnguidedBallisticMath.Step step = kind == RVP_EnumWeaponKind.BOMB
                    ? RVP_UnguidedBallisticMath.stepBomb(position, velocity, weapon)
                    : RVP_UnguidedBallisticMath.stepProjectile(position, velocity, weapon);
            position = step.position();
            velocity = step.velocity();
        }
        return position;
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }

    @Nullable
    private static GroundSolution refineRoot(RVP_WeaponData weapon, RVP_EnumWeaponKind kind,
                                             Vec3 horizontalDelta, Vec3 launch, Vec3 target, double speed,
                                             double lowAngle, double highAngle,
                                             Evaluation lowEvaluation, Evaluation highEvaluation) {
        Evaluation best = Math.abs(lowEvaluation.verticalError()) <= Math.abs(highEvaluation.verticalError())
                ? lowEvaluation : highEvaluation;
        double bestAngle = best == lowEvaluation ? lowAngle : highAngle;
        for (int step = 0; step < BISECTION_STEPS; step++) {
            double middleAngle = (lowAngle + highAngle) * 0.5D;
            Evaluation middle = evaluateGround(weapon, kind, horizontalDelta.length(), launch.y, target.y,
                    speed, middleAngle);
            if (middle == null) break;
            if (Math.abs(middle.verticalError()) < Math.abs(best.verticalError())) {
                best = middle;
                bestAngle = middleAngle;
            }
            if (Math.signum(lowEvaluation.verticalError()) == Math.signum(middle.verticalError())) {
                lowAngle = middleAngle;
                lowEvaluation = middle;
            } else {
                highAngle = middleAngle;
                highEvaluation = middle;
            }
        }
        return toGroundSolution(horizontalDelta, launch, best, bestAngle, speed, weapon, kind);
    }

    private static GroundSolution toGroundSolution(Vec3 horizontalDelta, Vec3 launch, Evaluation evaluation,
                                                    double angleDegrees, double speed, RVP_WeaponData weapon,
                                                    RVP_EnumWeaponKind kind) {
        double radians = Math.toRadians(angleDegrees);
        Vec3 desiredMotion = horizontalDelta.normalize().scale(Math.cos(radians) * speed)
                .add(0.0D, Math.sin(radians) * speed, 0.0D);
        // 调用本项目共享初始化逆变换，抵消既有高初速火箭兼容缩放。
        Vec3 spawnContextMotion = RVP_UnguidedBallisticMath.resolveSpawnContextMotion(
                weapon, kind, desiredMotion);
        return new GroundSolution(spawnContextMotion, desiredMotion, angleDegrees,
                evaluation.flightTicks(), evaluation.apexY(), Math.abs(evaluation.verticalError()));
    }

    @Nullable
    private static Evaluation evaluateGround(RVP_WeaponData weapon, RVP_EnumWeaponKind kind, double distance,
                                             double launchY, double targetY, double speed, double angleDegrees) {
        double radians = Math.toRadians(angleDegrees);
        Vec3 position = new Vec3(0.0D, launchY, 0.0D);
        Vec3 velocity = new Vec3(Math.cos(radians) * speed, Math.sin(radians) * speed, 0.0D);
        double previousHorizontal = 0.0D;
        double previousY = launchY;
        double apexY = launchY;
        int maxTicks = Math.min(Math.max(weapon.getLife(), 1), MAX_SOLVER_TICKS);
        for (int tick = 1; tick <= maxTicks; tick++) {
            RVP_UnguidedBallisticMath.Step step = kind == RVP_EnumWeaponKind.MACHINEGUN
                    ? RVP_UnguidedBallisticMath.stepCannon(position, velocity,
                            weapon.getCannonFriction(), weapon.getCannonGravity())
                    : RVP_UnguidedBallisticMath.stepProjectile(position, velocity, weapon);
            position = step.position();
            velocity = step.velocity();
            apexY = Math.max(apexY, position.y);
            if (position.x >= distance) {
                double fraction = position.x == previousHorizontal ? 1.0D
                        : (distance - previousHorizontal) / (position.x - previousHorizontal);
                double crossingY = previousY + (position.y - previousY) * fraction;
                return new Evaluation(crossingY - targetY, tick, apexY);
            }
            if (position.x <= previousHorizontal + 1.0E-8D && velocity.x <= 0.0D) return null;
            previousHorizontal = position.x;
            previousY = position.y;
        }
        return null;
    }

    /** 地射解算结果，同时保留 SpawnContext 输入速度和实体初始化后的实际速度。 */
    public record GroundSolution(
            /** 传给无载具生成上下文的初速度。 */ Vec3 spawnContextMotion,
            /** 弹体完成初始化后的标称初速度。 */ Vec3 initializedMotion,
            /** 相对水平面的发射仰角，单位度。 */ double launchAngleDegrees,
            /** 预计飞行 Tick。 */ int flightTicks,
            /** 预测轨迹顶点世界 Y。 */ double apexY,
            /** 预测终点误差，单位格。 */ double predictionErrorMeters) {}

    /** 地射求解失败类别；仅用于服务端诊断，不改变投送状态机。 */
    public enum GroundFailureReason {
        /** 输入对象缺失。 */ INVALID_INPUT,
        /** 发射点与目标点水平距离无效或过小。 */ INVALID_HORIZONTAL_DISTANCE,
        /** 初速无效。 */ INVALID_SPEED,
        /** 所有角度都未能在武器生命周期内前进到目标距离。 */ NO_FORWARD_TRAJECTORY,
        /** 轨迹到达目标距离，但没有穿越目标高度。 */ NO_HEIGHT_INTERSECTION,
        /** 有高度交点，但离散预测误差超过允许值。 */ PREDICTION_ERROR,
        /** 求解成功。 */ NONE
    }

    /** 地射解算诊断；保留有效速度、搜索统计和失败原因供 LOGGER.error 输出。 */
    public record GroundSolveDiagnostic(
            /** 成功时的轨迹，失败时为 null。 */ GroundSolution solution,
            /** 本次求解的失败类别，成功时为 NONE。 */ GroundFailureReason failureReason,
            /** 发射点到目标点的水平距离，单位格。 */ double horizontalDistanceMeters,
            /** 本次实际使用的初速，单位格/Tick。 */ double effectiveSpeedMetersPerTick,
            /** 扫描的发射角数量。 */ int evaluatedAngles,
            /** 能够在生命周期内抵达目标水平距离的角度数量。 */ int reachableEvaluations,
            /** 相邻角度间发现的高度误差符号变化数量。 */ int signChangeCount) {
        private static GroundSolveDiagnostic success(GroundSolution solution, double distance, double speed,
                                                      int evaluatedAngles, int reachableEvaluations,
                                                      int signChangeCount) {
            return new GroundSolveDiagnostic(solution, GroundFailureReason.NONE, distance, speed,
                    evaluatedAngles, reachableEvaluations, signChangeCount);
        }

        private static GroundSolveDiagnostic failure(GroundFailureReason reason, double distance, double speed,
                                                      int evaluatedAngles, int reachableEvaluations,
                                                      int signChangeCount) {
            return new GroundSolveDiagnostic(null, reason, distance, speed,
                    evaluatedAngles, reachableEvaluations, signChangeCount);
        }
    }

    /** 空投反解结果。 */
    public record AirSolution(
            /** 释放点到目标点的水平距离，单位格。 */ double releaseDistanceMeters,
            /** 预计飞行 Tick。 */ int flightTicks,
            /** 最后离散 Tick 的垂直越界量，仅供诊断。 */ double terminalVerticalOvershootMeters) {}

    /** 实际挂架位置下的三维空投解算结果。 */
    public record ActualAirSolution(
            /** 已包含载机当前速度和法向补偿的实体初速度。 */ Vec3 initializedMotion,
            /** 预计离散飞行 Tick。 */ int flightTicks,
            /** 预测终点误差，单位格。 */ double predictionErrorMeters) {}

    /** 实时空投搜索中的最优候选。 */
    private record Candidate(Vec3 motion, int flightTicks, double error) {}

    /** 指定水平距离处的轨迹评估。 */
    private record Evaluation(
            /** 交会高度减目标高度，单位格。 */ double verticalError,
            /** 到达交会点所需 Tick。 */ int flightTicks,
            /** 轨迹顶点世界 Y。 */ double apexY) {}
}
