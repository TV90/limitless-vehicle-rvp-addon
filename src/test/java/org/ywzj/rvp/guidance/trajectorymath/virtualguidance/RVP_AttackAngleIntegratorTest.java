package org.ywzj.rvp.guidance.trajectorymath.virtualguidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AttackAngleModel;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AttackAngleState;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_BallisticTrajectoryMath;

import static org.junit.jupiter.api.Assertions.*;

/** 虚拟中段的攻角、机头推力、姿态连续性与关闭兼容性回归。 */
class RVP_AttackAngleIntegratorTest {
    /** 无世界依赖的积分器。 */
    private final RVP_RvpTrajectoryIntegrator integrator = new RVP_RvpTrajectoryIntegrator();
    /** 向量误差阈值；姿态使用 Minecraft 单精度旋转换算。 */
    private static final double EPSILON = 1.0E-5;

    /** 调用本项目状态构造器，建立机头向 Z、速度也向 Z 的在途状态。 */
    private RVP_VirtualTrajectoryState state() {
        return new RVP_VirtualTrajectoryState(new Vec3(0, 100, 0), new Vec3(0, 0, 10),
                0f, 0f, 10, 100, 30, 1000, -1);
    }

    /** 调用本项目参数构造器，只开放本组测试需要变化的物理项。 */
    private RVP_VirtualTrajectoryParameters parameters(double cap, double drag, boolean constant,
                                                       boolean propulsion, double thrust, Double gs) {
        return new RVP_VirtualTrajectoryParameters(gs, 0.5f, true, 10, drag, 0, 1,
                100.0, true, constant, propulsion, 10, thrust, 100, 0,
                0, 1, 0, 0, 0, cap, 0);
    }

    /** 虚拟步骤必须与同参数纯模型一致，姿态保存机头而不是速度。 */
    @Test
    void virtualStepUsesSharedModelAndKeepsBodySeparate() {
        var state = state();
        var parameters = parameters(20, 0, false, false, 0, 10.0);
        var target = new Vec3(1000, 100, 0);
        // 调用本项目期望方向与共用求解器，构造独立参考结果。
        var desired = RVP_BallisticTrajectoryMath.resolveGPSCruiseDirection(state.position(), state.velocity(),
                target, parameters.cruiseAltitude(), parameters.aeroSteeringLimits());
        var expected = RVP_AttackAngleModel.solve(state.velocity(), new RVP_AttackAngleState(0, 0, 0, 0),
                desired, parameters.aeroSteeringLimits(), parameters.mass());
        // 调用本项目虚拟入口，验证不重复施加转向。
        var result = integrator.step(state, new RVP_VirtualGuidanceInput(target), parameters);
        assertFalse(result.invalid());
        assertEquals(expected.velocity(), result.state().velocity());
        assertEquals(expected.state().yawRateDeg(), result.state().yawRateDeg());
        Vec3 body = Vec3.directionFromRotation(result.state().xRot(), result.state().yRot());
        assertTrue(body.distanceTo(expected.bodyDirection()) < 0.001);
        assertTrue(RVP_BallisticTrajectoryMath.angleBetween(body, result.state().velocity()) > 0.01);
        assertEquals(state.position().add(expected.velocity()), result.state().position());
        assertEquals(31, result.state().flightTick());
        assertEquals(999, result.state().remainingLife());
        assertEquals(110, result.state().flightDistance(), EPSILON);
    }

    /** 推力沿机头；即使气动为 0 G，侧向推力仍应改变速度分量。 */
    @Test
    void thrustFollowsBodyAndZeroGravityRemainsZero() {
        var target = new RVP_VirtualGuidanceInput(new Vec3(1000, 100, 0));
        // 调用本项目虚拟积分器，隔离推力增量与气动转向。
        var coasting = integrator.step(state(), target, parameters(20, 0, false, true, 0, 0.0)).state();
        var burning = integrator.step(state(), target, parameters(20, 0, false, true, 10, 0.0)).state();
        assertEquals(0.0, coasting.velocity().x, EPSILON);
        assertEquals(0.0, burning.velocity().y, EPSILON);
        Vec3 acceleration = burning.velocity().subtract(coasting.velocity());
        assertEquals(1.0, acceleration.length(), EPSILON);
        assertTrue(acceleration.x > 0.0);
        assertTrue(acceleration.normalize().distanceTo(Vec3.directionFromRotation(burning.xRot(), burning.yRot())) < 0.001);
    }

