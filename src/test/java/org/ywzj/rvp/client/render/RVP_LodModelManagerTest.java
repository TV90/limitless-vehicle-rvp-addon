package org.ywzj.rvp.client.render;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.resource.vehicle.RVP_LodModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_LodModelManagerTest {
    /** 从近到远排列的两级测试规则。 */
    private static final List<RVP_LodModel> RULES = List.of(
            rule("rvp:lod_near", 100.0D, 80.0D, 10.0D, -1.0D, -1.0D),
            rule("rvp:lod_far", 300.0D, 200.0D, 30.0D, 500.0D, 400.0D));

    @Test
    void selectsLevelsFromFarthestToNearest() {
        assertEquals(1, RVP_LodModelManager.selectLevel(RULES, false, 350.0D, -1, false, 1.0D));
        assertEquals(0, RVP_LodModelManager.selectLevel(RULES, false, 150.0D, -1, false, 1.0D));
        assertEquals(-1, RVP_LodModelManager.selectLevel(RULES, false, 100.0D, -1, false, 1.0D));
    }

    @Test
    void activeLevelUsesPointEightFiveRetreatThreshold() {
        assertEquals(1, RVP_LodModelManager.selectLevel(RULES, false, 256.0D, 1, false, 1.0D));
        assertEquals(0, RVP_LodModelManager.selectLevel(RULES, false, 255.0D, 1, false, 1.0D));
    }

    @Test
    void airborneAndZoomUseTheirDedicatedThresholds() {
        assertEquals(1, RVP_LodModelManager.selectLevel(RULES, true, 250.0D, -1, false, 1.0D));
        assertEquals(1, RVP_LodModelManager.selectLevel(RULES, false, 501.0D, -1, true, 2.0D));
        assertEquals(0, RVP_LodModelManager.selectLevel(RULES, false, 250.0D, -1, true, 2.0D));
    }

    @Test
    void explicitAglUsesCurrentLevelAirHeightOrFirstLevelFallback() {
        assertFalse(RVP_LodModelManager.isAirborne(RULES, -1, 9.99D));
        assertTrue(RVP_LodModelManager.isAirborne(RULES, -1, 10.0D));
        assertFalse(RVP_LodModelManager.isAirborne(RULES, 1, 29.99D));
        assertTrue(RVP_LodModelManager.isAirborne(RULES, 1, 30.0D));
    }

    @Test
    void remoteFirstEvaluationIsImmediateAndThenThrottledForTwentyTicks() {
        assertTrue(RVP_LodModelManager.shouldEvaluate(7L, null, true));
        assertFalse(RVP_LodModelManager.shouldEvaluate(7L, null, false));
        assertFalse(RVP_LodModelManager.shouldEvaluate(26L, 7L, true));
        assertTrue(RVP_LodModelManager.shouldEvaluate(27L, 7L, true));
    }

    /** 创建测试 LOD 规则。 */
    private static RVP_LodModel rule(String modelId, double distance, double airDistance,
                                     double airHeight, double zoomDistance, double zoomAirDistance) {
        return new RVP_LodModel(ResourceLocation.parse(modelId), null, null,
                distance, airDistance, airHeight, zoomDistance, zoomAirDistance);
    }
}
