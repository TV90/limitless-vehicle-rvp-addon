package org.ywzj.rvp.guidance;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeGpsGuidanceSource;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeNoneGuidanceSource;

import java.util.EnumMap;
import java.util.Map;

public final class RVP_RuntimeGuidanceSourceRegistry {

    private static final Map<RVP_EnumGuidanceType, RVP_RuntimeGuidanceSource> SOURCES =
            new EnumMap<>(RVP_EnumGuidanceType.class);

    static {
        register(new RVP_RuntimeNoneGuidanceSource());
        register(new RVP_RuntimeGpsGuidanceSource());
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
