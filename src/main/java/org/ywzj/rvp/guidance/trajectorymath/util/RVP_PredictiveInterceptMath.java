package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;

/** PIP 相遇解；仅计算目标点，不读实体、不修改速度或机头。 */
public final class RVP_PredictiveInterceptMath {
    /** 退化速度、距离与多项式系数的数值阈值。 */
    private static final double EPSILON = 1.0E-8;
    /** 无正根但仍在接近时的外推上限，单位 Tick（60 秒），防止近零闭速产生巨量提前量。 */
    private static final double MAX_FALLBACK_TICKS = 1200.0;
    /** 恒加速度预测的固定点迭代次数，避免目标加速度把制导热路径变成无界求解。 */
    private static final int ACCELERATION_ITERATIONS = 8;
    /** 恒加速度只负责短时机动外推，单位 Tick；超出后回退恒速解，避免远期抛物线失真。 */
    private static final double MAX_ACCELERATION_PREDICTION_TICKS = 120.0;

    private RVP_PredictiveInterceptMath() { }

    /** 相遇解来源；用于区分几何可解、有限闭速近似与明确退化。 */
    public enum Status {
        /** 二次方程的最小正根。 */
        QUADRATIC,
        /** 速度相等时退化为线性方程。 */
        LINEAR,
        /** 目标存在有限加速度，使用当前恒加速度模型的有限时域相遇解。 */
        ACCELERATED,
        /** 无正根但径向闭速为正，使用有界近似。 */
        CLOSING_APPROXIMATION,
        /** 弹目已经重合，无需提前量。 */
        COINCIDENT,
        /** 无正根且未接近，交回调用方纯追踪。 */
        UNREACHABLE,
        /** 输入无效或弹速为零，交回调用方原有回退。 */
        INVALID
    }

    /**
     * @param point 预测目标点；不可解时为 null
     * @param timeTicks 相遇时间，单位 Tick；不可解时为 NaN
     * @param status 求解或回退来源
     */
    public record Solution(Vec3 point, double timeTicks, Status status) {
        /** @return 是否具有可供转向使用的有限目标点。 */
        public boolean usable() {
            return point != null && Double.isFinite(timeTicks);
        }
    }

    /** 求最小正根；无正根时仅允许正径向闭速生成受限近似。 */
    public static Solution solve(Vec3 missilePosition, Vec3 missileVelocity,
                                 Vec3 targetPosition, Vec3 targetVelocity) {
        // 调用本项目有限值校验，避免坏数据传播到三角函数和实体运动。
        if (!finite(missilePosition) || !finite(missileVelocity)
                || !finite(targetPosition) || !finite(targetVelocity)
                || missileVelocity.length() <= EPSILON) {
            return new Solution(null, Double.NaN, Status.INVALID);
        }
        Vec3 relative = targetPosition.subtract(missilePosition);
        double c = relative.lengthSqr();
        if (c <= EPSILON * EPSILON) {
            return new Solution(targetPosition, 0.0, Status.COINCIDENT);
        }
        double a = targetVelocity.lengthSqr() - missileVelocity.lengthSqr();
        double b = 2.0 * relative.dot(targetVelocity);
        double time = Double.NaN;
        Status status = Status.QUADRATIC;
        if (Math.abs(a) < EPSILON) {
            if (b < -EPSILON) {
                time = -c / b;
                status = Status.LINEAR;
            }
        } else {
            double discriminant = b * b - 4.0 * a * c;
            if (Double.isFinite(discriminant) && discriminant >= 0.0) {
                // 稳定求根，避免接近等速时两个大数相减丢失较小的正根。
                double q = -0.5 * (b + Math.copySign(Math.sqrt(discriminant), b));
                double first = q / a;
                double second = Math.abs(q) > EPSILON ? c / q : Double.NaN;
                if (Double.isFinite(first) && first > 0.0) time = first;
                if (Double.isFinite(second) && second > 0.0
                        && (!Double.isFinite(time) || second < time)) time = second;
            }
        }
        if (!Double.isFinite(time) || time <= 0.0) {
            // 径向闭速只取相对速度投影，禁止再加一次 missileSpeed。
            double closing = missileVelocity.subtract(targetVelocity).dot(relative.normalize());
            if (!Double.isFinite(closing) || closing <= EPSILON) {
                return new Solution(null, Double.NaN, Status.UNREACHABLE);
            }
            time = Math.min(Math.sqrt(c) / closing, MAX_FALLBACK_TICKS);
            status = Status.CLOSING_APPROXIMATION;
        }
        Vec3 point = targetPosition.add(targetVelocity.scale(time));
        // 调用本项目有限值校验，极端量级溢出时明确回退，不生成无穷坐标。
        return finite(point) ? new Solution(point, time, status)
                : new Solution(null, Double.NaN, Status.INVALID);
    }

