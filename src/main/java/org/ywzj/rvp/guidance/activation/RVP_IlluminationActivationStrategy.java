package org.ywzj.rvp.guidance.activation;

import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;

public final class RVP_IlluminationActivationStrategy implements RVP_GuidanceActivationStrategy {

    @Override
    public boolean isActive(RVP_GuidanceActivationData activation, RVP_GuidanceActivationContext context) {
        return !activation.isRequireIllumination() || context.hasIllumination();
    }
}
