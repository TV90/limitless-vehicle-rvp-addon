package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricVisualCurveTest {
    @Test
    void cloudColorTransitionUsesConfiguredAbsoluteTicks() {
        assertEquals(0.0F, RVP_ThermobaricRenderer.resolveColorTransitionProgress(7.0F, 8, 35));
        assertEquals(0.5F, RVP_ThermobaricRenderer.resolveColorTransitionProgress(21.5F, 8, 35));
        assertEquals(1.0F, RVP_ThermobaricRenderer.resolveColorTransitionProgress(35.0F, 8, 35));
        assertEquals(1.0F, RVP_ThermobaricRenderer.resolveColorTransitionProgress(8.0F, 8, 8));
    }

    @Test
    void fireballGrayFrontMovesFromOuterLayerTowardCenter() {
        float outerProgress = RVP_ThermobaricRenderer.outerToInnerGrayProgress(0.5F, 1.0F);
        float middleProgress = RVP_ThermobaricRenderer.outerToInnerGrayProgress(0.5F, 0.5F);
        float centerProgress = RVP_ThermobaricRenderer.outerToInnerGrayProgress(0.5F, 0.0F);

        assertTrue(outerProgress > middleProgress);
        assertTrue(middleProgress > centerProgress);
        assertEquals(1.0F,
                RVP_ThermobaricRenderer.outerToInnerGrayProgress(1.0F, 0.0F));
    }

    @Test
    void cloudRiseFactorScalesWholeLifetimeMotionHeight() {
        float lowFactorHeight = RVP_ThermobaricRenderer.resolveCloudRiseHeight(
                8.0F, 1.0F, 1.0F);
        float fiveRadiusHeight = RVP_ThermobaricRenderer.resolveCloudRiseHeight(
                8.0F, 5.0F, 1.0F);
        float cappedFiveRadiusHeight = RVP_ThermobaricRenderer.resolveCloudRiseHeight(
                8.0F, 5.0F, 2.0F);

        assertEquals(8.0F, lowFactorHeight);
        assertEquals(40.0F, fiveRadiusHeight);
        assertEquals(40.0F, cappedFiveRadiusHeight);
        assertTrue(Float.isFinite(RVP_ThermobaricRenderer.resolveCloudRiseHeight(
                8.0F, Float.MAX_VALUE, 1.0F)));
    }

    @Test
    void cloudFadeSpreadsLinearlyFromOuterLayerTowardCenter() {
        float outerQuarter = RVP_ThermobaricRenderer.resolveOuterToInnerCloudFadeProgress(
                0.25F, 1.10F);
        float middleQuarter = RVP_ThermobaricRenderer.resolveOuterToInnerCloudFadeProgress(
                0.25F, 0.55F);
        float innerQuarter = RVP_ThermobaricRenderer.resolveOuterToInnerCloudFadeProgress(
                0.25F, 0.0F);

        assertEquals(0.25F, outerQuarter);
        assertTrue(outerQuarter > middleQuarter);
        assertEquals(0.0F, middleQuarter);
        assertEquals(0.0F, innerQuarter);
        assertEquals(0.5F, RVP_ThermobaricRenderer.resolveOuterToInnerCloudFadeProgress(
                0.5F, 1.10F));
        assertEquals(0.25F, RVP_ThermobaricRenderer.resolveOuterToInnerCloudFadeProgress(
                0.52F, 0.55F), 1.0E-6F);
        assertEquals(0.50F, RVP_ThermobaricRenderer.resolveOuterToInnerCloudFadeProgress(
                0.68F, 0.55F), 1.0E-6F);
        assertEquals(0.75F, RVP_ThermobaricRenderer.resolveOuterToInnerCloudFadeProgress(
                0.84F, 0.55F), 1.0E-6F);
        assertEquals(1.0F, RVP_ThermobaricRenderer.resolveOuterToInnerCloudFadeProgress(
                1.0F, 0.0F));
    }

    @Test
    void dustRingThicknessDecreasesLinearlyAcrossWholeLifetime() {
        float start = RVP_ThermobaricRenderer.resolveDustThicknessFactor(0.0F);
        float middle = RVP_ThermobaricRenderer.resolveDustThicknessFactor(0.5F);
        float end = RVP_ThermobaricRenderer.resolveDustThicknessFactor(1.0F);

        assertTrue(start > middle);
        assertTrue(middle > end);
        assertEquals((start + end) * 0.5F, middle, 1.0E-6F);
    }

    @Test
    void dustRingKeepsSameRadialSpeedAcrossFullTick() {
        float earlyStep = RVP_ThermobaricRenderer.resolveDustRadialProgress(11.0F, 3, 36)
                - RVP_ThermobaricRenderer.resolveDustRadialProgress(10.0F, 3, 36);
        float beforeFullStep = RVP_ThermobaricRenderer.resolveDustRadialProgress(36.0F, 3, 36)
                - RVP_ThermobaricRenderer.resolveDustRadialProgress(35.0F, 3, 36);
        float afterFullStep = RVP_ThermobaricRenderer.resolveDustRadialProgress(37.0F, 3, 36)
                - RVP_ThermobaricRenderer.resolveDustRadialProgress(36.0F, 3, 36);
        float lateStep = RVP_ThermobaricRenderer.resolveDustRadialProgress(68.0F, 3, 36)
                - RVP_ThermobaricRenderer.resolveDustRadialProgress(67.0F, 3, 36);

        assertEquals(earlyStep, beforeFullStep, 1.0E-6F);
        assertEquals(earlyStep, afterFullStep, 1.0E-6F);
        assertEquals(earlyStep, lateStep, 1.0E-6F);
        assertEquals(1.0F,
                RVP_ThermobaricRenderer.resolveDustRadialProgress(36.0F, 3, 36));
        assertEquals(2.0F,
                RVP_ThermobaricRenderer.resolveDustRadialProgress(69.0F, 3, 36));
        assertEquals(0.0F,
                RVP_ThermobaricRenderer.resolveDustRadialProgress(40.0F, 40, 40));
    }

    @Test
    void pressureWaveKeepsSameRadialSpeedAcrossFullTick() {
        float earlyStep = RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(18.0F, 10, 30)
                - RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(17.0F, 10, 30);
        float beforeFullStep = RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(30.0F, 10, 30)
                - RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(29.0F, 10, 30);
        float afterFullStep = RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(31.0F, 10, 30)
                - RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(30.0F, 10, 30);
        float lateStep = RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(49.0F, 10, 30)
                - RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(48.0F, 10, 30);

        assertEquals(earlyStep, beforeFullStep, 1.0E-6F);
        assertEquals(earlyStep, afterFullStep, 1.0E-6F);
        assertEquals(earlyStep, lateStep, 1.0E-6F);
        assertEquals(1.0F,
                RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(30.0F, 10, 30));
        assertEquals(2.0F,
                RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(50.0F, 10, 30));
        assertEquals(0.0F,
                RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(40.0F, 40, 40));
    }

    @Test
    void condensationParticleSpawnThicknessConvergesToOriginalFullThickness() {
        float visualRadius = 10.0F;

        assertEquals(12.5F,
                RVP_ThermobaricRenderer.resolveCondensationParticleWallThickness(
                        visualRadius, 0.0F, 1.0F), 1.0E-6F);
        assertEquals(25.0F,
                RVP_ThermobaricRenderer.resolveCondensationParticleWallThickness(
                        visualRadius, 0.0F, 2.0F), 1.0E-6F);
        assertEquals(0.0F,
                RVP_ThermobaricRenderer.resolveCondensationParticleWallThickness(
                        visualRadius, 0.0F, 0.0F), 1.0E-6F);
        assertEquals(1.2F,
                RVP_ThermobaricRenderer.resolveCondensationParticleWallThickness(
                        visualRadius, 1.0F, 0.0F), 1.0E-6F);
        assertEquals(1.2F,
                RVP_ThermobaricRenderer.resolveCondensationParticleWallThickness(
                        visualRadius, 1.0F, 1.0F), 1.0E-6F);
        assertEquals(1.2F,
                RVP_ThermobaricRenderer.resolveCondensationParticleWallThickness(
                        visualRadius, 2.0F, 8.0F), 1.0E-6F);
    }

    @Test
    void pressureWaveFadeSpeedFactorControlsLinearFade() {
        assertEquals(1.0F,
                RVP_ThermobaricRenderer.resolvePressureWaveFadeAlpha(
                        40.0F, 30.0F, 50.0F, 0.0F));
        assertEquals(1.0F,
                RVP_ThermobaricRenderer.resolvePressureWaveFadeAlpha(
                        50.0F, 30.0F, 50.0F, 0.0F));
        assertEquals(0.5F,
                RVP_ThermobaricRenderer.resolvePressureWaveFadeAlpha(
                        40.0F, 30.0F, 50.0F, 1.0F));
        assertEquals(0.0F,
                RVP_ThermobaricRenderer.resolvePressureWaveFadeAlpha(
                        40.0F, 30.0F, 50.0F, 2.0F));
        assertEquals(0.0F,
                RVP_ThermobaricRenderer.resolvePressureWaveFadeAlpha(
                        50.0F, 30.0F, 50.0F, 1.0F));
        assertEquals(0.75F,
                RVP_ThermobaricRenderer.resolvePressureWaveFadeAlpha(
                        40.0F, 30.0F, 50.0F, 0.5F));
    }

    @Test
    void condensationCutSpeedOnlyScalesCutProgress() {
        assertEquals(0.0F,
                RVP_ThermobaricRenderer.resolveCondensationCutProgress(40.0F, 30.0F, 50.0F, 0.0F));
        assertEquals(0.25F,
                RVP_ThermobaricRenderer.resolveCondensationCutProgress(40.0F, 30.0F, 50.0F, 0.5F));
        assertEquals(0.5F,
                RVP_ThermobaricRenderer.resolveCondensationCutProgress(40.0F, 30.0F, 50.0F, 1.0F));
        assertEquals(1.0F,
                RVP_ThermobaricRenderer.resolveCondensationCutProgress(40.0F, 30.0F, 50.0F, 2.0F));
        assertEquals(1.5F,
                RVP_ThermobaricRenderer.resolvePressureWaveRadialProgress(40.0F, 10, 30));
        assertEquals(0.5F,
                RVP_ThermobaricRenderer.resolvePressureWaveFadeAlpha(
                        40.0F, 30.0F, 50.0F, 1.0F));
    }

    @Test
    void earlyEffectEndDoesNotRescalePressureCurves() {
        float age = 35.0F;
        float naturalEndTick = 50.0F;

        assertEquals(0.75F,
                RVP_ThermobaricRenderer.resolvePressureWaveFadeAlpha(
                        age, 30.0F, naturalEndTick, 1.0F));
        assertEquals(0.25F,
                RVP_ThermobaricRenderer.resolveCondensationCutProgress(
                        age, 30.0F, naturalEndTick, 1.0F));
    }

    @Test
    void dustRingStartsFadingAtFullTickAndHonorsEarlyEffectEnd() {
        assertEquals(1.0F, RVP_ThermobaricRenderer.resolveDustFadeAlpha(36.0F, 36.0F, 69.0F));
        assertTrue(RVP_ThermobaricRenderer.resolveDustFadeAlpha(37.0F, 36.0F, 69.0F) < 1.0F);
        assertEquals(0.0F, RVP_ThermobaricRenderer.resolveDustFadeAlpha(69.0F, 36.0F, 69.0F));
        assertEquals(0.5F, RVP_ThermobaricRenderer.resolveDustFadeAlpha(43.0F, 36.0F, 50.0F));
        assertEquals(0.0F, RVP_ThermobaricRenderer.resolveDustFadeAlpha(50.0F, 36.0F, 50.0F));
    }

    @Test
    void dustGroundSamplingCoversDerivedFinalRadius() {
        assertEquals(48.0F,
                RVP_ThermobaricEffectInstance.resolveDustGroundSampleRadius(10.0F, 2.4F));
        assertTrue(Float.isFinite(RVP_ThermobaricEffectInstance.resolveDustGroundSampleRadius(
                Float.MAX_VALUE, Float.MAX_VALUE)));
    }

    @Test
    void airborneDustRingStopsAboveConfiguredGroundClearance() {
        assertTrue(RVP_ThermobaricEffectInstance.shouldGenerateDustRing(120.0D, 100.0F));
        assertTrue(RVP_ThermobaricEffectInstance.shouldGenerateDustRing(120.0D, 100.0001F));
        assertTrue(RVP_ThermobaricEffectInstance.shouldGenerateDustRing(120.0D, Float.NaN));
        assertFalse(RVP_ThermobaricEffectInstance.shouldGenerateDustRing(120.0001D, 100.0F));
    }
}
