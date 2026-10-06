package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

import static org.junit.jupiter.api.Assertions.*;

/** 攻角纯数学回归；不构造世界或客户端实体。 */
class RVP_AttackAngleModelTest {
    /** 常规向量误差阈值。 */
    private static final double EPSILON = 1.0E-8;
    /** 测试用沿世界 Z 的初速度，单位格/Tick。 */
    private static final Vec3 FORWARD = new Vec3(0.0, 0.0, 10.0);
    /** 测试用世界 X 方向指令。 */
    private static final Vec3 RIGHT = new Vec3(1.0, 0.0, 0.0);

    /** 调用本项目快照构造器，冻结不减载的测试预算。 */
    private RVP_AeroSteeringLimits limits(Double gs, float factor, double cap) {
        return new RVP_AeroSteeringLimits(gs, factor, 10.0, 1.0, 0.2, 0.0, true, cap);
    }

    /** 同轴直飞没有攻角、转向或诱导阻力。 */
    @Test
    void alignedFlightHasNoLoad() {
        // 调用本项目模型验证无输入偏差的稳态。
        var result = RVP_AttackAngleModel.solve(FORWARD, FORWARD, FORWARD, limits(10.0, 0.5f, 20.0));
        assertEquals(FORWARD, result.velocity());
        assertEquals(0.0, result.loadFactor(), EPSILON);
        assertEquals(0.0, result.turnAngleRadians(), EPSILON);
        // 调用本项目阻力公式，验证稳态不额外扣速。
        assertEquals(0.0, RVP_AeroSteeringModel.inducedDragLoss(0.2, result.loadFactor(), 10.0));
    }

    /** 大指令使机头抵达攻角锥，而速度仍受 G 预算限制，二者不可合并。 */
    @Test
    void saturationSeparatesBodyFromVelocityAndHonorsG() {
        // 调用本项目模型以及夹角工具，验证受控机头锥与实际速度的独立性。
        var result = RVP_AttackAngleModel.solve(FORWARD, FORWARD, RIGHT, limits(10.0, 0.5f, 20.0));
        assertEquals(Math.toRadians(20.0), result.attackAngleRadians(), EPSILON);
        assertEquals(1.0, result.loadFactor(), EPSILON);
        assertEquals(10.0, result.velocity().length(), EPSILON);
        assertEquals(10.0 * PhysicsEngine.G, result.velocity().subtract(FORWARD).length(), EPSILON);
        assertTrue(RVP_BallisticTrajectoryMath.angleBetween(result.bodyDirection(), result.velocity()) > 0.01);
    }

    /** 小攻角按照正弦占比计载荷，阻力继续遵循载荷平方。 */
    @Test
    void partialLoadUsesSineRatioAndSquaredDrag() {
        double angle = Math.toRadians(10.0);
        Vec3 desired = new Vec3(Math.sin(angle), 0.0, Math.cos(angle));
        // 调用本项目模型，显式 G 的 0.5 姿态响应将 10 度指令变为 5 度攻角。
        var result = RVP_AttackAngleModel.solve(FORWARD, FORWARD, desired, limits(10.0, 0.5f, 20.0));
        double expected = Math.sin(Math.toRadians(5.0)) / Math.sin(Math.toRadians(20.0));
        assertEquals(expected, result.loadFactor(), EPSILON);
        // 调用本项目诱导阻力公式，保证新的载荷定义仍只扣一次平方代价。
        assertEquals(2.0 * expected * expected,
                RVP_AeroSteeringModel.inducedDragLoss(0.2, result.loadFactor(), 10.0), EPSILON);
    }

    /** 密度和低速动压共同减载，但不改变机头控制锥。 */
    @Test
    void densityAndSpeedReduceAvailableAcceleration() {
        var dense = limits(10.0, 0.5f, 20.0);
        var thin = new RVP_AeroSteeringLimits(10.0, 0.5f, 10.0, 0.25, 0.0, 0.0, true, 20.0);
        // 调用本项目模型比较相同指令在不同动压下的响应。
        var high = RVP_AttackAngleModel.solve(FORWARD, FORWARD, RIGHT, dense);
        var low = RVP_AttackAngleModel.solve(FORWARD, FORWARD, RIGHT, thin);
        var slowVelocity = FORWARD.scale(0.5);
        var slow = RVP_AttackAngleModel.solve(slowVelocity, FORWARD, RIGHT, dense);
        assertEquals(high.velocity().subtract(FORWARD).length() * 0.25,
                low.velocity().subtract(FORWARD).length(), EPSILON);
        assertEquals(high.velocity().subtract(FORWARD).length() * 0.25,
                slow.velocity().subtract(slowVelocity).length(), EPSILON);
        assertEquals(high.bodyDirection(), low.bodyDirection());
    }

