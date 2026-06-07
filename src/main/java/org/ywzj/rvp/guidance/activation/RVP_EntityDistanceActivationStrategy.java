package org.ywzj.rvp.guidance.activation;

import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;

public final class RVP_EntityDistanceActivationStrategy implements RVP_GuidanceActivationStrategy {

    @Override
    public boolean isActive(RVP_GuidanceActivationData activation, RVP_GuidanceActivationContext context) {
        if (!activation.hasEntityDistanceCondition()) {
            return true;
        }
        if (!context.hasEntityTarget()) {
            return false;
        }
        double distance = context.entityDistance();
        if (distance < 0) {
            return false;
        }
        if (activation.getMinEntityDistance() > 0f && distance < activation.getMinEntityDistance()) {
            return false;
        }
        return activation.getMaxEntityDistance() <= 0f || distance <= activation.getMaxEntityDistance();
    }
}
