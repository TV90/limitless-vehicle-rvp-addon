package org.ywzj.rvp.client.firecontrol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_FireControlModeMaskTest {

    @Test
    void canonicalValuesParseToOwnBits() {
        assertEquals(RVP_FireControlModeMask.STABLE, RVP_FireControlModeMask.parse("stable"));
        assertEquals(RVP_FireControlModeMask.SEMI_AUTO, RVP_FireControlModeMask.parse("semi_auto"));
        assertEquals(RVP_FireControlModeMask.OFF, RVP_FireControlModeMask.parse("off"));
    }

    @Test
    void aliasesParseToSameBit() {
        assertEquals(RVP_FireControlModeMask.STABLE, RVP_FireControlModeMask.parse("AUTO"));
        assertEquals(RVP_FireControlModeMask.SEMI_AUTO, RVP_FireControlModeMask.parse("Semi"));
        assertEquals(RVP_FireControlModeMask.SEMI_AUTO, RVP_FireControlModeMask.parse("SEMIAUTO"));
        assertEquals(RVP_FireControlModeMask.OFF, RVP_FireControlModeMask.parse("manual"));
    }

    @Test
    void parsingIsCaseAndWhitespaceInsensitive() {
        assertEquals(RVP_FireControlModeMask.STABLE, RVP_FireControlModeMask.parse(" Stable "));
        assertEquals(RVP_FireControlModeMask.SEMI_AUTO, RVP_FireControlModeMask.parse(" Semi_Auto "));
        assertEquals(RVP_FireControlModeMask.OFF, RVP_FireControlModeMask.parse("OFF"));
    }

    @Test
    void illegalValuesParseToZero() {
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
