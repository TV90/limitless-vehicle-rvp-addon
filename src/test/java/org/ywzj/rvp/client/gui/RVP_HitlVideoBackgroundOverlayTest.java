package org.ywzj.rvp.client.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 区分链路遮挡、命中后终结和恢复画面，防止残留 blocked 状态重新覆盖世界。 */
class RVP_HitlVideoBackgroundOverlayTest {
    @Test void signalLossAndTerminalNoImageDrawSnowWhileNormalViewDoesNot() {
        assertTrue(RVP_HitlVideoBackgroundOverlay.needsSnow(true, true, false));
        assertTrue(RVP_HitlVideoBackgroundOverlay.needsSnow(false, false, true));
        assertTrue(RVP_HitlVideoBackgroundOverlay.needsSnow(true, false, true));
        assertFalse(RVP_HitlVideoBackgroundOverlay.needsSnow(true, false, false));
        assertFalse(RVP_HitlVideoBackgroundOverlay.needsSnow(false, true, false));
        assertFalse(RVP_HitlVideoBackgroundOverlay.needsSnow(false, false, false));
    }
}
