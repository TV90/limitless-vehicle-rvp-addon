package org.ywzj.rvp.guidance.activation;

import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;

public final class RVP_AltitudeActivationStrategy implements RVP_GuidanceActivationStrategy {

    @Override
    public boolean isActive(RVP_GuidanceActivationData activation, RVP_GuidanceActivationContext context) {
        if (!activation.hasAltitudeCondition()) {
            return true;
        }
        double altitude = context.altitudeAgl();
        if (activation.getMinAltitudeAgl() > 0f && altitude < activation.getMinAltitudeAgl()) {
            return false;
        }
        return activation.getMaxAltitudeAgl() <= 0f || altitude <= activation.getMaxAltitudeAgl();
    }
}
