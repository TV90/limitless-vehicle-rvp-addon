package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_ClientConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_ThermobaricClientQualityTest {
    @Test
    void threeQualityLevelsHaveFixedDensityMultipliers() {
        assertEquals(0.50F, RVP_ClientConfig.ThermobaricQuality.LOW.densityMultiplier());
        assertEquals(0.75F, RVP_ClientConfig.ThermobaricQuality.MEDIUM.densityMultiplier());
        assertEquals(1.00F, RVP_ClientConfig.ThermobaricQuality.HIGH.densityMultiplier());
    }

    @Test
    void clientQualityOnlyReducesAuthorDensity() {
        assertEquals(0.25F,
                RVP_ThermobaricEffectInstance.resolveClientLimitedDensity(0.50F, 0.50F));
        assertEquals(0.50F,
                RVP_ThermobaricEffectInstance.resolveClientLimitedDensity(0.50F, 1.00F));
        assertEquals(0.50F,
                RVP_ThermobaricEffectInstance.resolveClientLimitedDensity(0.50F, 2.00F));
        assertEquals(0.00F,
                RVP_ThermobaricEffectInstance.resolveClientLimitedDensity(0.50F, 0.00F));
    }
}
