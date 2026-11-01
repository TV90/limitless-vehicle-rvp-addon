package org.ywzj.rvp.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.ywzj.rvp.event.RVP_RotaryFlightMode.*;

/** 回归玩家按 Z 和程序回母车设置悬停交错时的模式转换。 */
class RVP_RotaryFlightModeTest {
    @Test
    void nativeToggleStillCyclesThroughAllThreeModesWithoutPhantomTransitions() {
        RVP_RotaryFlightMode mode = NORMAL.observeHover(true);
        assertEquals(AUTO_COLLECTIVE, mode);
        for (int tick = 0; tick < 40; tick++) mode = mode.observeHover(false);
        assertEquals(AUTO_COLLECTIVE, mode, "校正后的 hover=false 不能被误认为再次按键");
        mode = mode.observeHover(true);
        assertEquals(HOVER, mode);
        for (int tick = 0; tick < 40; tick++) mode = mode.observeHover(true);
        assertEquals(HOVER, mode);
        assertEquals(NORMAL, mode.observeHover(false));
    }

    @Test
    void programmaticHoverRemainsHoverUntilPilotTogglesOrResumesManualControl() {
        // 模拟自动总距中离机；程序入口直接登记 HOVER，下一 tick 不得退回自动总距。
        RVP_RotaryFlightMode mode = HOVER;
        for (int tick = 0; tick < 200; tick++) mode = mode.observeHover(true);
        assertEquals(HOVER, mode);
        assertEquals(NORMAL, mode.observeHover(false), "重新接管/按 Z 退出后回常规");
        assertEquals(AUTO_COLLECTIVE, NORMAL.observeHover(true), "之后仍可正常使用三态循环");
    }
}
