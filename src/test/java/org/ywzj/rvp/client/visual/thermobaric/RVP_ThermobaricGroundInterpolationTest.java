package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricGroundInterpolationTest {
    @Test
    void finiteGroundSamplesUseLinearInterpolation() {
        assertEquals(66.0F,
                RVP_ThermobaricEffectInstance.interpolateGroundHeight(64.0F, 68.0F, 0.5F));
    }

    @Test
    void singleInvalidGroundSampleFallsBackToFiniteNeighbor() {
        assertEquals(68.0F,
                RVP_ThermobaricEffectInstance.interpolateGroundHeight(
                        Float.NaN, 68.0F, 0.25F));
        assertEquals(64.0F,
                RVP_ThermobaricEffectInstance.interpolateGroundHeight(
                        64.0F, Float.NaN, 0.75F));
    }

    @Test
    void twoInvalidGroundSamplesRemainInvalid() {
        assertTrue(Float.isNaN(RVP_ThermobaricEffectInstance.interpolateGroundHeight(
                Float.NaN, Float.NaN, 0.5F)));
    }
}
