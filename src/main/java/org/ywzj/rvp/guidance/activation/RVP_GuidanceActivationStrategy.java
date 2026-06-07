package org.ywzj.rvp.guidance.activation;

import org.ywzj.rvp.weapon.data.RVP_GuidanceActivationData;

/**
 * Pluggable activation dimension for a guidance phase.
 */
public interface RVP_GuidanceActivationStrategy {

    boolean isActive(RVP_GuidanceActivationData activation, RVP_GuidanceActivationContext context);
}
