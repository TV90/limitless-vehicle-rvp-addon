package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ArtilleryFireControlStateTest {

    @Test
    void exactHighAngleSolutionWinsOverMoreAccurateLowAngleSolution() {
        var low = sample(30.0D, 0.1D);
        var high = sample(55.0D, 1.0D);

        var selected = RVP_ArtilleryFireControlState.selectPreferred(
                List.of(low, high), 500.0D, true);

        assertEquals(55.0D, selected.elevationDeg());
    }

    @Test
    void mostAccurateHighAngleSolutionWinsWithinTolerance() {
        var lowHighAngle = sample(46.0D, 0.1D);
        var highHighAngle = sample(70.0D, 1.0D);

        var selected = RVP_ArtilleryFireControlState.selectPreferred(
                List.of(lowHighAngle, highHighAngle), 500.0D, true);

        assertEquals(46.0D, selected.elevationDeg());
    }

    @Test
    void lowTrajectoryModeSelectsLowestExactSolution() {
        var low = sample(15.0D, 1.0D);
        var high = sample(75.0D, 0.1D);

        var selected = RVP_ArtilleryFireControlState.selectPreferred(
                List.of(low, high), 500.0D, true,
                RVP_ArtilleryFireControlState.TrajectoryMode.LOW);

        assertEquals(15.0D, selected.elevationDeg());
    }

    @Test
    void noExactSolutionUsesSmallestMissDistance() {
        var low = sample(30.0D, 10.0D);
        var high = sample(55.0D, 12.0D);

        var selected = RVP_ArtilleryFireControlState.selectPreferred(
                List.of(low, high), 500.0D, true);

        assertEquals(30.0D, selected.elevationDeg());
    }

    @Test
    void unreachableYawCannotBeReportedAsPreferredExactSolution() {
        var low = sample(30.0D, 0.1D);
        var high = sample(55.0D, 1.0D);

        var selected = RVP_ArtilleryFireControlState.selectPreferred(
                List.of(low, high), 500.0D, false);

        assertEquals(30.0D, selected.elevationDeg());
    }

    @Test
    void beyondMaximumRangeUsesFarthestReachableTrajectory() {
        var shortLow = sample(21.0D, -120.0D, 120.0D);
        var maximumRange = sample(45.0D, -20.0D, 20.0D);
        var shortHigh = sample(70.0D, -80.0D, 80.0D);

        var selected = RVP_ArtilleryFireControlState.selectPreferred(
                List.of(shortLow, maximumRange, shortHigh), 1000.0D, true);

        assertEquals(45.0D, selected.elevationDeg());
    }

    @Test
    void largeYawErrorLocksAndCapturesCurrentPitch() {
        var gate = new RVP_ArtilleryFireControlState.PitchGate();

        var decision = gate.update(1L, true, true, 10.0F, 45.0F);

        assertTrue(decision.lockPitch());
        assertTrue(decision.capturePitch());
    }

    @Test
    void yawWrapAroundUsesShortestAngularError() {
        assertFalse(RVP_ArtilleryFireControlState.isYawReached(-179.0F, 179.0F));
        assertTrue(RVP_ArtilleryFireControlState.isYawReached(179.4F, 179.0F));
    }

    @Test
    void yawWithinHalfDegreeReleasesPitchAfterLatestSolutionArrives() {
        var gate = new RVP_ArtilleryFireControlState.PitchGate();
        gate.update(1L, true, false, 20.0F, 20.0F);

        var decision = gate.update(1L, true, true, 20.49F, 20.0F);

        assertFalse(decision.lockPitch());
        assertFalse(decision.capturePitch());
    }

    @Test
    void continuousTargetUpdatesDoNotRecapturePitchWhileLocked() {
        var gate = new RVP_ArtilleryFireControlState.PitchGate();
        var first = gate.update(1L, true, false, 0.0F, 30.0F);

        var movedAgain = gate.update(2L, true, false, 3.0F, 60.0F);

        assertTrue(first.capturePitch());
        assertTrue(movedAgain.lockPitch());
        assertFalse(movedAgain.capturePitch());
    }

    @Test
    void movingTargetAgainAfterReleaseCapturesNewActualPitch() {
        var gate = new RVP_ArtilleryFireControlState.PitchGate();
        gate.update(1L, true, false, 0.0F, 30.0F);
        gate.update(1L, true, true, 30.0F, 30.0F);

        var movedAgain = gate.update(2L, true, false, 30.0F, 60.0F);

        assertTrue(movedAgain.lockPitch());
        assertTrue(movedAgain.capturePitch());
    }

    @Test
    void latestSolutionIsRequiredEvenWhenYawAlreadyAligned() {
        var gate = new RVP_ArtilleryFireControlState.PitchGate();

        var waitingForSolve = gate.update(1L, true, false, 15.0F, 15.0F);

        assertTrue(waitingForSolve.lockPitch());
        assertTrue(waitingForSolve.capturePitch());
    }

    @Test
    void fixedYawMountBypassesPitchGate() {
        var gate = new RVP_ArtilleryFireControlState.PitchGate();

        var decision = gate.update(1L, false, false, 0.0F, 90.0F);

        assertFalse(decision.lockPitch());
        assertFalse(decision.capturePitch());
    }

    private static RVP_ArtilleryFireControlState.Sample sample(double elevation, double miss) {
        return sample(elevation, 0.0D, miss);
    }

    private static RVP_ArtilleryFireControlState.Sample sample(
            double elevation, double rangeResidual, double miss) {
        return new RVP_ArtilleryFireControlState.Sample(
                elevation, new Vec3(0, 1, 0), Vec3.ZERO, rangeResidual, miss);
    }
}
