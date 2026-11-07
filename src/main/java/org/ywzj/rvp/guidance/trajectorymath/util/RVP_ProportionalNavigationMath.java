package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 三维纯比例导航（PPN）：以世界坐标视线角速度产生速度法向加速度，不求未来相遇点。
 *
 * <p>采用 {@code omega = r cross (vTarget - vMissile) / |r|^2}、
 * {@code a = gain * (omega cross vMissile)}。长度用格、时间用 Tick；返回的是一刻航迹请求，
 * 真实 G 值、攻角、机头惯量和能量代价仍由原执行器裁决。本类无跨刻状态，不访问实体或世界。
 * 公式参见 Johns Hopkins APL《Basic Principles of Homing Guidance》式 21。</p>
 */
public final class RVP_ProportionalNavigationMath {
    /** 零距离、零速度和零修正的数值阈值。 */
    private static final double EPSILON = 1.0E-8;
    /** 单刻请求最多接近半圈，避免近距离大角速度绕过一圈后反向或丢失转弯方向。 */
    private static final double MAX_REQUEST_TURN = Math.PI - 0.003;

    /** 工具类不允许实例化。 */
    private RVP_ProportionalNavigationMath() {}

    /** 明确区分正式 PPN、退化输入和初始转入，供诊断与回归使用。 */
    public enum Status {
        /** 当前相对运动产生了有效 PPN 请求；零视线角速度也属于正常状态。 */ ACTIVE,
        /** 目标恰在后方且没有视线转动，使用受原预算限制的追点指令建立初始转弯平面。 */ CAPTURE,
        /** 已越过一刻运动范围内的目标，保留速度，不在奇点附近要求掉头。 */ PASSED_TARGET,
        /** 弹目重合，保持原速度。 */ COINCIDENT,
        /** 零速没有可用的速度轴，保持原速度。 */ ZERO_SPEED,
        /** 非有限输入或非法增益，拒绝产生新运动。 */ INVALID
    }

    /**
     * @param requestedVelocity 本刻期望速度，格/Tick；有效请求严格保持当前速率
     * @param acceleration PPN 原始侧向加速度，格/Tick²，尚未做执行器限幅
     * @param lineOfSightRate 世界坐标视线角速度，弧度/Tick
     * @param closingSpeed 径向接近速度，格/Tick；正值接近，负值远离，不把负值伪装成零
     * @param status 请求来源或退化原因
     */
    public record Solution(Vec3 requestedVelocity, Vec3 acceleration, Vec3 lineOfSightRate,
                           double closingSpeed, Status status) {}

