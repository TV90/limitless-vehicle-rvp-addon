package org.ywzj.rvp.guidance.trajectorymath.virtualguidance;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringLimits;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringModel;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringSolution;
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
    public static final int VERSION = 8;

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
        // 调用本项目虚拟参数投影，构造与实体链同口径的气动转向限制快照。
        RVP_AeroSteeringLimits aeroLimits = parameters.aeroSteeringLimits();
        RVP_AeroSteeringSolution steeringSolution;
        if (guidance.preset() != null && guidance.preset().cruiseAltitude() > 0.0) {
            // 调用本项目弹道数学工具，为 PRESET 弹道生成受动压与转角上限裁决的新速度。
            steeringSolution = RVP_BallisticTrajectoryMath.steerPresetBallistic(
                    state.position(), velocity, target, guidance.preset(),
                    aeroLimits);
        } else {
            // 调用本项目弹道数学工具，为固定 GPS 目标按同一气动预算生成高度闭环速度。
            steeringSolution = RVP_BallisticTrajectoryMath.steerGpsCruise(
                    state.position(), velocity, target, parameters.cruiseAltitude(),
                    aeroLimits);
        }

        double turnAngleRadians = steeringSolution.turnAngleRadians();
        // 调用本项目弹道数学工具，在转向后执行最终速率上下限钳制。
        velocity = RVP_BallisticTrajectoryMath.clampSpeed(
                steeringSolution.velocity(), parameters.minSpeed(), parameters.maxSpeed());
        if (parameters.aeroSteering() && !parameters.constantSpeed()
                && velocity.lengthSqr() > 1.0E-8) {
            double speed = velocity.length();
            // 调用本项目纯数学公式，在速度钳制后按 λ² 结算诱导阻力，避免被最高速率吸收。
            double inducedLoss = RVP_AeroSteeringModel.inducedDragLoss(
                    parameters.inducedDrag(), steeringSolution.loadFactor(), speed);
            if (inducedLoss > 0.0) {
                double minimumSpeed = Math.max(parameters.minSpeed(), 0.01);
                velocity = velocity.normalize().scale(Math.max(speed - inducedLoss, minimumSpeed));
            }
        }

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
