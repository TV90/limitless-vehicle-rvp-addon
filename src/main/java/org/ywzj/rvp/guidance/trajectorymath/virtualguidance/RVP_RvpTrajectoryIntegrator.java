package org.ywzj.rvp.guidance.trajectorymath.virtualguidance;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringLimits;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringModel;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringSolution;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_BallisticTrajectoryMath;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AttackAngleModel;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AttackAngleSolution;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_PropulsionMath;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_QuadraticAirDrag;

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
    public static final int VERSION = 9;

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
        // 调用本项目气动参数门控，只有显式启用攻角时才改变原积分顺序和姿态语义。
        if (parameters.aeroSteeringLimits().attackAngleEnabled()) {
            return stepAttackAngle(state, guidance, parameters);
        }
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
                steeringSolution.velocity(), parameters.minSpeed(), (float)(parameters.maxSpeed() * parameters.altitudeMaxSpeedFactor()));
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

    /** 攻角分支：制导推进机头和法向速度，然后沿机头推力、阻力、重力，最后平移。 */
    private RVP_VirtualTrajectoryResult stepAttackAngle(RVP_VirtualTrajectoryState state,
            RVP_VirtualGuidanceInput guidance, RVP_VirtualTrajectoryParameters parameters) {
        int tick = state.flightTick() + 1;
        Vec3 velocity = state.velocity();
        // 调用本项目快照投影及方向生成器，只生成期望方向，不提前对速度执行第二次转向。
        RVP_AeroSteeringLimits limits = parameters.aeroSteeringLimits();
        Vec3 desired = guidance.preset() != null && guidance.preset().cruiseAltitude() > 0.0
                ? RVP_BallisticTrajectoryMath.resolvePresetBallisticDirection(state.position(), velocity,
                        guidance.fixedTargetPosition(), guidance.preset(), limits)
                : RVP_BallisticTrajectoryMath.resolveGPSCruiseDirection(state.position(), velocity,
                        guidance.fixedTargetPosition(), parameters.cruiseAltitude(), limits);
        Vec3 body = Vec3.directionFromRotation(state.xRot(), state.yRot());
        // 调用本项目共用攻角模型，把已持久化的机头轴作为当前姿态，不从速度重建。
        RVP_AttackAngleSolution steering = RVP_AttackAngleModel.solve(velocity, body, desired, limits);
        velocity = steering.velocity();
        if (parameters.propulsion()) {
            if (tick >= parameters.ignitionTick()) {
                boolean burning = tick - parameters.ignitionTick() <= parameters.motorBurnTime();
                // 调用本项目推进和二次阻力公式；推力沿独立机头轴，重力零值保持零。
                velocity = RVP_PropulsionMath.applyThrust(velocity, steering.bodyDirection(),
                        new RVP_PropulsionMath.MotorState(burning, parameters.thrust(), parameters.mass()), 1.0);
                velocity = RVP_QuadraticAirDrag.apply(velocity, parameters.dragCoefficient(),
                        parameters.mass(), parameters.altitudeDragFactor());
                velocity = velocity.add(0.0, parameters.gravity(), 0.0);
            }
        } else {
            velocity = velocity.add(0.0, parameters.gravity(), 0.0);
            double drag = parameters.linearDrag() * parameters.altitudeDragFactor();
            double speed = velocity.length();
            if (drag > 0.0 && speed > 1.0E-6) {
                velocity = new Vec3(velocity.x - velocity.x / speed * drag,
                        velocity.y, velocity.z - velocity.z / speed * drag);
            }
        }
        // 调用本项目速度限制与诱导阻力公式，在所有转向/推力之后只结算一次能量代价。
        velocity = RVP_BallisticTrajectoryMath.clampSpeed(velocity, parameters.minSpeed(), (float)(parameters.maxSpeed() * parameters.altitudeMaxSpeedFactor()));
        double speed = velocity.length();
        if (speed > 1.0E-8) {
            if (parameters.constantSpeed()) {
                velocity = velocity.normalize().scale(Math.max(state.peakFlightSpeed(), speed));
                velocity = RVP_BallisticTrajectoryMath.clampSpeed(velocity, parameters.minSpeed(), (float)(parameters.maxSpeed() * parameters.altitudeMaxSpeedFactor()));
            } else {
                double loss = RVP_AeroSteeringModel.inducedDragLoss(parameters.inducedDrag(), steering.loadFactor(), speed);
                // 阻力下限不可把已经低于下限的速度反向加速。
                double floor = Math.min(speed, Math.max(parameters.minSpeed(), 0.01));
                velocity = velocity.scale(Math.max(speed - loss, floor) / speed);
            }
        }
        // 调用本项目角度换算，以机体轴保存姿态，使实体恢复后保留攻角。
        RVP_VirtualTrajectoryState next = new RVP_VirtualTrajectoryState(state.position().add(velocity), velocity,
                RVP_BallisticTrajectoryMath.pitchFromVelocity(steering.bodyDirection()),
                RVP_BallisticTrajectoryMath.yawFromVelocity(steering.bodyDirection()),
                Math.max(state.peakFlightSpeed(), velocity.length()), state.flightDistance() + velocity.length(),
                tick, state.remainingLife() - 1, state.secondPulseStartTick());
        // 调用本项目有限值检查，坏状态仍由现有虚拟飞行管理器终止，不静默修复在途记录。
        boolean finite = RVP_BallisticTrajectoryMath.isFinite(next.position())
                && RVP_BallisticTrajectoryMath.isFinite(next.velocity())
                && Float.isFinite(next.xRot()) && Float.isFinite(next.yRot())
                && Double.isFinite(next.peakFlightSpeed()) && Double.isFinite(next.flightDistance())
                && RVP_BallisticTrajectoryMath.isFinite(state.velocity())
                && Float.isFinite(state.xRot()) && Float.isFinite(state.yRot());
        return new RVP_VirtualTrajectoryResult(next, steering.turnAngleRadians(), !finite);
    }
}