    /**
     * 只使用本刻真实位置和速度生成 PPN 请求；不乘 turning_factor，也不修改机头或动力状态。
     *
     * @param missilePosition 导弹位置，格
     * @param missileVelocity 导弹实际速度，格/Tick，不能用历史峰值代替
     * @param targetPosition 本刻真实瞄准点，格，不包含高抛辅助高度
     * @param targetVelocity 本刻目标速度，格/Tick
     * @param gain 无量纲比例系数，0 表示不请求转弯，必须为非负有限值
     * @return 一刻请求及原始视线观测
     */
    public static Solution solve(Vec3 missilePosition, Vec3 missileVelocity,
                                 Vec3 targetPosition, Vec3 targetVelocity, double gain) {
        // 调用本项目有限值判据，坏数据不能进入叉乘、归一化或实体运动。
        Vec3 unchanged = finite(missileVelocity) ? missileVelocity : Vec3.ZERO;
        if (!finite(missilePosition) || !finite(missileVelocity)
                || !finite(targetPosition) || !finite(targetVelocity)
                || !Double.isFinite(gain) || gain < 0.0) {
            return unchanged(unchanged, Status.INVALID);
        }
        double speed = missileVelocity.length();
        if (!Double.isFinite(speed)) return unchanged(unchanged, Status.INVALID);
        if (speed <= EPSILON) return unchanged(unchanged, Status.ZERO_SPEED);
        Vec3 relative = targetPosition.subtract(missilePosition);
        double rangeSquared = relative.lengthSqr();
        if (!Double.isFinite(rangeSquared)) return unchanged(unchanged, Status.INVALID);
        if (rangeSquared <= EPSILON * EPSILON) return unchanged(unchanged, Status.COINCIDENT);

        double range = Math.sqrt(rangeSquared);
        Vec3 relativeVelocity = targetVelocity.subtract(missileVelocity);
        double closingSpeed = -relative.dot(relativeVelocity) / range;
        Vec3 rate = relative.cross(relativeVelocity).scale(1.0 / rangeSquared);
        Vec3 acceleration = rate.cross(missileVelocity).scale(gain);
        // 调用共同有限值判据，极端输入溢出时保持原速率，不生成无穷航迹。
        if (!Double.isFinite(closingSpeed) || !finite(rate) || !finite(acceleration)) {
            return unchanged(unchanged, Status.INVALID);
        }
        double normalAcceleration = acceleration.length();
        if (!Double.isFinite(normalAcceleration)) return unchanged(unchanged, Status.INVALID);
        if (relative.dot(missileVelocity) < 0.0 && range <= speed) {
            return new Solution(unchanged, acceleration, rate, closingSpeed, Status.PASSED_TARGET);
        }
        if (normalAcceleration <= EPSILON) {
            // 完全反向时 PPN 本身没有转弯平面；零增益仍必须保持不转弯的显式语义。
            if (gain > 0.0 && relative.dot(missileVelocity) < 0.0) {
                return new Solution(relative.scale(speed / range), acceleration, rate, closingSpeed, Status.CAPTURE);
            }
            return new Solution(unchanged, acceleration, rate, closingSpeed, Status.ACTIVE);
        }
        // 在当前速度与侧向加速度形成的平面转动一刻；不用 v+a 后直接增速，也不写实体。
        double angle = Math.min(normalAcceleration / speed, MAX_REQUEST_TURN);
        Vec3 requested = missileVelocity.scale(Math.cos(angle))
                .add(acceleration.scale(speed * Math.sin(angle) / normalAcceleration));
        return new Solution(requested.normalize().scale(speed), acceleration, rate, closingSpeed, Status.ACTIVE);
    }

    /**
     * 按权重衔接两个单 Tick 航迹请求，只混合指令，不提前执行任何一次姿态积分。
     *
     * @param velocity 当前实际速度，格/Tick
     * @param navigationRequest 本刻 PPN 请求（或其混合基准）
     * @param leadRequest 本刻前置/路线请求，与 navigationRequest 同量纲
     * @param leadWeight 前置请求权重；1 完全使用前置请求，0 完全使用 PPN 请求
     * @return 保持当前速率的一刻请求
     */
    public static Vec3 blendDirectionRequest(Vec3 velocity, Vec3 navigationRequest,
                                             Vec3 leadRequest, double leadWeight) {
        // 调用本项目有限值与确定性球面插值，跨竖直方向和反向请求也不通过欧拉角相减。
        if (!finite(velocity) || velocity.lengthSqr() <= EPSILON * EPSILON) return Vec3.ZERO;
        if (!finite(navigationRequest) || navigationRequest.lengthSqr() <= EPSILON * EPSILON) return velocity;
        if (!finite(leadRequest) || leadRequest.lengthSqr() <= EPSILON * EPSILON) return navigationRequest;
        double weight = Double.isFinite(leadWeight) ? Mth.clamp(leadWeight, 0.0, 1.0) : 0.0;
        if (weight <= 0.0) return navigationRequest.normalize().scale(velocity.length());
        if (weight >= 1.0) return leadRequest.normalize().scale(velocity.length());
        return RVP_BallisticTrajectoryMath.slerpDirection(navigationRequest.normalize(), leadRequest.normalize(), weight)
                .scale(velocity.length());
    }

    /** 为退化输入生成没有伪造观测的安全结果。 */
    private static Solution unchanged(Vec3 velocity, Status status) {
        return new Solution(velocity, Vec3.ZERO, Vec3.ZERO, 0.0, status);
    }

    /** 与正式气动执行器共用有限值口径。 */
    private static boolean finite(Vec3 vector) {
        // 调用本项目向量校验，拒绝 null、NaN 和 Infinity。
        return vector != null && RVP_BallisticTrajectoryMath.isFinite(vector);
    }
}
