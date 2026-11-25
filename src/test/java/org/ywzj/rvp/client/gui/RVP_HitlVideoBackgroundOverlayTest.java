package org.ywzj.rvp.client.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 区分链路遮挡、命中后终结和恢复画面，防止残留 blocked 状态重新覆盖世界。 */
class RVP_HitlVideoBackgroundOverlayTest {
    @Test void drawsVisibleFadesAndTerminalSnowButSkipsAnInactiveView() {
        assertTrue(RVP_HitlVideoBackgroundOverlay.needsSnow(true, 0.4f, false));
        assertTrue(RVP_HitlVideoBackgroundOverlay.needsSnow(false, 1f, true));
        assertFalse(RVP_HitlVideoBackgroundOverlay.needsSnow(true, 0f, false));
        assertFalse(RVP_HitlVideoBackgroundOverlay.needsSnow(false, 0.4f, false));
        assertFalse(RVP_HitlVideoBackgroundOverlay.needsSnow(false, 0f, true));
    }
}
