package org.ywzj.rvp.client.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ClientZoomStateTest {
    @Test
    void modelRenderingPreferenceRequiresAllScopeZoomConditions() {
        assertTrue(RVP_ClientZoomState.shouldPreferRemoteVehicleModelRendering(
                true, true, true, true));
        assertFalse(RVP_ClientZoomState.shouldPreferRemoteVehicleModelRendering(
                false, true, true, true));
        assertFalse(RVP_ClientZoomState.shouldPreferRemoteVehicleModelRendering(
                true, false, true, true));
        assertFalse(RVP_ClientZoomState.shouldPreferRemoteVehicleModelRendering(
                true, true, false, true));
        assertFalse(RVP_ClientZoomState.shouldPreferRemoteVehicleModelRendering(
                true, true, true, false));
    }

    @Test
    void finalFovUsesExistingZoomRatioWithoutLodEnableDependency() {
        assertTrue(RVP_ClientZoomState.isFovZoomed(50.0D, 70.0D, 0.75D));
        assertFalse(RVP_ClientZoomState.isFovZoomed(52.5D, 70.0D, 0.75D));
        assertFalse(RVP_ClientZoomState.isFovZoomed(Double.NaN, 70.0D, 0.75D));
        assertFalse(RVP_ClientZoomState.isFovZoomed(0.0D, 70.0D, 0.75D));
        assertFalse(RVP_ClientZoomState.isFovZoomed(40.0D, 0.0D, 0.75D));
    }
}
