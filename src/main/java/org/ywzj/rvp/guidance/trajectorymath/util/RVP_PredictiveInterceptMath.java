package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;

/** PIP 恒速相遇解；仅计算目标点，不读实体、不修改速度或机头。 */
public final class RVP_PredictiveInterceptMath {
    /** 退化速度、距离与多项式系数的数值阈值。 */
    private static final double EPSILON = 1.0E-8;
    /** 无正根但仍在接近时的外推上限，单位 Tick（60 秒），防止近零闭速产生巨量提前量。 */
    private static final double MAX_FALLBACK_TICKS = 1200.0;

    private RVP_PredictiveInterceptMath() { }

    /** 相遇解来源；用于区分几何可解、有限闭速近似与明确退化。 */
    public enum Status {
        /** 二次方程的最小正根。 */
        QUADRATIC,
        /** 速度相等时退化为线性方程。 */
        LINEAR,
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

    /** 与现有弹道工具保持相同的有限向量判据。 */
    private static boolean finite(Vec3 value) {
        // 调用本项目工具检查三个坐标，而不是只检查向量引用。
        return value != null && RVP_BallisticTrajectoryMath.isFinite(value);
    }
}
