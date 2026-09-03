package org.ywzj.rvp.client.compat.distanthorizons.realvehicleprotect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_DhTrackedVehicleBudgetTest {
    @Test
    void selectsByContributionThenDistanceThenEntityId() {
        List<RVP_DhTrackedVehicleBudget.Candidate> selected = RVP_DhTrackedVehicleBudget.select(
                List.of(
                        new RVP_DhTrackedVehicleBudget.Candidate(9, 100.0D, 0.5D),
                        new RVP_DhTrackedVehicleBudget.Candidate(7, 25.0D, 0.5D),
                        new RVP_DhTrackedVehicleBudget.Candidate(3, 25.0D, 0.5D),
                        new RVP_DhTrackedVehicleBudget.Candidate(1, 400.0D, 1.0D)),
                3);

        assertEquals(List.of(1, 3, 7), selected.stream()
                .map(RVP_DhTrackedVehicleBudget.Candidate::entityId).toList());
    }

    @Test
    void ignoresInvalidCandidatesAndDoesNotMutateInput() {
        List<RVP_DhTrackedVehicleBudget.Candidate> input = List.of(
                new RVP_DhTrackedVehicleBudget.Candidate(2, Double.NaN, 1.0D),
                new RVP_DhTrackedVehicleBudget.Candidate(1, 4.0D, 0.5D));

        assertEquals(List.of(1), RVP_DhTrackedVehicleBudget.select(input, 8).stream()
                .map(RVP_DhTrackedVehicleBudget.Candidate::entityId).toList());
        assertEquals(2, input.size());
    }
}
