package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public class RVP_MiscData {

    @SerializedName("missile_name_on_hud")
    private Map<RVP_Range<Float>, String> missileNameOnHud = createDefaultHudNames();

    @SerializedName("missile_name_on_radar")
    private Map<RVP_Range<Float>, String> missileNameOnRadar = createDefaultRadarNames();

    @SerializedName("signal_intensity_factor_on_radar")
    private Map<RVP_Range<Float>, Float> signalIntensityFactorOnRadar = createDefaultSignalIntensityFactors();

    public Map<RVP_Range<Float>, String> getMissileNameOnHud() {
        return missileNameOnHud == null ? createDefaultHudNames() : missileNameOnHud;
    }

    public Map<RVP_Range<Float>, String> getMissileNameOnRadar() {
        return missileNameOnRadar == null ? createDefaultRadarNames() : missileNameOnRadar;
    }

    public Map<RVP_Range<Float>, Float> getSignalIntensityFactorOnRadar() {
        return signalIntensityFactorOnRadar == null
                ? createDefaultSignalIntensityFactors()
                : signalIntensityFactorOnRadar;
    }

    @Nullable
    public String resolveMissileNameOnHud(float distance) {
        return resolveByRange(getMissileNameOnHud(), distance, null);
    }

    @Nullable
    public String resolveMissileNameOnRadar(float distance) {
        return resolveByRange(getMissileNameOnRadar(), distance, null);
    }

    public float resolveSignalIntensityFactorOnRadar(float distance) {
        Float resolved = resolveByRange(getSignalIntensityFactorOnRadar(), distance, 1.0f);
        if (resolved == null || Float.isNaN(resolved) || Float.isInfinite(resolved)) {
            return 1.0f;
        }
        return Math.max(resolved, 0f);
    }

    @Nullable
    private static <T> T resolveByRange(
            Map<RVP_Range<Float>, T> values,
            float distance,
            @Nullable T fallback
    ) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        float sample = normalizeDistance(distance);
        for (Map.Entry<RVP_Range<Float>, T> entry : values.entrySet()) {
            RVP_Range<Float> range = entry.getKey();
            if (range == null || !range.contains(sample)) {
                continue;
            }
            return entry.getValue();
        }
        return fallback;
    }

    private static float normalizeDistance(float distance) {
        if (Float.isNaN(distance)) {
            return 0f;
        }
        if (Float.isInfinite(distance)) {
            return distance > 0f ? Float.MAX_VALUE : 0f;
        }
        return Math.max(distance, 0f);
    }

    private static Map<RVP_Range<Float>, String> createDefaultHudNames() {
        Map<RVP_Range<Float>, String> values = new LinkedHashMap<>();
        values.put(RVP_Range.closed(0f, 500f), "MSL");
        values.put(RVP_Range.of(new RVP_Range.Interval<>(500f, null)), null);
        return values;
    }

    private static Map<RVP_Range<Float>, String> createDefaultRadarNames() {
        Map<RVP_Range<Float>, String> values = new LinkedHashMap<>();
        values.put(RVP_Range.of(new RVP_Range.Interval<>(20f, null)), "MSL");
        return values;
    }

    private static Map<RVP_Range<Float>, Float> createDefaultSignalIntensityFactors() {
        Map<RVP_Range<Float>, Float> values = new LinkedHashMap<>();
        values.put(RVP_Range.of(new RVP_Range.Interval<>(0f, null)), 1.0f);
        return values;
    }
}