    /** 绝对角速率与最大 G 同时存在时取较紧限制。 */
    @Test
    void turnRateCapsAerodynamicTurn() {
        var limits = new RVP_AeroSteeringLimits(100.0, 0.5f, 10.0, 1.0, 0.0, 0.1, true, 20.0);
        // 调用本项目攻角模型验证附加角速率限制。
        var result = RVP_AttackAngleModel.solve(FORWARD, FORWARD, RIGHT, limits);
        assertEquals(Math.toRadians(0.1), result.turnAngleRadians(), EPSILON);
    }

    /** 显式 G 优先，不能因 turning_factor 为 1 而绕过；0 G 不产生气动转向。 */
    @Test
    void explicitGOverridesFactorIncludingZeroG() {
        // 调用本项目模型对比 factor 两端，显式 G 下响应必须一致。
        var zeroFactor = RVP_AttackAngleModel.solve(FORWARD, FORWARD, RIGHT, limits(10.0, 0f, 20.0));
        var fullFactor = RVP_AttackAngleModel.solve(FORWARD, FORWARD, RIGHT, limits(10.0, 1f, 20.0));
        assertEquals(zeroFactor, fullFactor);
        var zeroG = RVP_AttackAngleModel.solve(FORWARD, FORWARD, RIGHT, limits(0.0, 1f, 20.0));
        assertEquals(FORWARD, zeroG.velocity());
        assertTrue(zeroG.loadFactor() > 0.0);
    }

    /** 未配置 G 时，旧瞬转弹豁免攻角模型；普通插值弹仍开启。 */
    @Test
    void instantaneousFactorIsExemptOnlyWithoutG() {
        assertFalse(limits(null, 1f, 20.0).attackAngleEnabled());
        assertTrue(limits(null, 0.2f, 20.0).attackAngleEnabled());
        assertTrue(limits(10.0, 1f, 20.0).attackAngleEnabled());
        // 调用本项目模型，关闭门控不应偷偷改变速度。
        assertEquals(FORWARD, RVP_AttackAngleModel.solve(FORWARD, FORWARD, RIGHT,
                limits(null, 1f, 20.0)).velocity());
    }

    /** 反向、近零和坏方向均不能产生 NaN，零速也不能凭空加速。 */
    @Test
    void oppositeZeroAndInvalidInputsStayFinite() {
        // 调用本项目模型和有限值判据，覆盖球面插值奇点。
        var opposite = RVP_AttackAngleModel.solve(FORWARD, FORWARD, FORWARD.reverse(), limits(10.0, 0.5f, 20.0));
        assertTrue(RVP_BallisticTrajectoryMath.isFinite(opposite.velocity()));
        assertEquals(10.0, opposite.velocity().length(), EPSILON);
        assertTrue(opposite.attackAngleRadians() <= Math.toRadians(20.0) + EPSILON);
        var stopped = RVP_AttackAngleModel.solve(Vec3.ZERO, RIGHT, FORWARD, limits(10.0, 0.5f, 20.0));
        assertEquals(Vec3.ZERO, stopped.velocity());
        assertEquals(RIGHT, stopped.bodyDirection());
        var invalid = RVP_AttackAngleModel.solve(FORWARD, null, new Vec3(Double.NaN, 0, 0), limits(10.0, 0.5f, 20.0));
        assertEquals(FORWARD, invalid.velocity());
    }

    /** 丢锁后把当前速度作为指令，残留机头角逐 Tick 回正，不能瞬间归零。 */
    @Test
    void noTargetSettlesResidualAngleOverTime() {
        var limits = limits(1.0, 0.5f, 20.0);
        // 调用本项目模型生成一次机头偏转，再以当前速度作为无目标回正指令。
        var initial = RVP_AttackAngleModel.solve(FORWARD, FORWARD, RIGHT, limits);
        var next = RVP_AttackAngleModel.solve(initial.velocity(), initial.bodyDirection(), initial.velocity(), limits);
        assertTrue(next.loadFactor() > 0.0);
        assertTrue(next.loadFactor() < initial.loadFactor());
        double previous = next.loadFactor();
        for (int tick = 0; tick < 15; tick++) {
            next = RVP_AttackAngleModel.solve(next.velocity(), next.bodyDirection(), next.velocity(), limits);
            assertTrue(next.loadFactor() <= previous + EPSILON);
            previous = next.loadFactor();
        }
        assertTrue(previous < 0.001);
    }

    /** 外力/干扰形成的超限角按满载记账，不能因超过 90 度而变成负载荷。 */
    @Test
    void forcedDeflectionSaturatesLoad() {
        // 调用本项目载荷测量，覆盖干扰强制旋转速度的独立入口。
        assertEquals(1.0, RVP_AttackAngleModel.loadFactor(FORWARD, FORWARD.reverse(), 20.0), EPSILON);
        assertEquals(0.0, RVP_AttackAngleModel.loadFactor(Vec3.ZERO, RIGHT, 20.0));
        assertEquals(0.0, RVP_AttackAngleModel.loadFactor(FORWARD, RIGHT, 90.0));
    }
}
