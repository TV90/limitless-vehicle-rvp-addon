package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 无滚转双轴状态；角度遵循 Minecraft 俯仰向下为正、偏航向左为正的约定。
 *
 * @param pitchDeg 俯仰角，度，范围 -90～90
 * @param yawDeg 偏航角，度，按最短路径环绕
 * @param pitchRateDeg 俯仰角速度，度/Tick
 * @param yawRateDeg 偏航角速度，度/Tick
 */
public record RVP_AttackAngleState(double pitchDeg, double yawDeg,
                                  double pitchRateDeg, double yawRateDeg) {
    /** 从方向初始化静止执行器；竖直方向保留指定偏航，避免 atan2(0,0) 随机翻面。 */
    public static RVP_AttackAngleState fromDirection(Vec3 direction, double fallbackYaw) {
        double horizontal = Math.hypot(direction.x, direction.z);
        return new RVP_AttackAngleState(Math.toDegrees(Math.atan2(-direction.y, horizontal)),
                horizontal < 1.0E-8 ? fallbackYaw : resolveHorizontalYaw(direction, fallbackYaw),
                0.0, 0.0);
    }

    /**
     * 从方向提取 Minecraft 偏航角；竖直方向没有唯一偏航时保留调用方指定的备用角。
     *
     * @param direction 待解析的世界方向
     * @param fallbackYaw 水平分量退化时的备用偏航角
     * @return 水平投影对应的偏航角，单位度
     */
    public static double resolveHorizontalYaw(Vec3 direction, double fallbackYaw) {
        if (direction == null
                || !Double.isFinite(direction.x)
                || !Double.isFinite(direction.y)
                || !Double.isFinite(direction.z)) {
            return fallbackYaw;
        }
        double horizontal = Math.hypot(direction.x, direction.z);
        return horizontal < 1.0E-8
                ? fallbackYaw
                : Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    /**
     * 让仍处于竖直极点的机头沿本次有效指令选择脱离转弯平面。
     *
     * <p>竖直方向的欧拉偏航本来没有物理意义；如果继续沿用任意旧偏航，俯仰先离开
     * -90/90 度时会把该任意角转换成错误的水平速度分量。这里只在机头仍位于极点且
     * 指令存在水平投影时重锚定偏航，已经离开极点后不再干预连续惯量响应。</p>
     *
     * @param desiredDirection 本 Tick 有效的期望航迹方向
     * @return 修正极点方位后的状态
     */
    public RVP_AttackAngleState reanchorPoleYaw(Vec3 desiredDirection) {
        RVP_AttackAngleState current = sanitized();
        Vec3 body = current.direction();
        double bodyHorizontal = Math.hypot(body.x, body.z);
        if (bodyHorizontal >= 1.0E-4 || desiredDirection == null
                || !Double.isFinite(desiredDirection.x)
                || !Double.isFinite(desiredDirection.y)
                || !Double.isFinite(desiredDirection.z)) {
            return current;
        }
        double desiredHorizontal = Math.hypot(desiredDirection.x, desiredDirection.z);
        if (desiredHorizontal < 1.0E-8) {
            return current;
        }
        double yaw = resolveHorizontalYaw(desiredDirection, current.yawDeg());
        // 极点偏航换锚后清除旧偏航角速度，避免把旧的任意方位继续带入脱离平面。
        return new RVP_AttackAngleState(current.pitchDeg(), yaw,
                current.pitchRateDeg(), 0.0).sanitized();
    }

    /** 双精度重建机头轴，不引入滚转或 Minecraft 三角查表的累计误差。 */
    public Vec3 direction() {
        double pitch = Math.toRadians(pitchDeg);
        double yaw = Math.toRadians(yawDeg);
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch));
    }

    /** 外部恢复状态统一限幅；坏角速度回零，不允许污染下一 Tick。 */
    public RVP_AttackAngleState sanitized() {
        return new RVP_AttackAngleState(
                Double.isFinite(pitchDeg) ? Mth.clamp(pitchDeg, -90.0, 90.0) : 0.0,
                Double.isFinite(yawDeg) ? Mth.wrapDegrees(yawDeg) : 0.0,
                Double.isFinite(pitchRateDeg) ? Mth.clamp(pitchRateDeg, -90.0, 90.0) : 0.0,
                Double.isFinite(yawRateDeg) ? Mth.clamp(yawRateDeg, -90.0, 90.0) : 0.0);
    }
}
