package org.ywzj.rvp.guidance;

import com.google.gson.Gson;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataAdapter;
import org.ywzj.rvp.weapon.data.RVP_TerminalGuidanceData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_GuidancePhaseStateTest {

    private final Gson gson = new Gson();

    @Test
    void noTerminalConfigurationNeverTransitions() {
        RVP_GuidancePhaseState state = new RVP_GuidancePhaseState();

        assertFalse(state.update(null, context(100, 0, 0)));
        assertEquals(RVP_GuidancePhase.MAIN, state.phase());
    }

    @Test
    void transitionsByTickAndNeverReturnsToMain() {
        RVP_TerminalGuidanceData terminal = terminal("""
                {"guidance_start_tick":40}
                """);
        RVP_GuidancePhaseState state = new RVP_GuidancePhaseState();

        assertFalse(state.update(terminal, context(39, 10, 10)));
        assertTrue(state.update(terminal, context(40, 10, 10)));
        assertTrue(state.isTerminal());
        assertFalse(state.update(terminal, context(0, 1000, 1000)));
        assertEquals(RVP_GuidancePhase.TERMINAL, state.phase());
    }

    @Test
    void transitionsByThreeDimensionalDistance() {
        RVP_TerminalGuidanceData terminal = terminal("""
                {"guidance_start_dist":80}
                """);

        assertFalse(RVP_GuidancePhaseState.matches(terminal, context(0, 80.1, 10)));
        assertTrue(RVP_GuidancePhaseState.matches(terminal, context(0, 80, 10)));
    }

    @Test
    void transitionsByHorizontalDistance() {
        RVP_TerminalGuidanceData terminal = terminal("""
                {"guidance_start_horizontal_dist":60}
                """);

        assertFalse(RVP_GuidancePhaseState.matches(terminal, context(0, 20, 60.1)));
        assertTrue(RVP_GuidancePhaseState.matches(terminal, context(0, 100, 60)));
    }

    @Test
    void multipleTransitionConditionsUseAndSemantics() {
        RVP_TerminalGuidanceData terminal = terminal("""
                {
                  "guidance_start_tick":40,
                  "guidance_start_dist":80,
                  "guidance_start_horizontal_dist":60
                }
                """);

        assertFalse(RVP_GuidancePhaseState.matches(terminal, context(39, 70, 50)));
        assertFalse(RVP_GuidancePhaseState.matches(terminal, context(40, 81, 50)));
        assertFalse(RVP_GuidancePhaseState.matches(terminal, context(40, 70, 61)));
        assertTrue(RVP_GuidancePhaseState.matches(terminal, context(40, 70, 50)));
    }

    @Test
    void terminalWithoutTriggerConditionsTakesOverImmediately() {
        RVP_TerminalGuidanceData terminal = terminal("{}");

        assertTrue(RVP_GuidancePhaseState.matches(terminal, context(0, -1, -1)));
    }

    private RVP_TerminalGuidanceData terminal(String terminalJson) {
        String json = """
                {"guidance_data":{"guidance_type":"GPS","terminal_guidance":%s}}
                """.formatted(terminalJson);
        return gson.fromJson(json, GuidanceHolder.class).guidanceData.getTerminalGuidance();
    }

    private static RVP_GuidanceTransitionContext context(int tick, double distance, double horizontal) {
        return new RVP_GuidanceTransitionContext(tick, distance, horizontal);
    }

    private static final class GuidanceHolder {
        @SerializedName("guidance_data")
        @JsonAdapter(RVP_GuidanceDataAdapter.class)
        private RVP_GuidanceData guidanceData;
    }
}
