package org.ywzj.rvp.firesupport.server;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_FireSupportSpawnChunkLeaseManagerTest {
    @Test
    void allocationOnlySelectsNewTicketsWithinGlobalBudget() {
        assertEquals(List.of(1, 3), RVP_FireSupportSpawnChunkLeaseManager.selectNewTicketIndexes(
                List.of(true, false, true, false, false), 2, 1));
        assertEquals(List.of(), RVP_FireSupportSpawnChunkLeaseManager.selectNewTicketIndexes(
                List.of(false, false), 0, 0));
        assertTrue(RVP_FireSupportSpawnChunkLeaseManager.MAX_NEW_TICKETS_PER_TICK >= 16);
        assertTrue(RVP_FireSupportSpawnChunkLeaseManager.MAX_NEW_TICKETS_PER_TICK <= 32);
        assertEquals(1200, RVP_FireSupportSpawnChunkLeaseManager.MAX_WAIT_TICKS);
    }

    @Test
    void rotationChangesWhichWaitingLeaseWinsWhenBudgetIsSmall() {
        List<Boolean> waiting = List.of(false, false, false);
        assertEquals(List.of(0), RVP_FireSupportSpawnChunkLeaseManager.selectNewTicketIndexes(waiting, 1, 0));
        assertEquals(List.of(1), RVP_FireSupportSpawnChunkLeaseManager.selectNewTicketIndexes(waiting, 1, 1));
        assertEquals(List.of(2), RVP_FireSupportSpawnChunkLeaseManager.selectNewTicketIndexes(waiting, 1, 2));
    }
}
