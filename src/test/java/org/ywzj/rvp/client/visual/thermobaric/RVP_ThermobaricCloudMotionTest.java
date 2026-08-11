package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricCloudMotionTest {
    @Test
    void lowDensitySequenceStillContainsAllMotionLayers() {
        Map<RVP_ThermobaricEffectInstance.CloudLayer, Integer> counts =
                new EnumMap<>(RVP_ThermobaricEffectInstance.CloudLayer.class);
        for (int index = 0; index < 10; index++) {
            counts.merge(RVP_ThermobaricEffectInstance.resolveCloudLayer(index), 1, Integer::sum);
        }

        assertEquals(3, counts.get(RVP_ThermobaricEffectInstance.CloudLayer.CENTER));
        assertEquals(3, counts.get(RVP_ThermobaricEffectInstance.CloudLayer.UPDRAFT));
        assertEquals(4, counts.get(RVP_ThermobaricEffectInstance.CloudLayer.ROLLER));
    }

    @Test
    void centerLayerRemainsLowAndCloseToBlastCenter() {
        RVP_ThermobaricEffectInstance.Cloud cloud = cloud(
                RVP_ThermobaricEffectInstance.CloudLayer.CENTER, 0.08F, 0.20F, 0.0F);

        RVP_ThermobaricCloudMotion.Motion motion =
                RVP_ThermobaricCloudMotion.resolve(cloud, 1.0F, 1.0F, 1.0F);

        assertTrue(motion.radialFactor() <= 0.08F);
        assertTrue(motion.riseFactor() < 0.10F);
    }

    @Test
    void rollerLayerRisesOverCapThenTurnsOutward() {
        RVP_ThermobaricEffectInstance.Cloud cloud = cloud(
                RVP_ThermobaricEffectInstance.CloudLayer.ROLLER, 0.80F, 0.90F, 0.20F);

        RVP_ThermobaricCloudMotion.Motion inner =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.18F, 1.0F, 1.0F);
        RVP_ThermobaricCloudMotion.Motion top =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.55F, 1.0F, 1.0F);
        RVP_ThermobaricCloudMotion.Motion outer =
                RVP_ThermobaricCloudMotion.resolve(cloud, 1.0F, 1.0F, 1.0F);

        assertTrue(top.riseFactor() > inner.riseFactor());
        assertTrue(top.riseFactor() > outer.riseFactor());
        assertTrue(outer.riseFactor() > inner.riseFactor());
        assertTrue(outer.radialFactor() > top.radialFactor());
    }

    @Test
    void cloudKeepsRollingAcrossFadeStartWithoutMotionPhaseSwitch() {
        RVP_ThermobaricEffectInstance.Cloud cloud = cloud(
                RVP_ThermobaricEffectInstance.CloudLayer.ROLLER, 0.80F, 0.90F, 0.20F);

        RVP_ThermobaricCloudMotion.Motion beforeFadeStart =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.34F, 1.0F, 1.0F);
        RVP_ThermobaricCloudMotion.Motion atFadeStart =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.35F, 1.0F, 1.0F);
        RVP_ThermobaricCloudMotion.Motion afterFadeStart =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.36F, 1.0F, 1.0F);

        assertTrue(atFadeStart.radialFactor() != beforeFadeStart.radialFactor());
        assertTrue(afterFadeStart.radialFactor() != atFadeStart.radialFactor());
        assertTrue(atFadeStart.angleOffset() != beforeFadeStart.angleOffset());
        assertTrue(afterFadeStart.angleOffset() != atFadeStart.angleOffset());
    }

    @Test
    void riseSpeedControlsApproachToConfiguredHeightCap() {
        RVP_ThermobaricEffectInstance.Cloud cloud = cloud(
                RVP_ThermobaricEffectInstance.CloudLayer.UPDRAFT, 0.20F, 0.90F, 0.0F);

        RVP_ThermobaricCloudMotion.Motion stopped =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.50F, 0.0F, 1.0F);
        RVP_ThermobaricCloudMotion.Motion slow =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.50F, 0.5F, 1.0F);
        RVP_ThermobaricCloudMotion.Motion fast =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.50F, 2.0F, 1.0F);

        assertEquals(0.0F, stopped.riseFactor());
        assertTrue(slow.riseFactor() < fast.riseFactor());
        assertTrue(fast.riseFactor() <= 1.0F);
    }

    @Test
    void rollSpeedControlsContinuousMotionIndependently() {
        RVP_ThermobaricEffectInstance.Cloud cloud = cloud(
                RVP_ThermobaricEffectInstance.CloudLayer.ROLLER, 0.80F, 0.90F, 0.20F);

        RVP_ThermobaricCloudMotion.Motion stoppedEarly =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.25F, 1.0F, 0.0F);
        RVP_ThermobaricCloudMotion.Motion stoppedLate =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.75F, 1.0F, 0.0F);
        RVP_ThermobaricCloudMotion.Motion rollingEarly =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.25F, 1.0F, 2.0F);
        RVP_ThermobaricCloudMotion.Motion rollingLate =
                RVP_ThermobaricCloudMotion.resolve(cloud, 0.75F, 1.0F, 2.0F);
        RVP_ThermobaricCloudMotion.Motion extremeFiniteSpeed =
                RVP_ThermobaricCloudMotion.resolve(
                        cloud, 0.50F, Float.MAX_VALUE, Float.MAX_VALUE);

        assertEquals(stoppedEarly.radialFactor(), stoppedLate.radialFactor());
        assertEquals(stoppedEarly.angleOffset(), stoppedLate.angleOffset());
        assertTrue(rollingEarly.radialFactor() != rollingLate.radialFactor());
        assertTrue(rollingEarly.angleOffset() != rollingLate.angleOffset());
        assertTrue(Float.isFinite(extremeFiniteSpeed.radialFactor()));
        assertTrue(Float.isFinite(extremeFiniteSpeed.riseFactor()));
        assertTrue(Float.isFinite(extremeFiniteSpeed.angleOffset()));
        assertTrue(Float.isFinite(extremeFiniteSpeed.scaleMultiplier()));
    }

    private static RVP_ThermobaricEffectInstance.Cloud cloud(
            RVP_ThermobaricEffectInstance.CloudLayer layer,
            float radialFactor, float riseFactor, float rollerRadiusFactor) {
        return new RVP_ThermobaricEffectInstance.Cloud(
                layer, 0.0F, radialFactor, riseFactor, 0.08F,
                0.0F, 0.0F, 0.0F, rollerRadiusFactor,
                0.0F, 1.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F);
    }
}
