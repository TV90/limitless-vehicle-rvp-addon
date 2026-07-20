package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static RVP_ArtilleryFireControlState.Sample sample(double elevation, double miss) {
        return new RVP_ArtilleryFireControlState.Sample(
                elevation, new Vec3(0, 1, 0), Vec3.ZERO, 0.0D, miss);
    }
}
