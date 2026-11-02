package org.ywzj.rvp.client.state;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 视频资源已退出后的显示时钟回归：不需要导弹实体或仍存活的区块会话。 */
class RVP_HitlSignalLossPresentationTest {
    @Test
    void endedVideoShowsForOneSecondAndQueriesCannotExtendIt() {
        var presentation = new RVP_HitlSignalLossPresentation();
        Object world = new Object();
        assertFalse(presentation.isVisible(world, 10));
        presentation.begin(world, 100);
        assertTrue(presentation.isVisible(world, 100));
        assertTrue(presentation.isVisible(world, 999_999_999L + 100));
        assertFalse(presentation.isVisible(world, 1_000_000_000L + 100));
        assertFalse(presentation.isVisible(world, 200));
    }

    @Test
    void leavingWorldOrStartingAnotherVideoCannotKeepOldNoise() {
        var presentation = new RVP_HitlSignalLossPresentation();
        Object first = new Object(), next = new Object();
        presentation.begin(first, 100);
        assertFalse(presentation.isVisible(next, 101));
        assertFalse(presentation.isVisible(first, 102));
        presentation.begin(first, 100);
        presentation.clear();
        assertFalse(presentation.isVisible(first, 101));
        presentation.begin(first, 100);
        assertFalse(presentation.isVisible(null, 101));
    }

    @Test
    void signedMonotonicClockWrapKeepsTheSameOneSecondBoundary() {
        var presentation = new RVP_HitlSignalLossPresentation();
        Object world = new Object();
        long start = Long.MAX_VALUE - 200;
        presentation.begin(world, start);
        assertTrue(presentation.isVisible(world, start + 300));
        assertFalse(presentation.isVisible(world, start + 1_000_000_000L));
    }
}
