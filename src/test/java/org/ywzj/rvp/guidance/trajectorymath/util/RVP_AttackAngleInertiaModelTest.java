package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_ProjectileData;

import static org.junit.jupiter.api.Assertions.*;

/** 双轴有效惯量的解析值、状态记忆、约束与极点回归，不访问世界或客户端。 */
class RVP_AttackAngleInertiaModelTest {
    /** 静止执行器的机头姿态。 */
    private static final RVP_AttackAngleState REST = new RVP_AttackAngleState(0, 0, 0, 0);
    /** 水平初速度，格/Tick。 */
    private static final Vec3 VELOCITY = new Vec3(0, 0, 10);
    /** 显式 0 G 隔离姿态响应，防止速度方向变化影响惯量断言。 */
    private static final RVP_AeroSteeringLimits LIMITS =
            new RVP_AeroSteeringLimits(0.0, 0.5f, 10, 1, 0, 0, true, 60);

    /** 基准质量的离散解等于一阶指数响应，两轴均应独立推进。 */
    @Test
    void firstStepMatchesExponentialSolutionOnBothAxes() {
        var target = new RVP_AttackAngleState(10, 15, 0, 0);
        // 调用本项目无副作用执行器验证已知解析解，不复用被测增益计算期望值。
        var result = RVP_AttackAngleInertiaModel.step(VELOCITY, REST, target.direction(), LIMITS, 100);
        double gain = 1 - Math.exp(-1);
        assertEquals(5 * gain, result.pitchDeg(), 1e-9);
        assertEquals(7.5 * gain, result.yawDeg(), 1e-9);
        assertEquals(result.pitchDeg(), result.pitchRateDeg(), 1e-9);
        assertEquals(result.yawDeg(), result.yawRateDeg(), 1e-9);
        assertEquals(REST, new RVP_AttackAngleState(0, 0, 0, 0));
    }

    /** 相同指令下重弹响应慢；既有吨/kg 约定、无发动机回退和上下限一致。 */
    @Test
    void massChangesResponseAndFallbackIsFinite() {
        Vec3 target = new RVP_AttackAngleState(0, 20, 0, 0).direction();
        var light = RVP_AttackAngleInertiaModel.step(VELOCITY, REST, target, LIMITS, 10);
        var heavy = RVP_AttackAngleInertiaModel.step(VELOCITY, REST, target, LIMITS, 900);
        assertTrue(light.yawRateDeg() > heavy.yawRateDeg());
        assertEquals(0.6, RVP_AttackAngleInertiaModel.effectiveInertia(1));
        assertEquals(3.0, RVP_AttackAngleInertiaModel.effectiveInertia(1e6));
        assertEquals(1.0, RVP_AttackAngleInertiaModel.effectiveInertia(0.1));
        for (double bad : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(1.0, RVP_AttackAngleInertiaModel.effectiveInertia(bad));
        }
    }

    /** 冷发射、点火、燃料消耗和二脉冲滑翔均使用相同质量口径，不修改任何 JSON 文件。 */
    @Test
    void attitudeMassPreservesFuelBeforeIgnitionAndMatchesBurnSchedule() {
        var data = new Gson().fromJson("""
                {"has_rocket_engine":true,"mass":200,"fuel_mass":80,
                 "thrust":10,"motor_burn_time":40}
                """, RVP_ProjectileData.class);
        // 调用惯量质量纯解析入口，不构造实体，不触发点火。
        assertEquals(200, RVP_PropulsionMath.resolveAttitudeMass(data, -10));
        assertEquals(200, RVP_PropulsionMath.resolveAttitudeMass(data, 0));
        assertEquals(160, RVP_PropulsionMath.resolveAttitudeMass(data, 20));
        assertEquals(120, RVP_PropulsionMath.resolveAttitudeMass(data, 40));
        assertEquals(120, RVP_PropulsionMath.resolveAttitudeMass(data, 100));
        assertEquals(0, RVP_PropulsionMath.resolveAttitudeMass(new RVP_ProjectileData(), 100));
    }

    /** 反向指令不会瞬间抹除旧角速度，连续积分与每步清零必然不同。 */
    @Test
    void reversalMustFirstBrakeExistingAngularMotion() {
        var moving = new RVP_AttackAngleState(0, 10, 0, 8);
        Vec3 reverse = new RVP_AttackAngleState(0, 8, 0, 0).direction();
        var next = RVP_AttackAngleInertiaModel.step(VELOCITY, moving, reverse, LIMITS, 900);
        assertTrue(next.yawRateDeg() > 0);
        assertTrue(next.yawRateDeg() < 8);
        assertTrue(next.yawDeg() > 10);
        var lost = RVP_AttackAngleInertiaModel.step(VELOCITY,
                new RVP_AttackAngleState(0, 10, 0, 0), reverse, LIMITS, 900);
        assertTrue(lost.yawRateDeg() < 0);
    }

