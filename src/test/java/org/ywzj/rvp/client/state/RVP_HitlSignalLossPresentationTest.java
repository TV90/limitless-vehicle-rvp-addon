package org.ywzj.rvp.client.state;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 视频资源已退出后的显示时钟回归：不需要导弹实体或仍存活的区块会话。 */
class RVP_HitlSignalLossPresentationTest {
    @Test
    void blockedVideoFadesInThenRecoversOrContinuesIntoSeveredLink() {
        var presentation = new RVP_HitlSignalLossPresentation();
        Object world = new Object();
        presentation.setBlocked(world, true, 0);
        assertEquals(0f, presentation.opacity(world, 0));
        presentation.setBlocked(world, true, 100_000_000);
        assertEquals(0.5f, presentation.opacity(world, 175_000_000));
        assertFalse(presentation.isVisible(world, 175_000_000));
        assertEquals(1f, presentation.opacity(world, 2_000_000_000L));
        presentation.setBlocked(world, false, 2_050_000_000L);
        assertEquals(1f, presentation.opacity(world, 2_050_000_000L));
        assertEquals(0.5f, presentation.opacity(world, 2_225_000_000L));
        assertEquals(0f, presentation.opacity(world, 2_400_000_000L));
        presentation.setBlocked(world, true, 3_000_000_000L);
        presentation.endLink(world, 3_175_000_000L);
        assertEquals(0.5f, presentation.opacity(world, 3_175_000_000L));
        assertEquals(1f, presentation.opacity(world, 3_350_000_000L));
        assertEquals(0.5f, presentation.opacity(world, 4_000_000_000L));
        assertEquals(0f, presentation.opacity(world, 4_175_000_000L));
    }
    @Test void normalExitAndLatePacketsCannotStartEndSnow() {
        assertTrue(RVP_HitlSignalLossPresentation.shouldBegin(7, 7, true, true));
        assertFalse(RVP_HitlSignalLossPresentation.shouldBegin(7, 7, true, false));
        assertFalse(RVP_HitlSignalLossPresentation.shouldBegin(-1, 7, false, true));
        assertFalse(RVP_HitlSignalLossPresentation.shouldBegin(8, 7, true, true));
        assertFalse(RVP_HitlSignalLossPresentation.shouldBegin(7, 7, false, true));
    }
    @Test
    void endedVideoShowsForOneSecondAndQueriesCannotExtendIt() {
        var presentation = new RVP_HitlSignalLossPresentation();
        Object world = new Object();
        assertFalse(presentation.isVisible(world, 10));
        presentation.detonate(world, 100);
        assertTrue(presentation.isVisible(world, 100));
        assertEquals(1f, presentation.opacity(world, 100));
        assertTrue(presentation.isVisible(world, 999_999_999L + 100));
        assertEquals(1f, presentation.opacity(world, 999_999_999L + 100));
        assertFalse(presentation.isVisible(world, 1_000_000_000L + 100));
        assertFalse(presentation.isVisible(world, 200));
    }

    @Test
    void leavingWorldOrStartingAnotherVideoCannotKeepOldNoise() {
        var presentation = new RVP_HitlSignalLossPresentation();
        Object first = new Object(), next = new Object();
        presentation.endLink(first, 100);
        assertFalse(presentation.isVisible(next, 101));
        assertFalse(presentation.isVisible(first, 102));
        presentation.endLink(first, 100);
        presentation.clear();
        assertFalse(presentation.isVisible(first, 101));
        presentation.endLink(first, 100);
        assertFalse(presentation.isVisible(null, 101));
    }

    @Test
    void signedMonotonicClockWrapKeepsTheSameOneSecondBoundary() {
        var presentation = new RVP_HitlSignalLossPresentation();
        Object world = new Object();
        long start = Long.MAX_VALUE - 200;
        presentation.endLink(world, start);
        assertTrue(presentation.isVisible(world, start + 300));
        assertFalse(presentation.isVisible(world, start + 1_000_000_000L));
    }

    @Test
    void switchingDirectionKeepsOpacityAndDetonationOverridesAnInterruptedFade() {
        var presentation = new RVP_HitlSignalLossPresentation();
        Object world = new Object();
        presentation.setBlocked(world, true, 0);
        presentation.setBlocked(world, false, 175_000_000L);
        assertEquals(0.5f, presentation.opacity(world, 175_000_000L));
        presentation.setBlocked(world, true, 262_500_000L);
        assertEquals(0.25f, presentation.opacity(world, 262_500_000L));
        presentation.detonate(world, 270_000_000L);
        assertEquals(1f, presentation.opacity(world, 270_000_000L));
    }
}
