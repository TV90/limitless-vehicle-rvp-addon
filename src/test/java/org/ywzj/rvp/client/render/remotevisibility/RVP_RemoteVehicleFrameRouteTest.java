package org.ywzj.rvp.client.render.remotevisibility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_RemoteVehicleFrameRouteTest {

    @Test
    void dhCompositeConsumesPreparedFrameOnlyOnce() {
        RVP_RemoteVehicleFrameRoute route = new RVP_RemoteVehicleFrameRoute();

        route.prepare(true);

        assertTrue(route.consumeDh());
        assertFalse(route.consumeDh());
        assertFalse(route.consumeCurrentPass());
        assertFalse(route.consumeLateFallback());
        assertEquals(RVP_RemoteVehicleFrameRoute.State.DH_COMPOSITED, route.state());
    }

    @Test
    void fallbackRoutesAreMutuallyExclusiveAndNewFrameResetsState() {
        RVP_RemoteVehicleFrameRoute route = new RVP_RemoteVehicleFrameRoute();

        route.prepare(true);
        assertTrue(route.consumeCurrentPass());
        assertFalse(route.consumeLateFallback());

        route.prepare(true);
        assertTrue(route.consumeLateFallback());
        assertFalse(route.consumeDh());
        assertEquals(RVP_RemoteVehicleFrameRoute.State.LATE_FALLBACK, route.state());
    }

    @Test
    void emptyPlanCannotBeConsumed() {
        RVP_RemoteVehicleFrameRoute route = new RVP_RemoteVehicleFrameRoute();

        route.prepare(false);

        assertFalse(route.consumeDh());
        assertFalse(route.consumeCurrentPass());
        assertFalse(route.consumeLateFallback());
        assertEquals(RVP_RemoteVehicleFrameRoute.State.EMPTY, route.state());
    }
}
