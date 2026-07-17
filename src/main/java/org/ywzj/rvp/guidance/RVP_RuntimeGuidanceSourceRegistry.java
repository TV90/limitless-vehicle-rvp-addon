package org.ywzj.rvp.guidance;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeGpsGuidanceSource;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeNoneGuidanceSource;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeSarhGuidanceSource;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeArhGuidanceSource;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeAirGuidanceSource;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeArmGuidanceSource;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeIrGuidanceSource;

import java.util.EnumMap;
import java.util.Map;

public final class RVP_RuntimeGuidanceSourceRegistry {

    private static final Map<RVP_EnumGuidanceType, RVP_RuntimeGuidanceSource> SOURCES =
            new EnumMap<>(RVP_EnumGuidanceType.class);

    static {
        register(new RVP_RuntimeNoneGuidanceSource());
        register(new RVP_RuntimeGpsGuidanceSource());
        register(new RVP_RuntimeSarhGuidanceSource());
        register(new RVP_RuntimeArhGuidanceSource());
        register(new RVP_RuntimeArmGuidanceSource());
        register(new RVP_RuntimeIrGuidanceSource());
        register(new RVP_RuntimeAirGuidanceSource());
    }

    private RVP_RuntimeGuidanceSourceRegistry() {}

    public static void register(RVP_RuntimeGuidanceSource source) {
        SOURCES.put(source.type(), source);
    }

    @Nullable
    public static RVP_RuntimeGuidanceSource get(RVP_EnumGuidanceType type) {
        return SOURCES.get(type);
    }
}
