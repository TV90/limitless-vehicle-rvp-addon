package org.ywzj.rvp.guidance.trajectorymath.virtualguidance;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_BallisticTrajectoryMath;

/**
 * 从 RVP 实体制导/运动链抽出的纯虚拟弹道 Tick 编排器。
 *
 * <p>本类只负责按固定顺序组合物理推进、制导、速度限制、姿态派生和状态推进；无状态的
 * 数值计算集中由 {@link RVP_BallisticTrajectoryMath} 提供，便于实体弹道、预测器和测试复用。
 * 实现不访问世界、实体和区块。</p>
 */
public final class RVP_RvpTrajectoryIntegrator implements RVP_VirtualTrajectoryIntegrator {
    /** 当前 RVP 纯数学弹道实现的稳定标识，用于校验持久化状态兼容性。 */
    public static final String ID = "rvp_current";
    /** 当前 RVP 纯数学弹道状态版本，用于阻止不兼容状态继续积分。 */
    public static final int VERSION = 6;

    /** {@inheritDoc} */
    @Override
    public String implementationId() {
        return ID;
    }

    /** {@inheritDoc} */
    @Override
    public int implementationVersion() {
        return VERSION;
    }

    /** {@inheritDoc} */
    @Override
    public RVP_VirtualTrajectoryResult step(RVP_VirtualTrajectoryState state,
                                             RVP_VirtualGuidanceInput guidance,
                                             RVP_VirtualTrajectoryParameters parameters) {
        int tick = state.flightTick() + 1;

        // 调用本项目弹道数学工具，依次施加点火后的推力、空气阻力和重力。
        Vec3 velocity = RVP_BallisticTrajectoryMath.integrateForces(
                state.velocity(),
                tick,
                parameters.ignitionTick(),
                parameters.propulsion(),
                parameters.motorBurnTime(),
                parameters.thrust(),
                parameters.mass(),
                parameters.dragCoefficient(),
                parameters.altitudeDragFactor(),
                parameters.gravity());

        Vec3 target = guidance.fixedTargetPosition();
        Vec3 steered;
        if (guidance.preset() != null && guidance.preset().cruiseAltitude() > 0.0) {
            // 调用本项目弹道数学工具，为 PRESET 弹道生成受最大 G 值限制的新速度。
            steered = RVP_BallisticTrajectoryMath.steerPresetBallistic(
                    state.position(), velocity, target, guidance.preset(), parameters.maxGs());
        } else {
            // 调用本项目弹道数学工具，为固定 GPS 目标生成高度闭环和末端可达的新速度。
            steered = RVP_BallisticTrajectoryMath.steerGpsCruise(
                    state.position(), velocity, target, parameters.cruiseAltitude(), parameters.maxGs());
        }

        // 调用本项目弹道数学工具，记录实际转角并执行最终速率上下限钳制。
        double turnAngleRadians = RVP_BallisticTrajectoryMath.angleBetween(velocity, steered);
        velocity = RVP_BallisticTrajectoryMath.clampSpeed(
                steered, parameters.minSpeed(), parameters.maxSpeed());

        float xRot = state.xRot();
        float yRot = state.yRot();
        if (velocity.lengthSqr() > 1.0E-8) {
            // 调用本项目弹道数学工具，只由权威速度派生恢复实体所需姿态。
            xRot = RVP_BallisticTrajectoryMath.pitchFromVelocity(velocity);
            yRot = RVP_BallisticTrajectoryMath.yawFromVelocity(velocity);
        }

        Vec3 position = state.position().add(velocity);
        RVP_VirtualTrajectoryState next = new RVP_VirtualTrajectoryState(
                position,
                velocity,
                xRot,
                yRot,
                Math.max(state.peakFlightSpeed(), velocity.length()),
                state.flightDistance() + velocity.length(),
                tick,
                state.remainingLife() - 1,
                state.secondPulseStartTick());
        // 调用本项目弹道数学工具，隔离含 NaN 或 Infinity 的位置与速度结果。
        boolean finite = RVP_BallisticTrajectoryMath.isFinite(next.position())
                && RVP_BallisticTrajectoryMath.isFinite(next.velocity())
                && Float.isFinite(next.xRot())
                && Float.isFinite(next.yRot())
                && Double.isFinite(next.peakFlightSpeed())
                && Double.isFinite(next.flightDistance());
        return new RVP_VirtualTrajectoryResult(next, turnAngleRadians, !finite);
    }
}
