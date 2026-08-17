package org.ywzj.rvp.client.render.remotevisibility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_RemoteVehicleRenderBudgetTest {
    @Test
    void fallbackLimitDoesNotConsumeSlotsNeededByLaterLodCandidates() {
        List<RVP_RemoteVehicleRenderBudget.Candidate> selected = RVP_RemoteVehicleRenderBudget.select(
                List.of(
                        candidate(1, 10.0D, true),
                        candidate(2, 9.0D, true),
                        candidate(3, 8.0D, false),
                        candidate(4, 7.0D, false)),
                3, 1);

        assertEquals(List.of(1, 3, 4), selected.stream().map(
                RVP_RemoteVehicleRenderBudget.Candidate::entityId).toList());
    }

    @Test
    void equalContributionUsesDistanceThenEntityIdForStableOrdering() {
        List<RVP_RemoteVehicleRenderBudget.Candidate> selected = RVP_RemoteVehicleRenderBudget.select(
                List.of(
                        new RVP_RemoteVehicleRenderBudget.Candidate(9, 400.0D, 1.0D, false),
                        new RVP_RemoteVehicleRenderBudget.Candidate(7, 100.0D, 1.0D, false),
                        new RVP_RemoteVehicleRenderBudget.Candidate(5, 100.0D, 1.0D, false)),
                3, 0);

        assertEquals(List.of(5, 7, 9), selected.stream().map(
                RVP_RemoteVehicleRenderBudget.Candidate::entityId).toList());
    }

    @Test
    void zeroTotalBudgetSelectsNothing() {
        assertEquals(List.of(), RVP_RemoteVehicleRenderBudget.select(
                List.of(candidate(1, 1.0D, false)), 0, 8));
    }

    /** 创建指定贡献和模型类型的测试候选。 */
    private static RVP_RemoteVehicleRenderBudget.Candidate candidate(
            int entityId, double contribution, boolean fallback) {
        return new RVP_RemoteVehicleRenderBudget.Candidate(entityId, 100.0D, contribution, fallback);
    }
}
