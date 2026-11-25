package org.ywzj.rvp.guidance.runtime;

import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;

public record RVP_RuntimeOpticalGuidanceSource(RVP_EnumGuidanceType type) implements RVP_RuntimeGuidanceSource {
    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        return context.projectile() instanceof RVP_MissileEntity missile
                ? missile.opticalTrack().evaluate(missile, context)
                : RVP_GuidanceIntent.failed(type);
    }
}
