package org.ywzj.rvp.guidance.activation;

import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;

public final class RVP_TargetDistanceActivationStrategy implements RVP_GuidanceActivationStrategy {

    @Override
    public boolean isActive(RVP_GuidanceActivationData activation, RVP_GuidanceActivationContext context) {
        if (!activation.hasTargetDistanceCondition()) {
            return true;
        }
        double distance = context.targetPointDistance();
        if (distance < 0) {
            return false;
        }
        if (activation.getMinTargetDistance() > 0f && distance < activation.getMinTargetDistance()) {
            return false;
        }
        return activation.getMaxTargetDistance() <= 0f || distance <= activation.getMaxTargetDistance();
    }
}