    /** 载荷诱导阻力只扣一次；恒速明确豁免并维持峰值基准。 */
    @Test
    void inducedDragAppliedOnceAndConstantSpeedExempt() {
        var target = new RVP_VirtualGuidanceInput(new Vec3(1000, 100, 0));
        // 调用本项目虚拟积分器对比相同满载指令的能量结算。
        var drag = integrator.step(state(), target, parameters(20, 0.2, false, false, 0, 10.0));
        var constant = integrator.step(state(), target, parameters(20, 0.2, true, false, 0, 10.0));
        // 独立按首 Tick 的质量惯量计算攻角载荷，不能再假定大指令瞬间满载。
        double alpha = Math.toRadians(10.0 * (1.0 - Math.exp(-1.0 / 0.6)));
        double load = Math.sin(alpha) / Math.sin(Math.toRadians(20));
        assertEquals(10.0 * (1.0 - 0.2 * load * load), drag.state().velocity().length(), EPSILON);
        assertEquals(10.0, constant.state().velocity().length(), EPSILON);
    }

    /** 跨步传递状态会保留残余攻角；从速度重建姿态将给出不同结果。 */
    @Test
    void consecutiveStepsConsumePersistedBody() {
        var parameters = parameters(20, 0, false, false, 0, 1.0);
        // 调用本项目积分器，先偏转再模拟无固定目标的回正。
        var first = integrator.step(state(), new RVP_VirtualGuidanceInput(new Vec3(1000, 100, 0)), parameters).state();
        var second = integrator.step(first, new RVP_VirtualGuidanceInput(null), parameters).state();
        assertTrue(second.velocity().subtract(first.velocity()).length() > 0.001);
        // 调用本项目姿态换算器构造错误的对照组，防止未来恢复逻辑重新抹平攻角。
        var aligned = new RVP_VirtualTrajectoryState(first.position(), first.velocity(),
                RVP_BallisticTrajectoryMath.pitchFromVelocity(first.velocity()),
                RVP_BallisticTrajectoryMath.yawFromVelocity(first.velocity()), first.peakFlightSpeed(),
                first.flightDistance(), first.flightTick(), first.remainingLife(), first.secondPulseStartTick());
        var lost = integrator.step(aligned, new RVP_VirtualGuidanceInput(null), parameters).state();
        assertTrue(second.velocity().distanceTo(lost.velocity()) > 0.001);
        // 只清角速度、保持机头不变的对照，专门防止虚实交接丢掉惯量状态。
        var noRates = new RVP_VirtualTrajectoryState(first.position(), first.velocity(),
                first.xRot(), first.yRot(), first.peakFlightSpeed(), first.flightDistance(),
                first.flightTick(), first.remainingLife(), first.secondPulseStartTick());
        var reset = integrator.step(noRates, new RVP_VirtualGuidanceInput(null), parameters).state();
        assertNotEquals(second.yawRateDeg(), reset.yawRateDeg());
    }

