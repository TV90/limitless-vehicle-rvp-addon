package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_ThermobaricPressureSmokeLodTest {
    @Test
    void distanceLodUsesBoundedStableSampleCounts() {
        assertEquals(64, RVP_ThermobaricPressureSmokeLod.resolveRenderCount(64, 128.0D * 128.0D));
        assertEquals(40, RVP_ThermobaricPressureSmokeLod.resolveRenderCount(64, 129.0D * 129.0D));
        assertEquals(24, RVP_ThermobaricPressureSmokeLod.resolveRenderCount(64, 257.0D * 257.0D));
        assertEquals(12, RVP_ThermobaricPressureSmokeLod.resolveRenderCount(16, 257.0D * 257.0D));
    }
}
