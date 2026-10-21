package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;

/**
 * 服务器权威目标运动快照。
 *
 * <p>该快照把当前目标状态与短时恒加速度外推集中起来，供 PIP、PRESET 和虚拟中段共用。
 * 加速度不是目标真实物理模型，而是由相邻服务器 Tick 的速度差得到的局部估计；调用方必须
 * 对外推时间和预测置信度做限幅。</p>
 *
 * @param position 当前瞄准点世界坐标，单位格
 * @param velocity 当前目标速度，单位格/Tick
 * @param acceleration 当前估计目标加速度，单位格/Tick^2
 * @param confidence 加速度估计置信度，范围 0～1；0 表示只应使用恒速预测
 */
public record RVP_TargetMotionState(Vec3 position, Vec3 velocity, Vec3 acceleration,
                                    double confidence) {
    /** 无目标或历史不足时的安全快照。 */
    public static final RVP_TargetMotionState EMPTY =
            new RVP_TargetMotionState(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, 0.0);

    /** 目标位置的短时恒加速度外推上限，单位 Tick，防止异常加速度产生远距飞点。 */
    private static final double MAX_PREDICTION_TICKS = 120.0;

    /** 归一化有限值并限制加速度置信度，避免坏状态进入导引三角函数。 */
    public RVP_TargetMotionState sanitized() {
        Vec3 safePosition = finite(position) ? position : Vec3.ZERO;
        Vec3 safeVelocity = finite(velocity) ? velocity : Vec3.ZERO;
        Vec3 safeAcceleration = finite(acceleration) ? acceleration : Vec3.ZERO;
        double safeConfidence = Double.isFinite(confidence)
                ? Math.max(0.0, Math.min(1.0, confidence)) : 0.0;
        return new RVP_TargetMotionState(safePosition, safeVelocity, safeAcceleration, safeConfidence);
    }

    /**
     * 预测未来目标位置。
     *
     * @param ticks 从快照时刻开始的 Tick 数
     * @return 受置信度和时间窗限制的目标位置
     */
    public Vec3 predictPosition(double ticks) {
        RVP_TargetMotionState safe = sanitized();
        double t = Double.isFinite(ticks)
                ? Math.max(0.0, Math.min(MAX_PREDICTION_TICKS, ticks)) : 0.0;
        return safe.position.add(safe.velocity.scale(t))
                .add(safe.acceleration.scale(0.5 * safe.confidence * t * t));
    }

    /** 预测未来目标速度，供下一轮有限时域求解和诊断使用。 */
    public Vec3 predictVelocity(double ticks) {
        RVP_TargetMotionState safe = sanitized();
        double t = Double.isFinite(ticks)
                ? Math.max(0.0, Math.min(MAX_PREDICTION_TICKS, ticks)) : 0.0;
        return safe.velocity.add(safe.acceleration.scale(safe.confidence * t));
    }

    /** 返回已经乘过置信度的有效加速度，供不接受 confidence 参数的求解器使用。 */
    public Vec3 effectiveAcceleration() {
        RVP_TargetMotionState safe = sanitized();
        return safe.acceleration.scale(safe.confidence);
    }

    /** 仅替换参考位置，保留同一 Tick 采集的速度、加速度和置信度。 */
    public RVP_TargetMotionState withPosition(Vec3 newPosition) {
        return new RVP_TargetMotionState(newPosition, velocity, acceleration, confidence).sanitized();
    }

    /** 创建位置已知但没有运动历史的静止快照，供固定 GPS 点和丢锁回退复用。 */
    public static RVP_TargetMotionState stationary(Vec3 position) {
        return new RVP_TargetMotionState(position, Vec3.ZERO, Vec3.ZERO, 0.0).sanitized();
    }

    /** 判断向量三个分量均为有限值。 */
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x)
                && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
