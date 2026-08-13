package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricParticleLodTest {
    @Test
    void defaultLodUsesInclusiveDistanceBoundaries() {
        RVP_ThermobaricLod lod = RVP_ThermobaricLod.DEFAULT;

        assertEquals(1.0F, lod.resolveParticleRatio(512.0D * 512.0D));
        assertEquals(0.7F, lod.resolveParticleRatio(513.0D * 513.0D));
        assertEquals(0.7F, lod.resolveParticleRatio(756.0D * 756.0D));
        assertEquals(0.35F, lod.resolveParticleRatio(757.0D * 757.0D));
        assertEquals(0.35F, lod.resolveParticleRatio(1024.0D * 1024.0D));
        assertEquals(0.0F, lod.resolveParticleRatio(1025.0D * 1025.0D));
    }

    @Test
    void renderCountsRespectRatioAndMinimumVisibility() {
        assertEquals(0, RVP_ThermobaricParticleLod.resolveRenderCount(64, 0.0F));
        assertEquals(64, RVP_ThermobaricParticleLod.resolveRenderCount(64, 1.0F));
        assertEquals(32, RVP_ThermobaricParticleLod.resolveRenderCount(64, 0.5F));
        assertEquals(16, RVP_ThermobaricParticleLod.resolveRenderCount(64, 0.25F));
        assertEquals(1, RVP_ThermobaricParticleLod.resolveRenderCount(3, 0.01F));
        assertEquals(3, RVP_ThermobaricParticleLod.resolveCoreLayerCount(1.0F));
        assertEquals(2, RVP_ThermobaricParticleLod.resolveCoreLayerCount(0.5F));
        assertEquals(1, RVP_ThermobaricParticleLod.resolveCoreLayerCount(0.25F));
        assertEquals(0, RVP_ThermobaricParticleLod.resolveCoreLayerCount(0.0F));
    }

    @Test
    void dustSamplesRemainDistributedAroundWholeRing() {
        assertEquals(List.of(1, 3, 5, 7), sampledIndices(8, 4));
        assertEquals(List.of(2, 6), sampledIndices(8, 2));
        assertEquals(List.of(1, 4, 7), sampledIndices(9, 3));
    }

    @Test
    void cloudSelectionKeepsBothLinkAnchorsWithinBudget() {
        RVP_ThermobaricCloudLink.AnchorPair link =
                new RVP_ThermobaricCloudLink.AnchorPair(6, 9, 123L);
        List<Integer> selected = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            if (RVP_ThermobaricRenderer.isCloudIndexSelected(index, 4, 12, link)) {
                selected.add(index);
            }
        }

        assertEquals(4, selected.size());
        assertTrue(selected.contains(6));
        assertTrue(selected.contains(9));
        assertTrue(RVP_ThermobaricRenderer.isCloudIndexSelected(0, 4, 12, link));
        assertFalse(RVP_ThermobaricRenderer.isCloudIndexSelected(11, 4, 12, link));
    }

    private static List<Integer> sampledIndices(int generatedCount, int renderCount) {
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < renderCount; index++) {
            indices.add(RVP_ThermobaricParticleLod.resolveEvenlySpacedIndex(
                    index, renderCount, generatedCount));
        }
        return indices;
    }
}
