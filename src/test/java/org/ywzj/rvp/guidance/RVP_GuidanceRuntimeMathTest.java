package org.ywzj.rvp.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_Range;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RVP_GuidanceRuntimeMathTest {

    private static final double EPSILON = 1.0E-6;

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
    void topAttackApexUsesConfiguredHeightAtStraightPathMidpoint() {
        Vec3 aim = RVP_GuidanceRuntimeMath.computeTopAttackApex(
                Vec3.ZERO, new Vec3(200, 10, 0), 80f);

        assertVectorEquals(new Vec3(100, 90, 0), aim);
    }

    @Test
    void topAttackMidpointUsesInitialStraightPathNotTravelledPath() {
        Vec3 launch = new Vec3(0, 20, 0);
        Vec3 target = new Vec3(200, 10, 0);

        assertEquals(false, RVP_GuidanceRuntimeMath.hasPassedTopAttackMidpoint(
                new Vec3(99, 300, 80), launch, target));
        assertEquals(true, RVP_GuidanceRuntimeMath.hasPassedTopAttackMidpoint(
                new Vec3(101, 5, -80), launch, target));
    }

    @Test
    void topAttackApexIsCappedForShortRangeTargets() {
        Vec3 aim = RVP_GuidanceRuntimeMath.computeTopAttackApex(
                Vec3.ZERO, new Vec3(20, 10, 0), 1000f);

        assertVectorEquals(new Vec3(10, 30, 0), aim);
    }

    @Test
    void topAttackShortRangeEntersTerminalBeforeOvershooting() {
        assertEquals(true, RVP_GuidanceRuntimeMath.shouldEnterTopAttackTerminal(
                new Vec3(40, 120, 0),
                new Vec3(100, 0, 0),
                Vec3.ZERO,
                new Vec3(100, 0, 0),
                new Vec3(8, 6, 0),
                0.15f
        ));
    }

    @Test
    void topAttackLongRangeKeepsClimbingOutsideTurnInDistance() {
        assertEquals(false, RVP_GuidanceRuntimeMath.shouldEnterTopAttackTerminal(
                new Vec3(200, 300, 0),
                new Vec3(1000, 0, 0),
                Vec3.ZERO,
                new Vec3(1000, 0, 0),
                new Vec3(8, 6, 0),
                0.15f
        ));
    }

    @Test
    void topAttackTurnInDistanceGrowsForSlowTurningMissiles() {
        double agile = RVP_GuidanceRuntimeMath.resolveTopAttackTurnInDistance(10.0D, 0.5f);
        double sluggish = RVP_GuidanceRuntimeMath.resolveTopAttackTurnInDistance(10.0D, 0.15f);

        assertEquals(true, sluggish > agile);
    }

    @Test
    void topAttackTerminalBoostsTurningAtShortRange() {
        float factor = RVP_GuidanceRuntimeMath.resolveTopAttackTerminalTurningFactor(
                Vec3.ZERO, new Vec3(50, 0, 0), new Vec3(10, 0, 0), 0.15f);

        assertEquals(true, factor > 0.35f);
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

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }
}