    /** ±180 度附近使用最短误差；竖直方向保留偏航，不凭空绕轴翻转。 */
    @Test
    void wrapsYawAndKeepsPoleHeading() {
        var state = new RVP_AttackAngleState(0, 179, 0, 0);
        var desired = new RVP_AttackAngleState(0, -179, 0, 0);
        var next = RVP_AttackAngleInertiaModel.step(state.direction().scale(10),
                state, desired.direction(), LIMITS, 100);
        assertTrue(next.yawRateDeg() > 0 && next.yawRateDeg() < 2);
        var pole = new RVP_AttackAngleState(-90, 123, 0, 0);
        var vertical = RVP_AttackAngleInertiaModel.step(new Vec3(0, 10, 0),
                pole, new Vec3(0, 1, 0), LIMITS, 100);
        assertEquals(123, vertical.yawDeg(), 1e-8);
        assertEquals(0, vertical.yawRateDeg(), 1e-8);
    }

    /** 三维圆锥硬限幅同时耗散边界角速度，极端残余姿态也不能产生 NaN。 */
    @Test
    void coneSaturationAbsorbsRatesAndZeroSpeedStopsActuator() {
        var bad = new RVP_AttackAngleState(0, 80, 0, 90);
        var result = RVP_AttackAngleModel.solve(VELOCITY, bad, VELOCITY.reverse(), LIMITS, 900);
        assertTrue(result.attackAngleRadians() <= Math.toRadians(60) + 1e-8);
        assertEquals(0, result.state().pitchRateDeg(), 1e-8);
        assertEquals(0, result.state().yawRateDeg(), 1e-8);
        var stopped = RVP_AttackAngleModel.solve(Vec3.ZERO, bad, VELOCITY, LIMITS, 900);
        assertEquals(Vec3.ZERO, stopped.velocity());
        assertEquals(0, stopped.state().pitchRateDeg());
        assertEquals(0, stopped.state().yawRateDeg());
    }

    /** 在锥边仅清理法向增长，另一轴的切向运动应保留。 */
    @Test
    void coneConstraintPreservesTangentialAngularRate() {
        var clipped = RVP_AttackAngleInertiaModel.constrainState(VELOCITY.normalize(),
                new RVP_AttackAngleState(0, 70, 2, 5), 60);
        assertEquals(60, clipped.yawDeg(), 1e-8);
        assertEquals(2, clipped.pitchRateDeg(), 1e-8);
        assertEquals(0, clipped.yawRateDeg(), 1e-8);
    }

    /** 丢锁改用速度轴后，在保留角速度的条件下仍应收敛，不靠每步清零伪造稳定性。 */
    @Test
    void coastingSettlesWithStateMemory() {
        var state = new RVP_AttackAngleState(15, -20, 3, -4);
        for (int tick = 0; tick < 150; tick++) {
            var next = RVP_AttackAngleModel.solve(VELOCITY, state, VELOCITY, LIMITS, 900);
            state = next.state();
            assertTrue(RVP_BallisticTrajectoryMath.isFinite(next.bodyDirection()));
        }
        assertEquals(0, state.pitchDeg(), 1e-6);
        assertEquals(0, state.yawDeg(), 1e-6);
        assertEquals(0, state.pitchRateDeg(), 1e-6);
        assertEquals(0, state.yawRateDeg(), 1e-6);
    }

    /** PIP 反解与正式执行必须使用完全相同的初始角速度，且不能修改输入。 */
    @Test
    void pipTrialAndForwardModelUseIdenticalState() {
        var input = new RVP_AttackAngleState(-5, 8, 1.2, -2.5);
        Vec3 target = new Vec3(1, 1, 10);
        var limits = new RVP_AeroSteeringLimits(10.0, 0.5f, 10, 1, 0, 0, true, 30);
        var result = RVP_PIPAttackAngleController.solve(VELOCITY, input, target, limits, null, 250);
        var actual = RVP_AttackAngleModel.solve(VELOCITY, input, result.bodyCommand(), limits, 250);
        assertEquals(result.steering(), actual);
        assertEquals(new RVP_AttackAngleState(-5, 8, 1.2, -2.5), input);
        assertEquals(result, RVP_PIPAttackAngleController.solve(VELOCITY, input, target, limits, null, 250));
    }

    /** 大质量首 Tick 不可能瞬间取得满攻角，PIP 必须真实报告可达性限制。 */
    @Test
    void pipCannotBypassInertiaForUnreachableRequests() {
        var limits = new RVP_AeroSteeringLimits(100.0, 0.5f, 10, 1, 0, 0, true, 30);
        var result = RVP_PIPAttackAngleController.solve(VELOCITY, REST, new Vec3(10, 0, 0),
                limits, null, 900);
        assertTrue(result.responseLimited());
        assertTrue(result.steering().attackAngleRadians() < Math.toRadians(10));
        assertTrue(result.steering().turnAngleRadians() < result.requestedTurnRadians());
    }
}
