package org.ywzj.rvp.guidance;

import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

/**
 * Pluggable guidance source. Sources only decide target/steering and should not
 * perform explosion, damage, submunition or generic projectile effects.
 */
public interface RVP_GuidanceSource {

    RVP_EnumGuidanceType type();

    RVP_GuidanceIntent evaluate(RVP_GuidanceContext context, RVP_GuidanceData.Source source);
}
