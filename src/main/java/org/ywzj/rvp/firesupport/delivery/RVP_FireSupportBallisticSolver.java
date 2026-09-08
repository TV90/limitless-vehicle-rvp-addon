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
        if (weapon == null || kind == null || launch == null || target == null) return null;
        Vec3 horizontalDelta = new Vec3(target.x - launch.x, 0.0D, target.z - launch.z);
        double distance = horizontalDelta.length();
        double speed = weapon.resolveMuzzleSpeed(kind);
        if (!Double.isFinite(distance) || distance < 1.0E-6D || !Double.isFinite(speed) || speed <= 0.0D) return null;

        List<GroundSolution> roots = new ArrayList<>();
        Evaluation previous = null;
        double previousAngle = 0.0D;
        for (double angle = ANGLE_STEP_DEGREES; angle < 90.0D; angle += ANGLE_STEP_DEGREES) {
            Evaluation current = evaluateGround(weapon, kind, distance, launch.y, target.y, speed, angle);
            if (current != null && Math.abs(current.verticalError()) <= MAX_PREDICTION_ERROR_METERS) {
                roots.add(toGroundSolution(horizontalDelta, launch, current, angle, speed, weapon, kind));
            }
            if (previous != null && current != null && Math.signum(previous.verticalError()) != Math.signum(current.verticalError())) {
                roots.add(refineRoot(weapon, kind, horizontalDelta, launch, target, speed,
                        previousAngle, angle, previous, current));
            }
            previous = current;
            previousAngle = angle;
        }
        roots = roots.stream().filter(solution -> solution != null
                        && solution.predictionErrorMeters() <= MAX_PREDICTION_ERROR_METERS)
                .sorted(Comparator.comparingDouble(GroundSolution::launchAngleDegrees)).toList();
        if (roots.isEmpty()) return null;
        GroundSolution low = roots.get(0);
        GroundSolution high = roots.get(roots.size() - 1);
        return high.apexY() - target.y <= maxApexAboveImpact ? high : low;
    }

    /** 计算给定高度和载机水平速度下的无制导炸弹释放距离。 */
    @Nullable
    public static AirSolution solveAirRelease(RVP_WeaponData weapon, double releaseY, double targetY,
                                              double carrierSpeedMetersPerTick) {
        if (weapon == null || !Double.isFinite(releaseY) || !Double.isFinite(targetY)
                || !Double.isFinite(carrierSpeedMetersPerTick) || carrierSpeedMetersPerTick <= 0.0D
                || releaseY <= targetY) return null;
        Vec3 position = new Vec3(0.0D, releaseY, 0.0D);
        Vec3 velocity = new Vec3(carrierSpeedMetersPerTick, 0.0D, 0.0D);
        double previousX = 0.0D;
        double previousY = releaseY;
        int maxTicks = Math.min(Math.max(weapon.getLife(), 1), MAX_SOLVER_TICKS);
        for (int tick = 1; tick <= maxTicks; tick++) {
            RVP_UnguidedBallisticMath.Step step = RVP_UnguidedBallisticMath.stepBomb(position, velocity, weapon);
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

    /** 空投反解结果。 */
    public record AirSolution(
            /** 释放点到目标点的水平距离，单位格。 */ double releaseDistanceMeters,
            /** 预计飞行 Tick。 */ int flightTicks,
            /** 最后离散 Tick 的垂直越界量，仅供诊断。 */ double terminalVerticalOvershootMeters) {}

    /** 指定水平距离处的轨迹评估。 */
    private record Evaluation(
            /** 交会高度减目标高度，单位格。 */ double verticalError,
            /** 到达交会点所需 Tick。 */ int flightTicks,
            /** 轨迹顶点世界 Y。 */ double apexY) {}
}
