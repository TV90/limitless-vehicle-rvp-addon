package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricCloudLinkTest {
    @Test
    void anchorSelectionUsesCorrectLayersAndStableIndexTieBreak() {
        List<RVP_ThermobaricEffectInstance.Cloud> clouds = List.of(
                cloud(RVP_ThermobaricEffectInstance.CloudLayer.CENTER, 0.0F, 0.10F, 0.20F),
                cloud(RVP_ThermobaricEffectInstance.CloudLayer.CENTER, 0.0F, 0.10F, 0.20F),
                cloud(RVP_ThermobaricEffectInstance.CloudLayer.UPDRAFT, 0.0F, 0.12F, 0.90F),
                cloud(RVP_ThermobaricEffectInstance.CloudLayer.ROLLER, 0.0F, 0.10F, 0.90F));

        RVP_ThermobaricCloudLink.AnchorPair first =
                RVP_ThermobaricCloudLink.selectAnchors(clouds, 12345L);
        RVP_ThermobaricCloudLink.AnchorPair second =
                RVP_ThermobaricCloudLink.selectAnchors(clouds, 12345L);

        assertNotNull(first);
        assertEquals(0, first.centerIndex());
        assertEquals(2, first.updraftIndex());
        assertEquals(first, second);
        assertEquals(RVP_ThermobaricEffectInstance.CloudLayer.CENTER,
                clouds.get(first.centerIndex()).layer());
        assertEquals(RVP_ThermobaricEffectInstance.CloudLayer.UPDRAFT,
                clouds.get(first.updraftIndex()).layer());
        assertNotEquals(first.visualSeed(),
                RVP_ThermobaricCloudLink.selectAnchors(clouds, 12346L).visualSeed());
    }

    @Test
    void anchorSelectionRequiresBothConnectedLayers() {
        assertNull(RVP_ThermobaricCloudLink.selectAnchors(List.of(
                cloud(RVP_ThermobaricEffectInstance.CloudLayer.CENTER,
                        0.0F, 0.10F, 0.20F)), 1L));
        assertNull(RVP_ThermobaricCloudLink.selectAnchors(List.of(
                cloud(RVP_ThermobaricEffectInstance.CloudLayer.UPDRAFT,
                        0.0F, 0.10F, 0.90F)), 1L));
    }

    @Test
    void particleBudgetScalesWithBaseCloudCountAndRemainsBounded() {
        assertEquals(0, RVP_ThermobaricCloudLink.resolveParticleLimit(0));
        assertEquals(0, RVP_ThermobaricCloudLink.resolveParticleLimit(1));
        assertEquals(1, RVP_ThermobaricCloudLink.resolveParticleLimit(2));
        assertEquals(2, RVP_ThermobaricCloudLink.resolveParticleLimit(8));
        assertEquals(50, RVP_ThermobaricCloudLink.resolveParticleLimit(200));
        assertEquals(64, RVP_ThermobaricCloudLink.resolveParticleLimit(1024));
    }

    @Test
    void overlappingEndpointsAndZeroRadiusDoNotCreateDerivedParticles() {
        assertEquals(0, RVP_ThermobaricCloudLink.resolveLayout(
                3.0D, 2.0F, 1.0F, 8.0F, 64).particleCount());
        assertEquals(0, RVP_ThermobaricCloudLink.resolveLayout(
                20.0D, 1.0F, 1.0F, 0.0F, 64).particleCount());
        assertEquals(0, RVP_ThermobaricCloudLink.resolveLayout(
                20.0D, 1.0F, 1.0F, 8.0F, 0).particleCount());
    }

    @Test
    void highRadiusAndRiseFactorsRemainConnectedThroughoutLifetime() {
        assertHighFactorContinuity(2.0F, 2.0F, 7.0F);
        assertHighFactorContinuity(3.0F, 5.0F, 7.0F);
    }

    @Test
    void cappedParticleCountStillExpandsSizesToCoverExtremeFiniteGap() {
        double extremeDistance = (double) Float.MAX_VALUE * 2.0D;
        RVP_ThermobaricCloudLink.Layout layout = RVP_ThermobaricCloudLink.resolveLayout(
                extremeDistance, 1.0F, 1.0F, 8.0F, 64);

        assertEquals(64, layout.particleCount());
        assertCoverage(layout);
        for (int index = 0; index < layout.particleCount(); index++) {
            assertTrue(Float.isFinite(layout.halfSize(index)));
        }
    }

    @Test
    void visualVariationIsStableAndDoesNotChangeParticlePositions() {
        long seed = 987654321L;
        assertEquals(RVP_ThermobaricCloudLink.resolveRotation(seed, 3),
                RVP_ThermobaricCloudLink.resolveRotation(seed, 3));
        assertEquals(RVP_ThermobaricCloudLink.resolveBrightness(seed, 3),
                RVP_ThermobaricCloudLink.resolveBrightness(seed, 3));
        assertNotEquals(RVP_ThermobaricCloudLink.resolveRotation(seed, 3),
                RVP_ThermobaricCloudLink.resolveRotation(seed, 4));
    }

    private static void assertHighFactorContinuity(float cloudRadiusFactor,
            float cloudRiseFactor, float riseSpeedFactor) {
        float visualRadius = 8.0F;
        RVP_ThermobaricEffectInstance.Cloud center = cloud(
                RVP_ThermobaricEffectInstance.CloudLayer.CENTER,
                0.0F, 0.08F, 0.20F);
        RVP_ThermobaricEffectInstance.Cloud updraft = cloud(
                RVP_ThermobaricEffectInstance.CloudLayer.UPDRAFT,
                0.0F, 0.20F, 0.90F);
        for (float progress : new float[]{0.05F, 0.35F, 0.75F, 0.99F}) {
            RVP_ThermobaricCloudMotion.Motion centerMotion =
                    RVP_ThermobaricCloudMotion.resolve(
                            center, progress, riseSpeedFactor, 1.0F);
            RVP_ThermobaricCloudMotion.Motion updraftMotion =
                    RVP_ThermobaricCloudMotion.resolve(
                            updraft, progress, riseSpeedFactor, 1.0F);
            double centerX = visualRadius * cloudRadiusFactor * centerMotion.radialFactor();
            double updraftX = visualRadius * cloudRadiusFactor * updraftMotion.radialFactor();
            double centerY = RVP_ThermobaricRenderer.resolveCloudRiseHeight(
                    visualRadius, cloudRiseFactor, centerMotion.riseFactor());
            double updraftY = RVP_ThermobaricRenderer.resolveCloudRiseHeight(
                    visualRadius, cloudRiseFactor, updraftMotion.riseFactor());
            double distance = Math.hypot(updraftX - centerX, updraftY - centerY);
            float centerSize = visualRadius * center.sizeFactor()
                    * (0.72F + progress * 1.38F) * centerMotion.scaleMultiplier();
            float updraftSize = visualRadius * updraft.sizeFactor()
                    * (0.72F + progress * 1.38F) * updraftMotion.scaleMultiplier();
            RVP_ThermobaricCloudLink.Layout layout =
                    RVP_ThermobaricCloudLink.resolveLayout(
                            distance, centerSize, updraftSize, visualRadius, 64);
            if (layout.particleCount() == 0) {
                assertTrue(distance <= centerSize + updraftSize);
            } else {
                assertTrue(layout.particleCount() <= 64);
                assertCoverage(layout);
            }
        }
    }

    private static void assertCoverage(RVP_ThermobaricCloudLink.Layout layout) {
        double previousHalfSize = layout.startHalfSize();
        for (int index = 0; index < layout.particleCount(); index++) {
            double currentHalfSize = layout.halfSize(index);
            assertTrue(previousHalfSize + currentHalfSize >= layout.spacing(),
                    "连接粒子与前一个粒子之间出现了几何间隙");
            previousHalfSize = currentHalfSize;
        }
        assertTrue(previousHalfSize + layout.endHalfSize() >= layout.spacing(),
                "最后一个连接粒子与中心上升层锚点之间出现了几何间隙");
    }

    private static RVP_ThermobaricEffectInstance.Cloud cloud(
            RVP_ThermobaricEffectInstance.CloudLayer layer,
            float angle, float radialFactor, float riseFactor) {
        return new RVP_ThermobaricEffectInstance.Cloud(
                layer, angle, radialFactor, riseFactor, 0.10F,
                0.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F);
    }
}
