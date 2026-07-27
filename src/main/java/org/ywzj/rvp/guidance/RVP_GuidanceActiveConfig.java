package org.ywzj.rvp.guidance;

import org.ywzj.rvp.weapon.data.RVP_Range;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RVP_GuidanceActiveConfig(
        RVP_GuidancePhase phase,
        RVP_EnumGuidanceType guidanceType,
        RVP_Range<Integer> tickRange,
        RVP_Range<Float> targetDistanceRange,
        RVP_Range<Float> altitudeRange,
        int maxLockAngle,
        int maxGuidanceAngle,
        Integer scanIntervalTick,
        boolean predictTargetPos,
        float predictTargetPosGain,
        float maxLateralAccel,
        int predictTargetPosStartTick,
        Float topAttackHeight,
        Integer cruiseStartTick,
        float cruiseEndHorizontalDist,
        float cruiseGravityScale,
        float cruiseLevelingFactor,
        Map<RVP_Range<Float>, RVP_Range<Float>> angleGate,
        int angleGateLockOutTick,
        int activeRadarActivationRange,
        boolean enableInertialGuidance,
        boolean ignoreFlares,
        boolean ignoreChaff,
        float jamResistance,
        float dircmResistance,
        boolean homeOnJam,
        float decoyFilter,
        int radiationPulseMemoryTick,
        int armMemoryTick,
        float armLockedEmitterBonus,
        float gpsSpreadRadius,
        int hitlMaxTurnDegPerTick,
        String hitlSignalSource,
        int hitlMaxControlDist,
        int hitlMaxControlTick,
        int hitlMaxLookOffset,
        List<String> hitlVideoModes,
        boolean semiCorrectionEnabled,
        float semiCorrectionStiffness,
        float semiCorrectionDamping,
        float semiCorrectionWobble
) {
    public RVP_GuidanceActiveConfig {
        phase = phase == null ? RVP_GuidancePhase.MAIN : phase;
        guidanceType = guidanceType == null ? RVP_EnumGuidanceType.NONE : guidanceType;
        maxLockAngle = Math.max(maxLockAngle, 0);
        maxGuidanceAngle = Math.max(maxGuidanceAngle, 0);
        scanIntervalTick = scanIntervalTick == null ? null : Math.max(scanIntervalTick, 1);
        predictTargetPosGain = Math.max(predictTargetPosGain, 0f);
        maxLateralAccel = Math.max(maxLateralAccel, 0f);
        predictTargetPosStartTick = Math.max(predictTargetPosStartTick, 0);
        cruiseStartTick = cruiseStartTick == null ? null : Math.max(cruiseStartTick, 0);
        cruiseEndHorizontalDist = Math.max(cruiseEndHorizontalDist, 0f);
        cruiseLevelingFactor = Math.max(cruiseLevelingFactor, 0f);
        angleGate = immutableMapAllowingNullValues(angleGate);
        angleGateLockOutTick = Math.max(angleGateLockOutTick, 0);
        activeRadarActivationRange = Math.max(activeRadarActivationRange, 0);
        jamResistance = Math.max(jamResistance, 0f);
        dircmResistance = Math.max(dircmResistance, 0f);
        decoyFilter = Math.max(decoyFilter, 0f);
        radiationPulseMemoryTick = Math.max(radiationPulseMemoryTick, 0);
        armMemoryTick = Math.max(armMemoryTick, 0);
        gpsSpreadRadius = Math.max(gpsSpreadRadius, 0f);
        hitlMaxTurnDegPerTick = Math.max(hitlMaxTurnDegPerTick, 0);
        hitlSignalSource = hitlSignalSource == null ? "RADIO" : hitlSignalSource;
        hitlMaxControlDist = Math.max(hitlMaxControlDist, 0);
        hitlMaxControlTick = Math.max(hitlMaxControlTick, 0);
        hitlMaxLookOffset = Math.max(hitlMaxLookOffset, 0);
        hitlVideoModes = hitlVideoModes == null ? List.of() : List.copyOf(hitlVideoModes);
        semiCorrectionStiffness = Math.max(semiCorrectionStiffness, 0f);
        semiCorrectionDamping = Math.max(semiCorrectionDamping, 0f);
        semiCorrectionWobble = Math.max(semiCorrectionWobble, 0f);
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
