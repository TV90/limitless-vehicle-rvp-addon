package org.ywzj.rvp.guidance;

import org.ywzj.rvp.guidance.source.RVP_ArhGuidanceSource;
import org.ywzj.rvp.guidance.source.RVP_ArmGuidanceSource;
import org.ywzj.rvp.guidance.source.RVP_GpsGuidanceSource;
import org.ywzj.rvp.guidance.source.RVP_IogGuidanceSource;
import org.ywzj.rvp.guidance.source.RVP_IrGuidanceSource;
import org.ywzj.rvp.guidance.source.RVP_MclosGuidanceSource;
import org.ywzj.rvp.guidance.source.RVP_NoneGuidanceSource;
import org.ywzj.rvp.guidance.source.RVP_SaclosGuidanceSource;
import org.ywzj.rvp.guidance.source.RVP_SarhGuidanceSource;
import java.util.EnumMap;
import java.util.Map;

/**
 * Maps {@link RVP_EnumGuidanceType} to pluggable source implementations.
 */
public final class RVP_GuidanceSourceRegistry {

    private static final Map<RVP_EnumGuidanceType, RVP_GuidanceSource> SOURCES = new EnumMap<>(RVP_EnumGuidanceType.class);

    static {
        register(new RVP_NoneGuidanceSource());
        register(new RVP_IogGuidanceSource());
        register(new RVP_GpsGuidanceSource());
        register(new RVP_MclosGuidanceSource());
        register(new RVP_SaclosGuidanceSource());
        register(new RVP_IrGuidanceSource());
        register(new RVP_SarhGuidanceSource());
        register(new RVP_ArhGuidanceSource());
        register(new RVP_ArmGuidanceSource());
    }

    private RVP_GuidanceSourceRegistry() {}

    public static void register(RVP_GuidanceSource source) {
        SOURCES.put(source.type(), source);
    }

    public static RVP_GuidanceSource get(RVP_EnumGuidanceType type) {
        RVP_GuidanceSource source = SOURCES.get(type);
        return source != null ? source : SOURCES.get(RVP_EnumGuidanceType.NONE);
    }
}
