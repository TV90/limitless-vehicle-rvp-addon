package org.ywzj.rvp.virtualflight.trajectory;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeMath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RVP_RvpTrajectoryIntegratorTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void pursuitTurningFactorControlsThetaWithoutDegreeCap() {
        Vec3 current = new Vec3(10, 0, 0);
        Vec3 target = new Vec3(0, 0, 100);

        assertEquals(0.0, theta(current,
                RVP_RvpTrajectoryIntegrator.steerPursuit(current, target, 10, 0f)), EPSILON);
        assertEquals(Math.toRadians(45), theta(current,
                RVP_RvpTrajectoryIntegrator.steerPursuit(current, target, 10, 0.5f)), EPSILON);
        assertEquals(Math.toRadians(90), theta(current,
                RVP_RvpTrajectoryIntegrator.steerPursuit(current, target, 10, 1f)), EPSILON);
    }

    @Test
    void pursuitGoldenVectorMatchesCurrentEntityRvpMath() {
        Vec3 current = new Vec3(7.5, -0.25, 3.0);
        Vec3 toTarget = new Vec3(-100, 25, 350);
        double speed = current.length();
        for (float factor : new float[]{0f, 0.1f, 0.5f, 0.9f, 1f}) {
            Vec3 expected = RVP_GuidanceRuntimeMath.steerPursuit(current, toTarget, speed, factor);
            Vec3 actual = RVP_RvpTrajectoryIntegrator.steerPursuit(current, toTarget, speed, factor);
            assertEquals(expected.x, actual.x, EPSILON);
            assertEquals(expected.y, actual.y, EPSILON);
            assertEquals(expected.z, actual.z, EPSILON);
        }
    }

    @Test
    void oneStepAdvancesClocksAndPositionWithoutWorldAccess() {
        RVP_VirtualTrajectoryState initial = new RVP_VirtualTrajectoryState(
                Vec3.ZERO, new Vec3(4, 0, 0), 0, -90, 4, 12, 100, 50, -1);
        RVP_VirtualTrajectoryParameters parameters = new RVP_VirtualTrajectoryParameters(
                0.5f, true, false, 0, 0, 0, 0, 0, 1, 0,
                0, 0, null, 10, 0.15f);
        RVP_VirtualTrajectoryResult result = new RVP_RvpTrajectoryIntegrator().step(
                initial, new RVP_VirtualGuidanceInput(new Vec3(100, 0, 100)), parameters);

        assertFalse(result.invalid());
        assertEquals(101, result.state().flightTick());
        assertEquals(49, result.state().remainingLife());
        assertEquals(result.state().velocity(), result.state().position());
        assertEquals(initial.flightDistance() + result.state().velocity().length(),
                result.state().flightDistance(), EPSILON);
    }

    private static double theta(Vec3 a, Vec3 b) {
        return Math.acos(Math.max(-1.0, Math.min(1.0, a.normalize().dot(b.normalize()))));
    }
}
