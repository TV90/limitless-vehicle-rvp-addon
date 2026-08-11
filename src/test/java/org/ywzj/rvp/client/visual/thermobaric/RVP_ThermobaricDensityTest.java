package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_ThermobaricDensityTest {
    @Test
    void densitySupportsZeroAndHasNoOnePointZeroInputCeiling() {
        assertEquals(0, RVP_ThermobaricEffectInstance.resolveDensityLimitedCount(200, 0.0F));
        assertEquals(100, RVP_ThermobaricEffectInstance.resolveDensityLimitedCount(200, 0.5F));
        assertEquals(200, RVP_ThermobaricEffectInstance.resolveDensityLimitedCount(200, 4.0F));
    }
}
