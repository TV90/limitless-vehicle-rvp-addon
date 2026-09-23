package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_MachinegunLeadStateTest {

    @Test
    void displayInterpolationUsesAdjacentCompensatedControlSolutions() {
        RVP_LeadSolution previous = new RVP_LeadSolution(
                null,
                new Vec3(10.0D, 20.0D, 30.0D),
                new Vec3(20.0D, 25.0D, 30.0D),
                8.0D,
                0.2D,
                100.0D
        );
        RVP_LeadSolution current = new RVP_LeadSolution(
                null,
                new Vec3(14.0D, 20.0D, 30.0D),
                new Vec3(28.0D, 25.0D, 30.0D),
                10.0D,
                0.4D,
                120.0D
        );

        RVP_LeadSolution interpolated = RVP_MachinegunLeadState.interpolateSolution(previous, current, 0.5F);

        assertEquals(12.0D, interpolated.targetWorldPos().x, 1.0E-9D);
        assertEquals(24.0D, interpolated.leadWorldPos().x, 1.0E-9D);
        assertEquals(9.0D, interpolated.timeToImpact(), 1.0E-9D);
        assertEquals(0.3D, interpolated.missDistance(), 1.0E-6D);
        assertEquals(110.0D, interpolated.projectileTravelDistanceMeters(), 1.0E-9D);
    }

    @Test
    void fullPartialTickReturnsCurrentCompensatedControlPoint() {
        RVP_LeadSolution previous = new RVP_LeadSolution(
                null, Vec3.ZERO, new Vec3(5.0D, 0.0D, 0.0D), 4.0D, 0.0D, 40.0D);
        RVP_LeadSolution current = new RVP_LeadSolution(
                null, Vec3.ZERO, new Vec3(12.0D, 0.0D, 0.0D), 5.0D, 0.0D, 50.0D);

        RVP_LeadSolution interpolated = RVP_MachinegunLeadState.interpolateSolution(previous, current, 1.0F);

        assertEquals(current.leadWorldPos(), interpolated.leadWorldPos());
    }

    @Test
    void phaseCompensationPlacesControlPointAheadOfRawLead() {
        Vec3 previousSmoothed = Vec3.ZERO;
        Vec3 rawLead = new Vec3(10.0D, 0.0D, 0.0D);
        double alpha = 0.23D;
        Vec3 currentSmoothed = previousSmoothed.lerp(rawLead, alpha);

        Vec3 compensated = RVP_MachinegunLeadState.compensateSmoothedLead(
                previousSmoothed, currentSmoothed, alpha);

        assertEquals(2.3D, currentSmoothed.x, 1.0E-9D);
        assertEquals(11.155D, compensated.x, 1.0E-9D);
        assertEquals((rawLead.x - currentSmoothed.x) * 0.15D,
                compensated.x - rawLead.x, 1.0E-9D);
    }

    @Test
    void phaseCompensationAddsFifteenPercentOfTheoreticalLagAsAdvance() {
        Vec3 previousSmoothed = new Vec3(100.0D, 0.0D, 0.0D);
        Vec3 rawLead = new Vec3(110.0D, 0.0D, 0.0D);
        double alpha = 0.46D;
        Vec3 currentSmoothed = previousSmoothed.lerp(rawLead, alpha);

        Vec3 compensated = RVP_MachinegunLeadState.compensateSmoothedLead(
                previousSmoothed, currentSmoothed, alpha);

        assertEquals(104.6D, currentSmoothed.x, 1.0E-9D);
        assertEquals(110.81D, compensated.x, 1.0E-9D);
        assertEquals((rawLead.x - currentSmoothed.x) * 0.15D,
                compensated.x - rawLead.x, 1.0E-9D);
    }
}
