package org.ywzj.rvp.radar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ClientRadarTickHandlerTest {

    @Test
    void localEntityWinsWhenEnteringNativeTrackingRange() {
        assertEquals(
                RVP_ClientRadarTickHandler.EntityRepresentationSource.LOCAL,
                RVP_ClientRadarTickHandler.chooseRepresentationSource(true, true)
        );
    }

    @Test
    void freshBroadcastCloneTakesOverWhenLeavingNativeTrackingRange() {
        assertEquals(
                RVP_ClientRadarTickHandler.EntityRepresentationSource.BROADCAST,
                RVP_ClientRadarTickHandler.chooseRepresentationSource(false, true)
        );
    }

    @Test
    void missingReplacementCannotResurrectLock() {
        assertEquals(
                RVP_ClientRadarTickHandler.EntityRepresentationSource.NONE,
                RVP_ClientRadarTickHandler.chooseRepresentationSource(false, false)
        );
    }

    @Test
    void broadcastFreshnessAllowsTwoIntervalsOnly() {
        assertTrue(RVP_ClientRadarTickHandler.isFreshBroadcastSample(110, 100, 5));
        assertFalse(RVP_ClientRadarTickHandler.isFreshBroadcastSample(111, 100, 5));
        assertFalse(RVP_ClientRadarTickHandler.isFreshBroadcastSample(100, null, 5));
        assertFalse(RVP_ClientRadarTickHandler.isFreshBroadcastSample(99, 100, 5));
    }
}