    /**
     * 在恒速 PIP 上增加目标局部加速度外推。
     *
     * <p>先用恒速解提供初值，再对
     * {@code |targetPos + targetVelocity*t + 0.5*targetAcceleration*t^2 - missilePos| = missileSpeed*t}
     * 做有界 Newton 迭代。该方法不假设目标未来永远保持加速度，调用方应传入已滤波且有
     * 置信度的短时估计。</p>
     *
     * @param missilePosition 当前导弹位置
     * @param missileVelocity 当前导弹速度
     * @param targetPosition 当前目标瞄准点
     * @param targetVelocity 当前目标速度
     * @param targetAcceleration 当前目标加速度，单位格/Tick^2
     * @return 带加速度修正的有限相遇点；加速度无法形成可靠根时仍返回有界近似
     */
    public static Solution solve(Vec3 missilePosition, Vec3 missileVelocity,
                                 Vec3 targetPosition, Vec3 targetVelocity,
                                 Vec3 targetAcceleration) {
        if (!finite(targetAcceleration)) {
            // 调用本项目恒速入口，坏加速度按安全的历史预测退化，不把 NaN 带入 Newton 迭代。
            return solve(missilePosition, missileVelocity, targetPosition, targetVelocity);
        }
        if (targetAcceleration.lengthSqr() <= EPSILON * EPSILON) {
            // 调用本项目恒速入口，零加速度保持原有求根状态和诊断语义。
            return solve(missilePosition, missileVelocity, targetPosition, targetVelocity);
        }
        if (!finite(missilePosition) || !finite(missileVelocity)
                || !finite(targetPosition) || !finite(targetVelocity)
                || missileVelocity.length() <= EPSILON) {
            return new Solution(null, Double.NaN, Status.INVALID);
        }
        Vec3 relative = targetPosition.subtract(missilePosition);
        double distance = relative.length();
        if (distance <= EPSILON) {
            return new Solution(targetPosition, 0.0, Status.COINCIDENT);
        }

        // 调用恒速求解器取得物理上更稳定的初始相遇时间；无恒速正根时从当前距离/弹速起步，
        // 允许“目标正在减速”这类加速度模型恢复一个有限候选，而不是立即纯追踪。
        Solution constant = solve(missilePosition, missileVelocity, targetPosition, targetVelocity);
        double time = constant.usable()
                ? Math.min(MAX_ACCELERATION_PREDICTION_TICKS, constant.timeTicks())
                : Math.min(MAX_ACCELERATION_PREDICTION_TICKS,
                        Math.max(1.0, distance / missileVelocity.length()));
        boolean converged = false;
        double missileSpeed = missileVelocity.length();
        for (int iteration = 0; iteration < ACCELERATION_ITERATIONS; iteration++) {
            Vec3 predictedRelative = relative
                    .add(targetVelocity.scale(time))
                    .add(targetAcceleration.scale(0.5 * time * time));
            double range = predictedRelative.length();
            if (!Double.isFinite(range) || range <= EPSILON) {
                time = 0.0;
                converged = true;
                break;
            }
            double function = range - missileSpeed * time;
            if (Math.abs(function) <= 1.0E-5) {
                converged = true;
                break;
            }
            Vec3 targetFutureVelocity = targetVelocity.add(targetAcceleration.scale(time));
            double derivative = predictedRelative.dot(targetFutureVelocity) / range - missileSpeed;
            if (!Double.isFinite(derivative) || Math.abs(derivative) <= EPSILON) {
                break;
            }
            double next = time - function / derivative;
            if (!Double.isFinite(next) || next <= EPSILON) {
                break;
            }
            time = Math.min(next, MAX_ACCELERATION_PREDICTION_TICKS);
        }
        if (!Double.isFinite(time) || time <= EPSILON) {
            return constant;
        }
        Vec3 point = targetPosition.add(targetVelocity.scale(time))
                .add(targetAcceleration.scale(0.5 * time * time));
        double residual = Math.abs(point.subtract(missilePosition).length() - missileSpeed * time);
        if (!Double.isFinite(residual) || (!converged && residual > Math.max(0.05D, missileSpeed * 0.05D))) {
            // 调用恒速求解器回退，避免把未收敛的 Newton 中间点误当成可命中目标。
            return constant;
        }
        // 即使 Newton 未在固定次数内完全收敛，也保留有限的加速度预测点；下一 Tick 会用新观测重新修正。
        return finite(point) ? new Solution(point, time, Status.ACCELERATED)
                : (converged ? new Solution(null, Double.NaN, Status.INVALID) : constant);
    }

    /** 与现有弹道工具保持相同的有限向量判据。 */
    private static boolean finite(Vec3 value) {
        // 调用本项目工具检查三个坐标，而不是只检查向量引用。
        return value != null && RVP_BallisticTrajectoryMath.isFinite(value);
    }
}
