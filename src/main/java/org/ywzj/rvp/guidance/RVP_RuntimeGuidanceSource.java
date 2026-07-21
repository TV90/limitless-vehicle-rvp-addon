package org.ywzj.rvp.guidance;

public interface RVP_RuntimeGuidanceSource {

    RVP_EnumGuidanceType type();

    RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context);
}
