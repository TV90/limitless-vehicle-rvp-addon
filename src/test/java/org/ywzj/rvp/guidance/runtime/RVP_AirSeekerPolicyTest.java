package org.ywzj.rvp.guidance.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 用户指定的搜索、计时和雷达隐身边界，使用生产决策函数逐刻推进。 */
class RVP_AirSeekerPolicyTest {
    @Test void noRadarSearchesImmediatelyButCannotTimeOutBeforeActivationRange() {
        var d = RVP_AirSeekerPolicy.decide(false, false, false, 1000, 100);
        assertTrue(d.searchOn()); assertFalse(d.lossTimerArmed());
        int ticks = 0;
        for (int i = 0; i < 400; i++) ticks = RVP_AirSeekerPolicy.nextLostTicks(ticks, d.lossTimerArmed(), false, false);
        assertEquals(0, ticks);
        d = RVP_AirSeekerPolicy.decide(d.searchOn(), d.lossTimerArmed(), false, 100, 100);
        assertTrue(d.lossTimerArmed());
        assertEquals(1, RVP_AirSeekerPolicy.nextLostTicks(ticks, d.lossTimerArmed(), false, false));
        assertTrue(RVP_AirSeekerPolicy.decide(true, true, false, 1000, 100).lossTimerArmed());
    }
    @Test void radarRecoveryDoesNotShutOffTheAlreadySearchingInfraredHead() {
        var d = RVP_AirSeekerPolicy.decide(false, false, true, 1000, 100);
        assertFalse(d.searchOn());
        d = RVP_AirSeekerPolicy.decide(d.searchOn(), d.lossTimerArmed(), false, 900, 100);
        assertTrue(d.searchOn());
        d = RVP_AirSeekerPolicy.decide(d.searchOn(), d.lossTimerArmed(), true, 800, 100);
        assertTrue(d.searchOn()); assertFalse(d.lossTimerArmed());
        assertEquals(0, RVP_AirSeekerPolicy.nextLostTicks(59, true, true, false));
        assertEquals(0, RVP_AirSeekerPolicy.nextLostTicks(59, true, false, true));
    }
    @Test void staleRadarLockCannotBypassStealthButArhKeepsItsOriginalPolicy() {
        assertFalse(RVP_AirSeekerPolicy.radarSupports(true, false, true));
        assertTrue(RVP_AirSeekerPolicy.radarSupports(true, true, false));
        assertTrue(RVP_AirSeekerPolicy.radarSupports(false, false, true));
    }
    @Test void chaffRequiresAnActualHardLockEvenWhenStillInsideRadarRange() {
        assertTrue(RVP_AirSeekerPolicy.chaffRelayAvailable(true, true));
        assertFalse(RVP_AirSeekerPolicy.chaffRelayAvailable(true, false));
        assertFalse(RVP_AirSeekerPolicy.chaffRelayAvailable(false, true));
    }
    @Test void noDistanceLimitArmsTimerAndMissingMemoryDoesNotInventAReachedRange() {
        assertTrue(RVP_AirSeekerPolicy.decide(false, false, false, -1, 0).lossTimerArmed());
        assertFalse(RVP_AirSeekerPolicy.decide(false, false, false, -1, 100).lossTimerArmed());
    }
}
