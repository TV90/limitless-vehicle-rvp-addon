package org.ywzj.rvp.guidance;

import org.ywzj.rvp.weapon.data.RVP_Range;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RVP_GuidanceLaunchConfig(
        RVP_EnumGuidanceType guidanceType,
        RVP_Range<Float> targetDistanceRange,
        RVP_Range<Float> altitudeRange,
        boolean enableIrHmd,
        int maxLockAngle,
        int maxOffAxisLockAngle,
        Map<RVP_Range<Float>, RVP_Range<Float>> angleGate
) {
    public RVP_GuidanceLaunchConfig {
        guidanceType = guidanceType == null ? RVP_EnumGuidanceType.NONE : guidanceType;
        maxLockAngle = Math.max(maxLockAngle, 0);
        maxOffAxisLockAngle = Math.max(maxOffAxisLockAngle, 0);
        angleGate = immutableMapAllowingNullValues(angleGate);
    }

    public float maxLockHalfAngle() {
        return maxLockAngle * 0.5f;
    }

    private static <K, V> Map<K, V> immutableMapAllowingNullValues(Map<K, V> source) {
        if (source == null) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
