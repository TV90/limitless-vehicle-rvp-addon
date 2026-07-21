package org.ywzj.rvp.guidance.runtime;

import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;

/** Explicit placeholder for a declared but not yet implemented guidance mechanism. */
public final class RVP_RuntimeUnsupportedGuidanceSource implements RVP_RuntimeGuidanceSource {

    private final RVP_EnumGuidanceType type;

    public RVP_RuntimeUnsupportedGuidanceSource(RVP_EnumGuidanceType type) {
        this.type = type;
    }

    @Override
    public RVP_EnumGuidanceType type() {
        return type;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        return RVP_GuidanceIntent.failed(type);
    }
}
