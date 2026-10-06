package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

/**
 * 机体轴和速度轴分离的准三自由度模型，不模拟力矩、角动量或滚转。
 * 使用游戏化的总攻角升力曲线；没有风场时速度轴视为相对气流方向。
 * 纯函数不访问实体、世界或客户端，实体和虚拟中段共用。
 */
public final class RVP_AttackAngleModel {
    /** 非零方向和速度的判定阈值。 */
    private static final double EPSILON = 1.0E-8;
    /** 显式 G 优先时的姿态响应比例，避免同时配置 turning_factor 改变 G 主控语义。 */
    private static final double G_CONTROL_RESPONSE = 0.5;

    private RVP_AttackAngleModel() { }

    /**
     * 先推进弹轴，再在受控攻角锥内计算升力；速度最多转到弹轴，防止低速数值过冲。
     * 无制导输入时传入当前速度作为期望方向，逐步回正后仍结算残留攻角。
     *
     * @param velocity 当前速度，格/Tick
     * @param bodyDirection 当前机体前向；零向量回退到速度轴
     * @param desiredDirection 制导期望方向，不得预先执行 turning_factor 或 G 裁决
     * @param limits 本 Tick 已解析的气动参数
     * @return 更新后的速度、机体轴及施力前攻角载荷
     */
    public static RVP_AttackAngleSolution solve(Vec3 velocity, Vec3 bodyDirection,
                                                Vec3 desiredDirection, RVP_AeroSteeringLimits limits) {
        // 调用本项目有限值检查，隔离坏输入；零速保持已有姿态，不凭空制造速度。
        Vec3 current = valid(velocity) ? velocity : Vec3.ZERO;
        Vec3 body = valid(bodyDirection) && bodyDirection.lengthSqr() > EPSILON * EPSILON
                ? bodyDirection.normalize() : (current.lengthSqr() > EPSILON * EPSILON
                ? current.normalize() : new Vec3(0.0, 0.0, 1.0));
        if (current.length() <= EPSILON || limits == null || !limits.attackAngleEnabled()) {
            return new RVP_AttackAngleSolution(current, body, 0.0, 0.0, 0.0);
        }
        Vec3 axis = current.normalize();
        Vec3 desired = valid(desiredDirection) && desiredDirection.lengthSqr() > EPSILON * EPSILON
                ? desiredDirection.normalize() : axis;
        double response = limits.rvpMaxGs() != null ? G_CONTROL_RESPONSE
                : Mth.clamp(limits.turningFactor(), 0.0, 1.0);
        // 调用本项目球面插值推进弹轴，避免反向指令在线性插值中坍缩为零向量。
        body = RVP_BallisticTrajectoryMath.slerpDirection(body, desired, response);
        double cap = Math.toRadians(limits.attackAngleLimitDeg());
        // 调用本项目夹角/球面插值，将受控姿态限制在速度轴周围的攻角锥内。
        double angle = RVP_BallisticTrajectoryMath.angleBetween(axis, body);
        if (angle > cap) {
            body = RVP_BallisticTrajectoryMath.slerpDirection(axis, body, cap / angle);
            angle = cap;
        }
        double load = Math.sin(angle) / Math.sin(cap);
        // 调用本项目动压过载模型，再按攻角使用率缩放；显式 0 G 不产生法向转向。
        double normalG = RVP_AeroSteeringModel.availableGs(current.length(), limits) * load;
        double turn = 2.0 * Math.asin(Mth.clamp(
                normalG * PhysicsEngine.G / (2.0 * current.length()), 0.0, 1.0));
        if (Double.isFinite(limits.turnRateLimitDegPerTick()) && limits.turnRateLimitDegPerTick() > 0) {
            turn = Math.min(turn, Math.toRadians(limits.turnRateLimitDegPerTick()));
        }
        turn = Double.isFinite(turn) ? Math.min(turn, angle) : 0.0;
        // 调用本项目球面插值积分法向速度，保持速率；推力与诱导阻力由运动阶段各结算一次。
        Vec3 next = angle > EPSILON && turn > 0.0
                ? RVP_BallisticTrajectoryMath.slerpDirection(axis, body, turn / angle).scale(current.length())
                : current;
        return new RVP_AttackAngleSolution(next, body, angle, Mth.clamp(load, 0.0, 1.0), turn);
    }

    /** 强制干扰造成的总攻角也计入能量代价；超过控制锥时饱和，不翻转升力符号。 */
    public static double loadFactor(Vec3 velocity, Vec3 body, double limitDeg) {
        if (!valid(velocity) || !valid(body) || velocity.lengthSqr() <= EPSILON * EPSILON
                || body.lengthSqr() <= EPSILON * EPSILON || !Double.isFinite(limitDeg)
                || limitDeg <= 0.0 || limitDeg >= 90.0) return 0.0;
        // 调用本项目夹角工具测量实际弹轴偏差，超限按满载记账。
        double angle = RVP_BallisticTrajectoryMath.angleBetween(velocity, body);
        double cap = Math.toRadians(limitDeg);
        return Math.sin(Math.min(angle, cap)) / Math.sin(cap);
    }

    /** 非有限方向不进入三角函数与向量归一化。 */
    private static boolean valid(Vec3 value) {
        // 调用本项目统一有限值检查，保持实体和虚拟积分的数值判据一致。
        return value != null && RVP_BallisticTrajectoryMath.isFinite(value);
    }
}