    /** 连续百 Tick 验证虚拟编排与实体使用的纯模型一致，姿态仅在同步边界转为 float。 */
    @Test
    void continuousVirtualStepsMatchStatefulExecutor() {
        var state = state();
        var parameters = parameters(30, 0, false, false, 0, 10.0);
        var target = new Vec3(800, 180, 600);
        for (int tick = 0; tick < 100; tick++) {
            // 调用与实体相同的有状态入口，使用虚拟态当前机头和角速度构造参考单步。
            var desired = RVP_BallisticTrajectoryMath.resolveGPSCruiseDirection(state.position(), state.velocity(),
                    target, parameters.cruiseAltitude(), parameters.aeroSteeringLimits());
            var expected = RVP_AttackAngleModel.solve(state.velocity(),
                    new RVP_AttackAngleState(state.xRot(), state.yRot(), state.pitchRateDeg(), state.yawRateDeg()),
                    desired, parameters.aeroSteeringLimits(), parameters.mass());
            var actual = integrator.step(state, new RVP_VirtualGuidanceInput(target), parameters);
            assertFalse(actual.invalid());
            assertEquals(expected.velocity(), actual.state().velocity());
            assertEquals(expected.state().pitchRateDeg(), actual.state().pitchRateDeg());
            assertEquals(expected.state().yawRateDeg(), actual.state().yawRateDeg());
            assertEquals((float) expected.state().pitchDeg(), actual.state().xRot());
            assertEquals((float) expected.state().yawDeg(), actual.state().yRot());
            state = actual.state();
        }
    }

    /** PRESET 与 GPS 均直接把原始方向交给攻角模型，末段仍受攻角锥控制。 */
    @Test
    void presetDirectionAlsoUsesAttackAngleModel() {
        var parameters = parameters(20, 0, false, false, 0, 10.0);
        var preset = new RVP_VirtualPresetGuidance(Vec3.ZERO, 750, 25, 24, 24, 0.3, 1.5, 0.002, 0.01, 0.5, 0);
        var target = new Vec3(0, 0, 1);
        // 调用本项目 PRESET 原始方向生成器与模型，核验虚拟入口不重复裁决。
        var desired = RVP_BallisticTrajectoryMath.resolvePresetBallisticDirection(state().position(), state().velocity(),
                target, preset, parameters.aeroSteeringLimits());
        var expected = RVP_AttackAngleModel.solve(state().velocity(), new RVP_AttackAngleState(0, 0, 0, 0),
                desired, parameters.aeroSteeringLimits(), parameters.mass());
        var actual = integrator.step(state(), new RVP_VirtualGuidanceInput(target, preset), parameters);
        assertEquals(expected.velocity(), actual.state().velocity());
        assertTrue(actual.state().xRot() > 0);
    }

    /** 未提供新字段的 Java 调用与显式 0 度保持完全相同的原积分路径。 */
    @Test
    void omittedAngleAndZeroAngleAreIdentical() {
        var old = new RVP_VirtualTrajectoryParameters(10.0, 0.5f, true, 10, 0, 0, 1,
                100.0, true, false, false, 10, 0, 100, 0, 0, 1, 0, 0, 0);
        var target = new RVP_VirtualGuidanceInput(new Vec3(1000, 100, 0));
        // 调用本项目积分入口，逐字段对比默认关闭与显式关闭结果。
        assertEquals(integrator.step(state(), target, old),
                integrator.step(state(), target, parameters(0, 0, false, false, 0, 10.0)));
        var spinning = new RVP_VirtualTrajectoryState(state().position(), state().velocity(), 0, 0,
                10, 100, 30, 1000, -1, 5, -5);
        var disabled = integrator.step(spinning, target, old).state();
        assertEquals(0, disabled.pitchRateDeg());
        assertEquals(0, disabled.yawRateDeg());
    }

    /** NaN 速度必须标记无效，不能被模型安全退化掩盖为正常在途状态。 */
    @Test
    void invalidInputIsReported() {
        var bad = new RVP_VirtualTrajectoryState(Vec3.ZERO, new Vec3(Double.NaN, 0, 0),
                0, 0, 10, 10, 30, 100, -1);
        // 调用本项目积分入口验证现有管理器可接收到数值故障信号。
        assertTrue(integrator.step(bad, new RVP_VirtualGuidanceInput(new Vec3(1000, 100, 0)),
                parameters(20, 0, false, false, 0, 10.0)).invalid());
        var badRate = new RVP_VirtualTrajectoryState(state().position(), state().velocity(), 0, 0,
                10, 100, 30, 1000, -1, Double.NaN, 0);
        assertTrue(integrator.step(badRate, new RVP_VirtualGuidanceInput(null),
                parameters(20, 0, false, false, 0, 10.0)).invalid());
    }
}
