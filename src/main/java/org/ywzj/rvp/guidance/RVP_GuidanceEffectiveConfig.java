package org.ywzj.rvp.guidance;

import org.ywzj.rvp.weapon.data.RVP_GuidanceSeekerData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceSteeringData;

/**
 * Merged steering/seeker snapshot for one guidance tick.
 */
public record RVP_GuidanceEffectiveConfig(
        RVP_GuidanceSteeringData steering,
        RVP_GuidanceSeekerData seeker,
        RVP_EnumGuidanceType activeSourceType
) {
}
