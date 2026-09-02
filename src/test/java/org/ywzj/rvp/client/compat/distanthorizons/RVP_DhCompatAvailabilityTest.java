package org.ywzj.rvp.client.compat.distanthorizons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_DhCompatAvailabilityTest {

    @Test
    void permanentFailureReasonSurvivesAcrossFramesUntilBridgeBecomesReady() {
        RVP_DhCompatAvailability availability = new RVP_DhCompatAvailability();

        assertFalse(availability.isDepthCompositeReady());
        assertEquals(RVP_DhCompatAvailability.BRIDGE_INITIALIZING,
                availability.failureReasonForFrame());

        availability.updateApiVersion("6.1.0");
        availability.markUnavailable("DH_API_TOO_OLD");

        assertFalse(availability.isDepthCompositeReady());
        assertEquals("DH_API_TOO_OLD", availability.failureReasonForFrame());
        assertEquals("6.1.0", availability.apiVersion());

        availability.updateApiVersion("7.0.1");
        availability.markReady();

        assertTrue(availability.isDepthCompositeReady());
        assertEquals(RVP_DhCompatAvailability.NO_EVENT_THIS_FRAME,
                availability.failureReasonForFrame());
        assertEquals("7.0.1", availability.apiVersion());
    }

    @Test
    void blankFailureReasonDoesNotEraseInitializationState() {
        RVP_DhCompatAvailability availability = new RVP_DhCompatAvailability();

        availability.markUnavailable(" ");

        assertEquals(RVP_DhCompatAvailability.BRIDGE_INITIALIZING,
                availability.failureReasonForFrame());
    }
}
