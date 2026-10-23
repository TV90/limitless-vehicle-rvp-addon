package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** 固定点末段：随剩余交会时间收紧航迹，再反解机头，防止高速时只追视线而来不及消偏。 */
public final class RVP_PointGuidanceController {
    /** 视线收敛系数；大于 2 使靠近目标时横向偏差和横向速度同时收敛。 */
    private static final double NAVIGATION_GAIN = 3.0;
    /** 重合位置和零速判定阈值。 */
    private static final double EPSILON = 1.0E-8;

    private RVP_PointGuidanceController() { }

    /**
     * 生成本刻速度方向请求。仅在三维目标位于后方且已在一刻运动范围内时停止掉头；
     * 高空水平过顶仍继续指向真实目标，不锁死朝外漂移。
     */
    public static Vec3 requestVelocity(Vec3 position, Vec3 velocity, Vec3 target) {
        if (position == null || target == null || velocity == null) return Vec3.ZERO;
        // 调用共同有限值检查，坏坐标不进入角度运算，也不凭空产生制导速度。
        if (!RVP_BallisticTrajectoryMath.isFinite(position) || !RVP_BallisticTrajectoryMath.isFinite(target)
                || !RVP_BallisticTrajectoryMath.isFinite(velocity)) return Vec3.ZERO;
        Vec3 delta = target.subtract(position);
        double distance = delta.length();
        double speed = velocity.length();
        if (distance <= EPSILON || speed <= EPSILON) return velocity;
        double closingSpeed = velocity.dot(delta) / distance;
        if (closingSpeed < 0.0 && distance <= speed) return velocity;
        // 调用项目球面插值，按预计剩余飞行时间生成一刻请求；最终能力由执行器裁决。
        double fraction = Mth.clamp(NAVIGATION_GAIN * speed / distance, 0.0, 1.0);
        return RVP_BallisticTrajectoryMath.slerpDirection(
                velocity.scale(1.0 / speed), delta.scale(1.0 / distance), fraction).scale(speed);
    }

    /** 在同一原始姿态上反解并执行一次，不直接修改速度、不重复结算推力与阻力。 */
    public static RVP_AttackAngleSolution solve(Vec3 position, Vec3 velocity, Vec3 target,
            RVP_AttackAngleState state, RVP_AeroSteeringLimits limits,
            RVP_PropulsionMath.MotorState motor, double mass) {
        // 调用公共单刻航迹反解器；turning_factor 已用于 G/惯量预算，不重复缩小请求。
        return RVP_PIPAttackAngleController.solveVelocityRequest(velocity, state,
                requestVelocity(position, velocity, target), limits, motor, mass).steering();
    }
}
