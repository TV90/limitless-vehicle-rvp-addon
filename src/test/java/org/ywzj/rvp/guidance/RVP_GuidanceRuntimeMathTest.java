package org.ywzj.rvp.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_BallisticTrajectoryMath;
import org.ywzj.rvp.weapon.data.RVP_Range;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class RVP_GuidanceRuntimeMathTest {

    private static final double EPSILON = 1.0E-6;

    /** 无攻角兼容入口仍按 factor 控制航迹，只有明确不可解的 PIP 才返回纯追踪回退信号。 */
    @Test
    void predictiveInterceptPreservesDirectionBlendAndSignalsUnreachableTarget() {
        Vec3 velocity = new Vec3(5.5, 0, 0);
        // 调用本项目公开 PIP 入口验证 factor=0 保持速度，factor=1 指向真实前置点。
        assertVectorEquals(velocity, RVP_GuidanceRuntimeMath.steerPredictiveIntercept(
                null, Vec3.ZERO, velocity, new Vec3(300, 0, 0), new Vec3(0, 0, 2), 5.5, 0));
        Vec3 full = RVP_GuidanceRuntimeMath.steerPredictiveIntercept(
                null, Vec3.ZERO, velocity, new Vec3(300, 0, 0), new Vec3(0, 0, 2), 5.5, 1);
        assertEquals(5.5, full.length(), EPSILON);
        assertEquals(2.0, full.z, EPSILON);
        // 调用同一入口验证更快且远离的目标不再获得虚假的有限预测点。
        assertNull(RVP_GuidanceRuntimeMath.steerPredictiveIntercept(
                null, Vec3.ZERO, velocity, new Vec3(300, 0, 0), new Vec3(6, 0, 0), 5.5, 1));
    }

    @Test
    void pursuitTurningFactorOwnsTheDirectionBlend() {
        Vec3 current = new Vec3(1, 0, 0);
        Vec3 target = new Vec3(0, 0, 10);

        Vec3 noTurn = RVP_GuidanceRuntimeMath.steerPursuit(current, target, 1.0, 0f);
        Vec3 fullTurn = RVP_GuidanceRuntimeMath.steerPursuit(current, target, 1.0, 1f);

        assertVectorEquals(new Vec3(1, 0, 0), noTurn);
        assertVectorEquals(new Vec3(0, 0, 1), fullTurn);
    }

    @Test
    void scanRadiusUsesFiniteEnvelopeOrBoundedFallback() {
        RVP_Range<Float> finite = RVP_Range.of(
                RVP_Range.interval(0f, 100f),
                RVP_Range.interval(200f, 300f)
        );
        RVP_Range<Float> unbounded = RVP_Range.of(
                RVP_Range.interval(0f, 100f),
                RVP_Range.interval(200f, null)
        );

        assertEquals(300.0, RVP_GuidanceRuntimeGeometry.resolveScanRadius(finite));
        assertEquals(512.0, RVP_GuidanceRuntimeGeometry.resolveScanRadius(unbounded));
        assertEquals(512.0, RVP_GuidanceRuntimeGeometry.resolveScanRadius(null));
    }

    @Test
    void angleLimitsAreSingleSidedAtRuntime() {
        Vec3 axis = new Vec3(0, 0, 1);
        Vec3 fortyFiveDegrees = new Vec3(1, 0, 1);

        assertEquals(true, RVP_GuidanceRuntimeGeometry.withinAngle(axis, fortyFiveDegrees, 45));
        assertEquals(false, RVP_GuidanceRuntimeGeometry.withinAngle(axis, fortyFiveDegrees, 44.9));
    }

    @Test
    void topAttackAimSitsConfiguredHeightAboveTargetAtLongRange() {
        // 水平距离 200 > H=80：瞄准点恒在目标上空 80 处，导弹持续爬升
        Vec3 aim = RVP_GuidanceRuntimeMath.resolveTopAttackAimPoint(
                new Vec3(0, 10, 0), new Vec3(200, 10, 0), 80f);

        assertVectorEquals(new Vec3(200, 90, 0), aim);
    }

    @Test
    void topAttackAimHeightShrinksWithHorizontalDistanceWhenCloser() {
        // 水平距离 30 < H=100：瞄准点高度收缩为 30，导弹自然越顶俯冲
        Vec3 aim = RVP_GuidanceRuntimeMath.resolveTopAttackAimPoint(
                new Vec3(0, 50, 0), new Vec3(30, 10, 0), 100f);

        assertVectorEquals(new Vec3(30, 40, 0), aim);
    }

    @Test
    void topAttackNegativeHeightAimsBelowTargetForLowApproach() {
        // 负 H：瞄准点在目标下方，支持低空上升逼近
        Vec3 aim = RVP_GuidanceRuntimeMath.resolveTopAttackAimPoint(
                new Vec3(0, 10, 0), new Vec3(200, 60, 0), -40f);

        assertVectorEquals(new Vec3(200, 20, 0), aim);
    }

    @Test
    void topAttackAimFallsBackToTargetForInvalidHeight() {
        assertEquals(new Vec3(200, 10, 0),
                RVP_GuidanceRuntimeMath.resolveTopAttackAimPoint(new Vec3(0, 10, 0), new Vec3(200, 10, 0), 0f));
        assertEquals(new Vec3(200, 10, 0),
                RVP_GuidanceRuntimeMath.resolveTopAttackAimPoint(new Vec3(0, 10, 0), new Vec3(200, 10, 0), null));
    }

    @Test
    void gpsCruiseSteersHorizontallyAndLevelsPositiveClimb() {
        Vec3 guided = RVP_GuidanceRuntimeMath.steerGpsCruise(
                new Vec3(1, 1, 0),
                new Vec3(0, -20, 100),
                1.5,
                0.5f,
                0.25f,
                false
        );

        assertEquals(0.5, guided.x, EPSILON);
        assertEquals(0.75, guided.y, EPSILON);
        assertEquals(0.5, guided.z, EPSILON);
    }

    @Test
    void gpsCruiseResetsVerticalVelocityOnlyOnEntry() {
        Vec3 guided = RVP_GuidanceRuntimeMath.steerGpsCruise(
                new Vec3(1, -0.4, 0),
                new Vec3(100, -20, 0),
                1.0,
                0.2f,
                0.25f,
                true
        );

        assertEquals(0.0, guided.y, EPSILON);
    }

    /**
     * 验证实体 PRESET 在冷发射结束后的首个追踪点仍带有上升分量，
     * 防止前视水平点把垂发导弹先拉成一小段平飞。
     */
    @Test
    void presetArcUsesLookAheadHeightForColdLaunchHandoff() {
        RVP_PresetBallisticProfile preset = new RVP_PresetBallisticProfile(
                750.0f, 25.0f, 24.0f, 24.0f, 0.3f, 1.5f,
                0.002f, 0.01f, 0.5f, 0.0f);

        Vec3 guided = RVP_GuidanceRuntimeMath.steerPresetBallisticArc(
                new Vec3(0.0, 2.0, 0.0),
                new Vec3(0.0, 40.0, 0.0),
                new Vec3(2000.0, 0.0, 0.0),
                new Vec3(0.0, 40.0, 0.0),
                preset,
                2.0,
                1.0f);

        assertTrue(guided.y > 0.0,
                "cold-launch handoff must follow the rising side of the PRESET arc");
    }

    /** 抛物线必须从实际点火高度出发，并在末端收敛到目标高度，不能保留固定基线偏移。 */
    @Test
    void presetArcUsesActualIgnitionAndTargetHeights() {
        assertEquals(40.0, RVP_BallisticTrajectoryMath.samplePresetArcHeight(
                40.0, 0.0, 750.0, 0.0), EPSILON);
        assertEquals(0.0, RVP_BallisticTrajectoryMath.samplePresetArcHeight(
                40.0, 0.0, 750.0, 1.0), EPSILON);
        assertEquals(770.0, RVP_BallisticTrajectoryMath.samplePresetArcHeight(
                40.0, 0.0, 750.0, 0.5), EPSILON);
    }

    /** 当前弹体高于低追点时，保持原水平前视并沿抛物线正常斜率抬高追点，避免被压成平飞。 */
    @Test
    void presetArcCompensatesAheadWhenProjectileIsAboveCurrentPoint() {
        RVP_BallisticTrajectoryMath.PresetLookAhead plan =
                RVP_BallisticTrajectoryMath.resolvePresetHeightLookAhead(
                        400.0, 0.0, 0.0, 0.10, 4000.0, 60.0, 750.0);

        assertTrue(plan.compensated(), "高度超前时必须启用未来追点补偿");
        assertEquals(60.0, plan.lookAhead(), EPSILON,
                "高度补偿不得扩大水平前视距离");
        assertTrue(plan.targetY() > 400.0, "补偿后的追点高度必须高于当前弹体");
        assertTrue(Math.atan2(plan.targetY() - 400.0, plan.lookAhead())
                        > Math.toRadians(10.0),
                "补偿后的追点仍必须保留明显爬升角");
    }

    /** 当前弹体没有超过追点高度时，补偿器不得改变原有前视距离。 */
    @Test
    void presetArcDoesNotCompensateWhenProjectileIsBelowCurrentPoint() {
        RVP_BallisticTrajectoryMath.PresetLookAhead plan =
                RVP_BallisticTrajectoryMath.resolvePresetHeightLookAhead(
                        100.0, 0.0, 0.0, 0.10, 4000.0, 60.0, 750.0);

        assertTrue(!plan.compensated(), "未超过当前追点时不应提前改变路线");
        assertEquals(60.0, plan.lookAhead(), EPSILON);
    }

    /** 实体抛物线追点在高度超前时仍应保留爬升指令，而不是被低追点拉平。 */
    @Test
    void presetArcSteeringKeepsClimbAfterHeightCompensation() {
        RVP_PresetBallisticProfile preset = new RVP_PresetBallisticProfile(
                750.0f, 60.0f, 24.0f, 24.0f, 0.3f, 1.5f,
                0.002f, 0.01f, 0.5f, 0.0f);

        Vec3 guided = RVP_GuidanceRuntimeMath.steerPresetBallisticArc(
                new Vec3(0.0, 400.0, 0.0),
                new Vec3(400.0, 400.0, 0.0),
                new Vec3(4000.0, 0.0, 0.0),
                Vec3.ZERO,
                preset,
                11.0,
                1.0f);

        assertTrue(guided.y > 0.0,
                "高度超前补偿后，期望速度仍应指向未来上升追点");
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }
}
