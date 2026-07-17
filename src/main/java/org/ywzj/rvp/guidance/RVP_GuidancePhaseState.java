package org.ywzj.rvp.guidance;

import org.ywzj.rvp.weapon.data.RVP_TerminalGuidanceData;

/** Owns the one-way MAIN to TERMINAL transition for one projectile. */
public final class RVP_GuidancePhaseState {

    private RVP_GuidancePhase phase = RVP_GuidancePhase.MAIN;

    public RVP_GuidancePhase phase() {
        return phase;
    }

    public boolean isTerminal() {
        return phase == RVP_GuidancePhase.TERMINAL;
    }

    public boolean update(
            RVP_TerminalGuidanceData terminal,
            RVP_GuidanceTransitionContext context
    ) {
        if (phase == RVP_GuidancePhase.TERMINAL || terminal == null || context == null) {
            return false;
        }
        if (!matches(terminal, context)) {
            return false;
        }
        phase = RVP_GuidancePhase.TERMINAL;
        return true;
    }

    public static boolean matches(
            RVP_TerminalGuidanceData terminal,
            RVP_GuidanceTransitionContext context
    ) {
        if (terminal == null || context == null) {
            return false;
        }

        Integer startTick = terminal.getGuidanceStartTick();
        if (startTick != null && context.tick() < startTick) {
            return false;
        }

        Float startDistance = terminal.getGuidanceStartDist();
        if (startDistance != null
                && (context.targetDistance() < 0 || context.targetDistance() > startDistance)) {
            return false;
        }

        Float startHorizontalDistance = terminal.getGuidanceStartHorizontalDist();
        if (startHorizontalDistance != null
                && (context.targetHorizontalDistance() < 0
                || context.targetHorizontalDistance() > startHorizontalDistance)) {
            return false;
        }
        return true;
    }
}
