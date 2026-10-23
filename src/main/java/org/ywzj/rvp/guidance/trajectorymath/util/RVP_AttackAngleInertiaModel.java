package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 无滚转、双轴的有效惯量降阶执行器。没有几何尺寸/力矩数据，因此有效惯量是经验响应系数，
 * 不是 kg·m² 的刚体惯量；不模拟陀螺项、侧滑状态或六自由度动力学。
 */
public final class RVP_AttackAngleInertiaModel {
    /** 未配置有效质量时的基准质量，单位 kg。 */
    public static final double DEFAULT_MASS_KG = 100.0;
    /** 两轴共同的安全角速度上限，度/Tick，不代替气动 G 约束。 */
    private static final double MAX_RATE = 90.0;

    private RVP_AttackAngleInertiaModel() { }

    /** 沿用阻力层的质量约定：0～1 为吨，其他正数为 kg；缺失/坏值回退基准质量。 */
    public static double effectiveInertia(double mass) {
        double kg = !Double.isFinite(mass) || mass <= 0.0 ? DEFAULT_MASS_KG
                : mass < 1.0 ? mass * 1000.0 : mass;
        return Mth.clamp(Math.sqrt(kg / DEFAULT_MASS_KG), 0.6, 3.0);
    }

    /** 动压控制姿态权威，沿用气动层已有的动压下限，不新增配置。 */
    public static double response(double speed, RVP_AeroSteeringLimits limits) {
        // 调用原姿态响应与共用动压公式，显式 G 优先的规则保持不变。
        return RVP_AttackAngleModel.bodyResponse(limits)
                * Math.sqrt(RVP_AeroSteeringModel.resolveDynamicPressureFactor(speed, limits));
    }

    /** 指数松弛保证最小惯量小于 1 时仍无数值增益过冲。 */
    public static double rateResponse(double mass) {
        // 调用统一有效惯量公式，使前向推进和 PIP 反解严格同源。
        return -Math.expm1(-1.0 / effectiveInertia(mass));
    }

    /** 先限指令锥，再推进两轴角速度与姿态，最后施加姿态硬约束。 */
    public static RVP_AttackAngleState step(Vec3 velocity, RVP_AttackAngleState input, Vec3 desired,
                                            RVP_AeroSteeringLimits limits, double mass) {
        // 调用状态校验、锥裁剪和方向换算，所有中间值均不回写调用方。
        var state = input.sanitized();
        Vec3 axis = velocity.normalize();
        Vec3 command = constrain(axis, desired, limits.attackAngleLimitDeg());
        // 调用最近等价角表示，跨竖直方向的几度修正不能变成 180° 偏航。
        var target = state.nearestRotation(command);
        double authority = response(velocity.length(), limits);
        double gain = rateResponse(mass);
        double pitchError = target.pitchDeg() - state.pitchDeg();
        double yawError = Mth.wrapDegrees(target.yawDeg() - state.yawDeg());
        double pitchRate = advanceRate(state.pitchRateDeg(), pitchError, authority, gain);
        double yawRate = advanceRate(state.yawRateDeg(), yawError, authority, gain);
        double pitch = state.pitchDeg() + pitchRate;
        double yaw = Mth.wrapDegrees(state.yawDeg() + yawRate);
        // 恰好到达目标或越过目标时耗散角速度；反向指令下未到目标前仍保留惯性。
        if (pitchError != 0.0 && pitchRate == pitchError) pitchRate = 0.0;
        if (yawError != 0.0 && yawRate == yawError) yawRate = 0.0;
        // 调用极点折回，保持存储角合法；仅换坐标表示，不跳转机头方向或增加转向权限。
        var next = new RVP_AttackAngleState(pitch, yaw,
                pitchRate, yawRate).foldPole();
        // 调用共用夹角工具检测饱和，再移除朝锥外的角速度分量，保留边界切向响应。
        if (RVP_BallisticTrajectoryMath.angleBetween(axis, next.direction())
                > Math.toRadians(limits.attackAngleLimitDeg()) + 1.0E-10) {
            return constrainState(axis, next, limits.attackAngleLimitDeg());
        }
        return next;
    }

    /** 圆锥硬约束只吸收朝外的角速度，不无故抹掉沿锥面的另一轴运动。 */
    public static RVP_AttackAngleState constrainState(Vec3 axis, RVP_AttackAngleState state, double capDeg) {
        // 调用方向锥裁剪和姿态换算，先确定约束位置处的两轴切向基。
        var pose = RVP_AttackAngleState.fromDirection(constrain(axis, state.direction(), capDeg), state.yawDeg());
        double pitch = Math.toRadians(pose.pitchDeg());
        double yaw = Math.toRadians(pose.yawDeg());
        // 计算 axis·body 关于两个角度的导数；其变化为负意味着总攻角仍在向锥外增长。
        double pitchGradient = axis.dot(new Vec3(Math.sin(pitch) * Math.sin(yaw),
                -Math.cos(pitch), -Math.sin(pitch) * Math.cos(yaw)));
        double yawGradient = axis.dot(new Vec3(-Math.cos(pitch) * Math.cos(yaw),
                0, -Math.cos(pitch) * Math.sin(yaw)));
        double rateNormal = pitchGradient * state.pitchRateDeg() + yawGradient * state.yawRateDeg();
        double normalSquared = pitchGradient * pitchGradient + yawGradient * yawGradient;
        double pitchRate = state.pitchRateDeg();
        double yawRate = state.yawRateDeg();
        if (rateNormal < 0 && normalSquared > 1.0E-16) {
            pitchRate -= pitchGradient * rateNormal / normalSquared;
            yawRate -= yawGradient * rateNormal / normalSquared;
        }
        // 投影可能把分量转移到另一轴；等比例限速不会重新引入朝外分量。
        double scale = Math.max(1.0, Math.max(Math.abs(pitchRate), Math.abs(yawRate)) / MAX_RATE);
        return new RVP_AttackAngleState(pose.pitchDeg(), pose.yawDeg(), pitchRate / scale, yawRate / scale);
    }

    /** 各轴独立稳定松弛；跨过目标时耗散至静止，反向指令仍需先消除原有角速度。 */
    private static double advanceRate(double previous, double error, double authority, double gain) {
        double target = Mth.clamp(error * authority, -MAX_RATE, MAX_RATE);
        double rate = previous + (target - previous) * gain;
        return error != 0.0 && rate * error > 0.0 && Math.abs(rate) > Math.abs(error) ? error : rate;
    }

    /** 按速度轴限制总攻角，避免逐轴限幅在对角方向突破圆锥。 */
    public static Vec3 constrain(Vec3 axis, Vec3 desired, double capDeg) {
        // 调用共用球面算法，反向指令也有确定性旋转平面。
        double angle = RVP_BallisticTrajectoryMath.angleBetween(axis, desired);
        double cap = Math.toRadians(capDeg);
        return angle > cap ? RVP_BallisticTrajectoryMath.slerpDirection(axis, desired, cap / angle)
                : desired.normalize();
    }
}
