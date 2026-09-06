package org.ywzj.rvp.firesupport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RVP_FireSupportSchedulePlannerTest {
    @Test
    void exampleModesProduceExpectedCountsCallsAndExactLastTicks() {
        RVP_FireSupportProfile profile = RVP_FireSupportTestProfiles.parse();
        var rapid = plan(profile, "rapid", 7L);
        assertEquals(800, rapid.callDurationTicks());
        assertEquals(9, rapid.rounds().size());
        assertEquals(60, rapid.rounds().get(8).strikeOffsetTicks());

        var effect = plan(profile, "effect", 11L);
        assertEquals(1200, effect.callDurationTicks());
        assertTrue(effect.rounds().size() == 14 || effect.rounds().size() == 15);
        int registration = effect.rounds().size() - 12;
        assertEquals((registration - 1) * 20 + 80, effect.rounds().get(registration).strikeOffsetTicks());
        assertEquals(effect.rounds().get(registration).strikeOffsetTicks() + 180,
                effect.rounds().get(effect.rounds().size() - 1).strikeOffsetTicks());

        var monitor = plan(profile, "monitor", 11L);
        assertEquals(effect.rounds().size(), monitor.rounds().size());
        assertEquals(effect.rounds().get(registration).strikeOffsetTicks() + 600,
                monitor.rounds().get(monitor.rounds().size() - 1).strikeOffsetTicks());
    }

    @Test
    void baseMultipliersAlwaysRoundUp() {
        RVP_FireSupportProfile profile = RVP_FireSupportTestProfiles.parse();
        var rapid = RVP_FireSupportSchedulePlanner.plan(800, 5, true, profile.fireModes().get("rapid"), 1, profile.limits());
        assertEquals(8, rapid.rounds().size());
    }

    @Test
    void munitionCanDisableMarkedRegistrationPhaseWithoutDependingOnItsId() {
        RVP_FireSupportProfile profile = RVP_FireSupportTestProfiles.parse();
        var effect = RVP_FireSupportSchedulePlanner.plan(profile.callStage().baseDurationTicks(), 6, false,
                profile.fireModes().get("effect"), 11L, profile.limits());
        assertEquals(12, effect.rounds().size());
        assertTrue(effect.rounds().stream().noneMatch(round -> round.phaseId().equals("registration")));
        assertEquals(80, effect.rounds().get(0).strikeOffsetTicks());
    }

    private static RVP_FireSupportSchedulePlanner.Plan plan(RVP_FireSupportProfile profile, String mode, long seed) {
        return RVP_FireSupportSchedulePlanner.plan(profile.callStage().baseDurationTicks(), 6, true,
                profile.fireModes().get(mode), seed, profile.limits());
    }
}
