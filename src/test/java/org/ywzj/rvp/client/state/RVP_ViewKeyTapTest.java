package org.ywzj.rvp.client.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RVP_ViewKeyTapTest {
    @Test void aTapSwitchesOnlyOnceAndARepeatDoesNotRefreshThePress() {
        var tap = new RVP_ViewKeyTap();
        tap.press(RVP_ViewKeyTap.OPEN, 0L);
        assertTrue(tap.release(RVP_ViewKeyTap.OPEN, 110_000_000L));
        assertFalse(tap.release(RVP_ViewKeyTap.OPEN, 120_000_000L));

        tap.press(17, 0L);
        tap.press(17, 500_000_000L);
        assertFalse(tap.release(17, 510_000_000L));
        assertFalse(tap.isPressed());
    }

    @Test void changingMissileOrLeavingTheViewCancelsTheTap() {
        var tap = new RVP_ViewKeyTap();
        tap.press(17, 0L);
        assertFalse(tap.release(18, 100_000_000L));
        tap.press(17, 0L);
        tap.cancel();
        assertFalse(tap.release(17, 100_000_000L));
    }
}
