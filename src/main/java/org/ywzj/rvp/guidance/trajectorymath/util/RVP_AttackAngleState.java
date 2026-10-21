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
                horizontal < 1.0E-8 ? fallbackYaw : Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                0.0, 0.0);
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
