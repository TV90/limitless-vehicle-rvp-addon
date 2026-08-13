package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricScreenFeedbackTest {
    @Test
    void distanceFactorFallsLinearlyToZeroAtRange() {
        assertEquals(1.0F, RVP_ThermobaricScreenFeedback.resolveDistanceFactor(0.0D, 100.0D));
        assertEquals(0.5F, RVP_ThermobaricScreenFeedback.resolveDistanceFactor(50.0D, 100.0D));
        assertEquals(0.0F, RVP_ThermobaricScreenFeedback.resolveDistanceFactor(100.0D, 100.0D));
        assertEquals(0.0F, RVP_ThermobaricScreenFeedback.resolveDistanceFactor(10.0D, 0.0D));
    }

    @Test
    void progressSupportsImmediateFlashCatchUpAndFiniteLifetime() {
        assertEquals(0.0F, RVP_ThermobaricScreenFeedback.resolveProgress(100.0D, 100.0D, 10));
        assertEquals(0.4F, RVP_ThermobaricScreenFeedback.resolveProgress(104.0D, 100.0D, 10));
        assertEquals(1.0F, RVP_ThermobaricScreenFeedback.resolveProgress(112.0D, 100.0D, 10));
    }

    @Test
    void combinedShakeIsClampedToTwoDegreesPerAxis() {
        assertEquals(2.0F, RVP_ThermobaricScreenFeedback.clampShake(8.0F));
        assertEquals(-2.0F, RVP_ThermobaricScreenFeedback.clampShake(-8.0F));
        assertEquals(1.25F, RVP_ThermobaricScreenFeedback.clampShake(1.25F));
    }

    @Test
    void shakeOffsetsAreDeterministicFadeAndRemainBelowSinglePulseLimits() {
        RVP_ThermobaricScreenFeedback.ShakeOffset first =
                RVP_ThermobaricScreenFeedback.resolveShakeOffset(2.5D, 0.25F, 12345L, 0.8F);
        RVP_ThermobaricScreenFeedback.ShakeOffset repeated =
                RVP_ThermobaricScreenFeedback.resolveShakeOffset(2.5D, 0.25F, 12345L, 0.8F);
        RVP_ThermobaricScreenFeedback.ShakeOffset otherSeed =
                RVP_ThermobaricScreenFeedback.resolveShakeOffset(2.5D, 0.25F, 54321L, 0.8F);
        RVP_ThermobaricScreenFeedback.ShakeOffset ended =
                RVP_ThermobaricScreenFeedback.resolveShakeOffset(12.0D, 1.0F, 12345L, 0.8F);

        assertEquals(first, repeated);
        assertNotEquals(first, otherSeed);
        assertTrue(Math.abs(first.yaw()) <= 0.90F);
        assertTrue(Math.abs(first.pitch()) <= 0.70F);
        assertTrue(Math.abs(first.roll()) <= 0.50F);
        assertEquals(0.0F, ended.yaw(), 0.0F);
        assertEquals(0.0F, ended.pitch(), 0.0F);
        assertEquals(0.0F, ended.roll(), 0.0F);
    }
}
