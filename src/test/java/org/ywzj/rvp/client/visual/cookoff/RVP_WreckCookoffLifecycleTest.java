package org.ywzj.rvp.client.visual.cookoff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证殉燃火柱生命周期的增长和收缩方向。 */
class RVP_WreckCookoffLifecycleTest {

    @Test
    void shrinkStartsAtCurrentScaleAndEndsAtZero() {
        double startScale = 0.82;

        assertEquals(startScale, RVP_WreckCookoffController.shrinkLifecycleScale(startScale, 0), 1.0E-9);
        assertTrue(RVP_WreckCookoffController.shrinkLifecycleScale(startScale, 0.5) < startScale);
        assertEquals(0, RVP_WreckCookoffController.shrinkLifecycleScale(startScale, 1), 1.0E-9);
    }

    @Test
    void growthStartsAtZeroAndEndsAtFullScale() {
        assertEquals(0, RVP_WreckCookoffController.growthLifecycleScale(0), 1.0E-9);
        assertTrue(RVP_WreckCookoffController.growthLifecycleScale(0.5) > 0);
        assertEquals(1, RVP_WreckCookoffController.growthLifecycleScale(1), 1.0E-9);
    }

    @Test
    void cookoffDurationUsesConfiguredWreckLifetimePercentage() {
        // 60 秒残骸寿命取 50%，结果应为 30 秒，即 600 tick。
        assertEquals(600, RVP_WreckCookoffController.cookoffDurationTicks(60, 50));
        assertEquals(0, RVP_WreckCookoffController.cookoffDurationTicks(0, 50));
        assertEquals(0, RVP_WreckCookoffController.cookoffDurationTicks(60, 0));
    }
}
