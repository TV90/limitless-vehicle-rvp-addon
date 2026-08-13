package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricParticleBudgetTest {
    @Test
    void disabledExperimentStrictlyUsesLegacyLodCountAndIgnoresCoverageLimit() {
        assertEquals(32, RVP_ThermobaricParticleBudget.resolveRenderCount(
                false, 64, 0.5F, 0));
        assertEquals(64, RVP_ThermobaricParticleBudget.resolveRenderCount(
                false, 64, 1.0F, 1));
        assertEquals(0, RVP_ThermobaricParticleBudget.resolveRenderCount(
                false, 64, 0.0F, 64));
    }

    @Test
    void enabledExperimentUsesShortestCoveragePrefixBeforeLod() {
        int coverageCount = RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                100, 10.0D, 1.25D, index -> 1.0D);

        assertEquals(13, coverageCount);
        assertEquals(7, RVP_ThermobaricParticleBudget.resolveRenderCount(
                true, 100, 0.5F, coverageCount));
        assertEquals(0, RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                100, 0.0D, 1.0D, index -> 1.0D));
    }

    @Test
    void insufficientOrInvalidCoverageSafelyUsesCapacity() {
        assertEquals(8, RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                8, 10.0D, 1.0D, index -> 0.0D));
        assertEquals(8, RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                8, Double.POSITIVE_INFINITY, 1.0D, index -> 100.0D));
        assertEquals(8, RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                8, 10.0D, Double.NaN, index -> 100.0D));
    }

    @Test
    void billboardAreaIncludesWorldSizeAndTextureCoverage() {
        assertEquals(8.0D,
                RVP_ThermobaricParticleBudget.resolveBillboardEffectiveArea(2.0F, 0.5F),
                1.0E-9D);
        assertEquals(0.0D,
                RVP_ThermobaricParticleBudget.resolveBillboardEffectiveArea(2.0F, 0.0F),
                1.0E-9D);

        int smallBillboardCount = RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                100, 32.0D, 1.0D,
                index -> RVP_ThermobaricParticleBudget.resolveBillboardEffectiveArea(
                        1.0F, 0.5F));
        int largeBillboardCount = RVP_ThermobaricParticleBudget.resolveCoverageLimitedCount(
                100, 32.0D, 1.0D,
                index -> RVP_ThermobaricParticleBudget.resolveBillboardEffectiveArea(
                        2.0F, 0.5F));
        assertTrue(smallBillboardCount > largeBillboardCount);
    }

    @Test
    void fourGeometryModelsScaleWithTheirActualDimensions() {
        assertEquals(4.0D * Math.PI,
                RVP_ThermobaricParticleBudget.resolveSphereArea(1.0F, 1.0F), 1.0E-9D);
        assertEquals(2.0D * Math.PI,
                RVP_ThermobaricParticleBudget.resolveSphereArea(1.0F, 0.5F), 1.0E-9D);
        assertEquals(4.0D * Math.PI,
                RVP_ThermobaricParticleBudget.resolveRingBandArea(2.0F, 0.5F), 1.0E-9D);
        assertEquals(9.0D * Math.PI,
                RVP_ThermobaricParticleBudget.resolveCloudSilhouetteArea(3.0D, 2.0D),
                1.0E-9D);
        assertTrue(RVP_ThermobaricRenderer.resolveDustGeometryArea(4.0F, 0.2F)
                > RVP_ThermobaricRenderer.resolveDustGeometryArea(2.0F, 0.2F));
        assertTrue(RVP_ThermobaricRenderer.resolveFireballCoreRadius(1.0F, 1.0F, 0.0F)
                > RVP_ThermobaricRenderer.resolveFireballCoreRadius(1.0F, 0.5F, 0.0F));
    }

    @Test
    void overlapFactorsRemainIndependentPerGeometryModel() {
        assertEquals(2.50D, RVP_ThermobaricParticleBudget.CONDENSATION_OVERLAP_FACTOR);
        assertEquals(1.20D, RVP_ThermobaricParticleBudget.DUST_OVERLAP_FACTOR);
        assertEquals(1.50D, RVP_ThermobaricParticleBudget.FIREBALL_OVERLAP_FACTOR);
        assertEquals(3.00D, RVP_ThermobaricParticleBudget.CLOUD_OVERLAP_FACTOR);
    }

    @Test
    void anchoredCloudCoverageCountsAnchorsBeforeOtherCandidates() {
        double[] areas = {1.0D, 1.0D, 1.0D, 4.0D, 4.0D};

        assertEquals(2, RVP_ThermobaricParticleBudget.resolveAnchoredCoverageLimitedCount(
                areas.length, 7.0D, 1.0D, 3, 4, index -> areas[index]));
        assertEquals(4, RVP_ThermobaricParticleBudget.resolveAnchoredCoverageLimitedCount(
                areas.length, 10.0D, 1.0D, 3, 4, index -> areas[index]));
    }

    @Test
    void fireballAndCloudBudgetAgeFreezeAtFullTick() {
        assertEquals(3.0F,
                RVP_ThermobaricParticleBudget.resolveFrozenBudgetAge(3.0F, 8.0F));
        assertEquals(8.0F,
                RVP_ThermobaricParticleBudget.resolveFrozenBudgetAge(8.0F, 8.0F));
        assertEquals(8.0F,
                RVP_ThermobaricParticleBudget.resolveFrozenBudgetAge(14.0F, 8.0F));
    }

    @Test
    void progressiveDustOrderUsesStableNestedWholeCirclePrefixes() {
        int[] order = RVP_ThermobaricParticleBudget.createProgressiveSegmentOrder(8);

        assertArrayEquals(new int[]{0, 4, 2, 6, 1, 5, 3, 7}, order);
        assertEquals(4, circularDistance(order[0], order[1], 8));
        assertArrayEquals(new int[]{0, 8, 4, 2, 6, 1, 5, 3, 7},
                RVP_ThermobaricParticleBudget.createProgressiveSegmentOrder(9));
    }

    private static int circularDistance(int left, int right, int segmentCount) {
        int direct = Math.abs(left - right);
        return Math.min(direct, segmentCount - direct);
    }
}
