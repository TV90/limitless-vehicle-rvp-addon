package org.ywzj.rvp.client.firecontrol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_FireControlModeMaskTest {

    @Test
    void onlyThreeLegalValuesParseToOwnBits() {
        assertEquals(RVP_FireControlModeMask.STABLE, RVP_FireControlModeMask.parse("auto"));
        assertEquals(RVP_FireControlModeMask.SEMI_AUTO, RVP_FireControlModeMask.parse("semi"));
        assertEquals(RVP_FireControlModeMask.OFF, RVP_FireControlModeMask.parse("manual"));
    }

    @Test
    void parsingIsCaseAndWhitespaceInsensitive() {
        assertEquals(RVP_FireControlModeMask.STABLE, RVP_FireControlModeMask.parse(" Auto "));
        assertEquals(RVP_FireControlModeMask.SEMI_AUTO, RVP_FireControlModeMask.parse("SEMI"));
        assertEquals(RVP_FireControlModeMask.OFF, RVP_FireControlModeMask.parse("MANUAL"));
    }

    @Test
    void legacyAndIllegalValuesParseToZero() {
        // 旧草案别名 stable/semi_auto/off 与任意非法值一律归零（回退三种全有）
        assertEquals(0, RVP_FireControlModeMask.parse("stable"));
        assertEquals(0, RVP_FireControlModeMask.parse("semi_auto"));
        assertEquals(0, RVP_FireControlModeMask.parse("off"));
        assertEquals(0, RVP_FireControlModeMask.parse("xxx"));
        assertEquals(0, RVP_FireControlModeMask.parse(""));
        assertEquals(0, RVP_FireControlModeMask.parse("  "));
        assertEquals(0, RVP_FireControlModeMask.parse(null));
    }

    @Test
    void allEqualsUnionOfThreeModes() {
        assertEquals(0b111, RVP_FireControlModeMask.ALL);
        assertEquals(RVP_FireControlModeMask.ALL,
                RVP_FireControlModeMask.STABLE | RVP_FireControlModeMask.SEMI_AUTO | RVP_FireControlModeMask.OFF);
    }
}
