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
    void proportionalNavigationPreservesSpeedWithoutInterceptPrediction() {
        Vec3 missilePos = Vec3.ZERO;
        Vec3 missileVelocity = new Vec3(1, 0, 0);
        Vec3 targetPos = new Vec3(10, 0, 10);
        Vec3 targetVelocity = new Vec3(0, 0, 0.2);

        Vec3 guided = RVP_GuidanceRuntimeMath.steerProportional(
                missilePos,
                missileVelocity,
                targetPos,
                targetVelocity,
                1.0,
                1f
        );

        assertEquals(1.0, guided.length(), EPSILON);
        assertNotEquals(missileVelocity, guided);
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

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }
}
