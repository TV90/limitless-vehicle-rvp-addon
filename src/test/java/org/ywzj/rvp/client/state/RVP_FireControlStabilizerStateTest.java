package org.ywzj.rvp.client.state;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.firecontrol.RVP_FireControlModeMask;
import org.ywzj.rvp.client.state.RVP_FireControlStabilizerState.Mode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_FireControlStabilizerStateTest {

    /** 全掩码下循环次序必须与旧 Mode.next() 一致：SEMI_AUTO→STABLE→OFF→SEMI_AUTO（枚举反序）。 */
    @Test
    void fullMaskKeepsLegacyCycleOrder() {
        int all = RVP_FireControlModeMask.ALL;
        assertEquals(Mode.STABLE, RVP_FireControlStabilizerState.nextAllowedMode(Mode.SEMI_AUTO, all));
        assertEquals(Mode.OFF, RVP_FireControlStabilizerState.nextAllowedMode(Mode.STABLE, all));
        assertEquals(Mode.SEMI_AUTO, RVP_FireControlStabilizerState.nextAllowedMode(Mode.OFF, all));
    }

    @Test
    void twoModeSubsetCyclesWithinSubset() {
        int autoAndManual = RVP_FireControlModeMask.parse("auto") | RVP_FireControlModeMask.parse("manual");
        assertEquals(Mode.OFF, RVP_FireControlStabilizerState.nextAllowedMode(Mode.STABLE, autoAndManual));
        assertEquals(Mode.STABLE, RVP_FireControlStabilizerState.nextAllowedMode(Mode.OFF, autoAndManual));
        // 半自动未开放：从半自动按键按反序（SEMI_AUTO→STABLE→OFF）先命中 STABLE 跳入子集
        assertEquals(Mode.STABLE, RVP_FireControlStabilizerState.nextAllowedMode(Mode.SEMI_AUTO, autoAndManual));

        int stableAndSemi = RVP_FireControlModeMask.parse("stable") | RVP_FireControlModeMask.parse("semi_auto");
        assertEquals(Mode.SEMI_AUTO, RVP_FireControlStabilizerState.nextAllowedMode(Mode.STABLE, stableAndSemi));
        assertEquals(Mode.STABLE, RVP_FireControlStabilizerState.nextAllowedMode(Mode.SEMI_AUTO, stableAndSemi));
    }

    @Test
    void singleModeSubsetStaysPut() {
        int stableOnly = RVP_FireControlModeMask.parse("stable");
        assertEquals(Mode.STABLE, RVP_FireControlStabilizerState.nextAllowedMode(Mode.STABLE, stableOnly));
        assertEquals(Mode.STABLE, RVP_FireControlStabilizerState.nextAllowedMode(Mode.SEMI_AUTO, stableOnly));
    }

    @Test
    void emptyMaskReturnsCurrent() {
        assertEquals(Mode.OFF, RVP_FireControlStabilizerState.nextAllowedMode(Mode.OFF, 0));
    }

    /** 初始模式：半自动开放时恒为半自动（现状），未开放时取掩码中最低枚举序的开放项。 */
    @Test
    void initialModePrefersSemiAutoThenLowestOpenOrdinal() {
        int all = RVP_FireControlModeMask.ALL;
        assertEquals(Mode.SEMI_AUTO, RVP_FireControlStabilizerState.resolveInitialMode(all));

        int autoAndManual = RVP_FireControlModeMask.parse("auto") | RVP_FireControlModeMask.parse("manual");
        // STABLE(0) 枚举序低于 OFF(2)，未开放半自动时取 STABLE
        assertEquals(Mode.STABLE, RVP_FireControlStabilizerState.resolveInitialMode(autoAndManual));

        int semiAndManual = RVP_FireControlModeMask.parse("semi_auto") | RVP_FireControlModeMask.parse("manual");
        assertEquals(Mode.SEMI_AUTO, RVP_FireControlStabilizerState.resolveInitialMode(semiAndManual));

        int offOnly = RVP_FireControlModeMask.parse("manual");
        assertEquals(Mode.OFF, RVP_FireControlStabilizerState.resolveInitialMode(offOnly));
    }
}
