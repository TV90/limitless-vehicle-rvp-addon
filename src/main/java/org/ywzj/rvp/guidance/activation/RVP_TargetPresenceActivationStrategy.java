package org.ywzj.rvp.guidance.activation;

import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;

public final class RVP_TargetPresenceActivationStrategy implements RVP_GuidanceActivationStrategy {

    @Override
    public boolean isActive(RVP_GuidanceActivationData activation, RVP_GuidanceActivationContext context) {
        if (activation.isRequireEntityTarget() && !context.hasEntityTarget()) {
            return false;
        }
        if (activation.isRequireTarget() && !context.hasTargetPoint() && !context.hasEntityTarget()) {
            return false;
        }
        return true;
    }
}
