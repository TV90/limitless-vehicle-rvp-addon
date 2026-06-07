package org.ywzj.rvp.guidance.source;

import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceContext;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceSource;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;

public final class RVP_NoneGuidanceSource implements RVP_GuidanceSource {

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.NONE;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceContext context, RVP_GuidanceData.Source source) {
        return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.NONE);
    }
}
