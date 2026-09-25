package org.ywzj.rvp.client.lead;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

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
        Vec3 filtered = RVP_MachinegunLeadSolver.combineAndFilterTargetVelocity(
                new Vec3(0.004D, 0.0D, -0.003D),
                new Vec3(0.006D, 0.0D, -0.002D),
                false
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
    void airborneVelocityEstimatorFavorsRealizedMovement() {
        Vec3 filtered = RVP_MachinegunLeadSolver.combineAndFilterTargetVelocity(
                new Vec3(0.20D, 0.0D, 0.0D),
                new Vec3(0.10D, 0.0D, 0.0D),
                false
        );

        assertEquals(0.175D, filtered.x, 1.0E-9D);
        assertEquals(0.0D, filtered.y, 1.0E-9D);
        assertEquals(0.0D, filtered.z, 1.0E-9D);
    }

    @Test
    void groundedVelocityIgnoresPhysicsVerticalMotion() {
        Vec3 filtered = RVP_MachinegunLeadSolver.combineAndFilterTargetVelocity(
                new Vec3(0.80D, 0.02D, -0.40D),
                new Vec3(0.75D, -0.60D, -0.35D),
                true
        );

        assertEquals(0.80D, filtered.x, 1.0E-9D);
        assertEquals(0.02D, filtered.y, 1.0E-9D);
        assertEquals(-0.40D, filtered.z, 1.0E-9D);
    }

    @Test
    void groundedRealizedVerticalVelocityIsClamped() {
        Vec3 filtered = RVP_MachinegunLeadSolver.combineAndFilterTargetVelocity(
                new Vec3(0.50D, 0.80D, 0.0D),
                Vec3.ZERO,
                true
        );

        assertEquals(0.25D, filtered.y, 1.0E-9D);
    }

    @Test
    void positionHistoryProducesVelocityWithoutWorldPointLag() {
        Deque<RVP_MachinegunLeadSolver.PositionSample> samples = new ArrayDeque<>();
        samples.addLast(new RVP_MachinegunLeadSolver.PositionSample(20, new Vec3(10.0D, 64.0D, 3.0D)));
        samples.addLast(new RVP_MachinegunLeadSolver.PositionSample(24, new Vec3(18.0D, 64.4D, -1.0D)));

        Vec3 velocity = RVP_MachinegunLeadSolver.estimateRealizedVelocity(samples);

        assertEquals(2.0D, velocity.x, 1.0E-9D);
        assertEquals(0.1D, velocity.y, 1.0E-9D);
        assertEquals(-1.0D, velocity.z, 1.0E-9D);
    }

    @Test
    void broadcastBufferDelayIsAddedOnlyToPredictionBase() {
        Vec3 displayedCenter = new Vec3(100.0D, 80.0D, -25.0D);
        Vec3 velocity = new Vec3(1.5D, -0.2D, 0.5D);

        Vec3 compensated = RVP_MachinegunLeadSolver.compensateBufferedTargetPosition(
                displayedCenter,
                velocity,
                5.0D
        );

        assertEquals(107.5D, compensated.x, 1.0E-9D);
        assertEquals(79.0D, compensated.y, 1.0E-9D);
        assertEquals(-22.5D, compensated.z, 1.0E-9D);
        assertEquals(new Vec3(100.0D, 80.0D, -25.0D), displayedCenter);
    }

    @Test
    void negativeBroadcastDelayCannotMovePredictionBackwards() {
        Vec3 center = new Vec3(20.0D, 30.0D, 40.0D);

        Vec3 compensated = RVP_MachinegunLeadSolver.compensateBufferedTargetPosition(
                center,
                new Vec3(3.0D, 0.0D, 0.0D),
                -5.0D
        );

        assertEquals(center, compensated);
    }
}
