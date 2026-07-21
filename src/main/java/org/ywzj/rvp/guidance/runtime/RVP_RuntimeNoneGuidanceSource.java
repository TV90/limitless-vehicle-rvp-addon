package org.ywzj.rvp.guidance.runtime;

import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;

public final class RVP_RuntimeNoneGuidanceSource implements RVP_RuntimeGuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.NONE;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        if (context.active().enableInertialGuidance()
                && context.projectile().getLastGuidancePos() != null) {
            return RVP_GuidanceIntent.point(
                    context.projectile().getLastGuidancePos(),
                    false,
                    1.0,
                    RVP_EnumGuidanceType.NONE
            );
        }
        return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.NONE);
    }
}
