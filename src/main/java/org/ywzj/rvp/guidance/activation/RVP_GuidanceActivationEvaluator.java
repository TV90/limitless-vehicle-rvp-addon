package org.ywzj.rvp.guidance.activation;

import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;

import java.util.List;

/**
 * Evaluates all activation strategies (AND) for one guidance phase.
 */
public final class RVP_GuidanceActivationEvaluator {

    private static final List<RVP_GuidanceActivationStrategy> STRATEGIES = List.of(
            new RVP_TickRangeActivationStrategy(),
            new RVP_TargetDistanceActivationStrategy(),
            new RVP_EntityDistanceActivationStrategy(),
            new RVP_AltitudeActivationStrategy(),
            new RVP_TargetPresenceActivationStrategy(),
            new RVP_IlluminationActivationStrategy()
    );

    private RVP_GuidanceActivationEvaluator() {}

    public static boolean isPhaseActive(RVP_GuidanceActivationData activation, RVP_GuidanceActivationContext context) {
        if (activation == null) {
            return true;
        }
        for (RVP_GuidanceActivationStrategy strategy : STRATEGIES) {
            if (!strategy.isActive(activation, context)) {
                return false;
            }
        }
        return true;
    }
}
