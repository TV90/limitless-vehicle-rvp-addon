package org.ywzj.rvp.client.lead;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RVP_MachinegunLeadSolverTest {

    @Test
    void stationaryTargetKeepsBoundingBoxCenterAsPredictionAnchor() {
        Vec3 center = new Vec3(120.0D, 64.0D, -30.0D);

        Vec3 predicted = RVP_MachinegunLeadSolver.predictTargetPosition(center, Vec3.ZERO, 40.0D);

        assertEquals(center, predicted);
    }

    @Test
    void nearZeroPhysicsJitterIsTreatedAsStationary() {
        Vec3 filtered = RVP_MachinegunLeadSolver.blendAndFilterTargetVelocity(
                new Vec3(0.004D, 0.0D, -0.003D),
                new Vec3(0.006D, 0.0D, -0.002D)
        );

        assertSame(Vec3.ZERO, filtered);
    }

    @Test
    void lowSpeedLeadIsOnlyVelocityTimesFlightTime() {
        Vec3 center = new Vec3(10.0D, 20.0D, 30.0D);
        Vec3 velocity = new Vec3(0.10D, 0.0D, 0.0D);

        Vec3 predicted = RVP_MachinegunLeadSolver.predictTargetPosition(center, velocity, 20.0D);

        assertEquals(12.0D, predicted.x, 1.0E-9D);
        assertEquals(20.0D, predicted.y, 1.0E-9D);
        assertEquals(30.0D, predicted.z, 1.0E-9D);
    }

    @Test
    void velocityEstimatorKeepsExistingDeltaMovementWeightingAboveDeadband() {
        Vec3 filtered = RVP_MachinegunLeadSolver.blendAndFilterTargetVelocity(
                new Vec3(0.20D, 0.0D, 0.0D),
                new Vec3(0.10D, 0.0D, 0.0D)
        );

        assertEquals(0.135D, filtered.x, 1.0E-9D);
        assertEquals(0.0D, filtered.y, 1.0E-9D);
        assertEquals(0.0D, filtered.z, 1.0E-9D);
    }
}
