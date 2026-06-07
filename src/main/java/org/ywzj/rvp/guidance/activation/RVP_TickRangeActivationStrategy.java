package org.ywzj.rvp.guidance.activation;

import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;

public final class RVP_TickRangeActivationStrategy implements RVP_GuidanceActivationStrategy {

    @Override
    public boolean isActive(RVP_GuidanceActivationData activation, RVP_GuidanceActivationContext context) {
        if (!activation.hasTickCondition()) {
            return true;
        }
        if (context.tick() < activation.getStartTick()) {
            return false;
        }
        return activation.getEndTick() < 0 || context.tick() <= activation.getEndTick();
    }
}
