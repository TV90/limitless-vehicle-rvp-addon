package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 单条伤害衰减规则，写在 {@link RVP_CollisionData#getDamageDecayRules()}。
 */
public class RVP_DamageDecayRuleData {

    /**
     * {@code distance}（默认，已飞行距离/米，多条相乘）或 {@code angle}（入射角/度，分段互斥）。
     */
    @SerializedName("domain")
    private String domain = "distance";

    @SerializedName("type")
    private String type = "none";

    @SerializedName("segments")
    private List<float[]> segments = new ArrayList<>();

    @SerializedName("start_distance")
    private float startDistance = 0f;

    @SerializedName("end_distance")
    private float endDistance = 256f;

    /** 未写时：距离衰减起点为 1，终点见 {@link #minFactor}。 */
    @SerializedName("start_factor")
    private Float startFactor;

    /** 未写时：使用 {@link #minFactor}（默认 0.25）。 */
    @SerializedName("end_factor")
    private Float endFactor;

    /** 未写时默认 {@value #DEFAULT_MIN_FACTOR}（用于 {@code exponential} 等）。 */
    @SerializedName("min_factor")
    private Float minFactor;

    private static final float DEFAULT_MIN_FACTOR = 0.25f;

    @SerializedName("rate")
    private float rate = 0.002f;

    @SerializedName("range")
    private float range = 256f;

    @SerializedName("power")
    private float power = 2f;

    public String getType() {
        return type == null ? "none" : type;
    }

    public String getDomain() {
        return domain == null ? "distance" : domain;
    }

    public boolean isAngleDomain() {
        return "angle".equalsIgnoreCase(getDomain());
    }

    public float getStartDistance() {
        return startDistance;
    }

    public float getBandEnd() {
        if (endDistance > startDistance + 1e-4f) {
            return endDistance;
        }
        if (isAngleDomain()) {
            return startDistance + 1e-3f;
        }
        return Float.MAX_VALUE;
    }

    public float evaluateSample(float sample) {
        float value = Math.max(sample, 0f);
        return switch (getType().toLowerCase(Locale.ROOT)) {
            case "constant" -> resolveStartFactor();
            case "segmented" -> evaluateSegmented(value);
            case "linear" -> evaluateLinear(value);
            case "exponential", "exp" -> evaluateExponential(value);
            case "curve", "polynomial" -> evaluateCurve(value);
            case "none" -> 1f;
            default -> 1f;
        };
    }

    private float resolveStartFactor() {
        if (startFactor != null) {
            return Mth.clamp(startFactor, 0f, 1f);
        }
        return 1f;
    }

    private float resolveEndFactor() {
        if (endFactor != null) {
            return Mth.clamp(endFactor, 0f, 1f);
        }
        return Mth.clamp(resolveMinFactor(), 0f, 1f);
    }

    private float resolveMinFactor() {
        return minFactor != null ? minFactor : DEFAULT_MIN_FACTOR;
    }

    private float evaluateSegmented(float distance) {
        if (segments == null || segments.isEmpty()) {
            return 1f;
        }
        float factor = 1f;
        for (float[] pair : segments) {
            if (pair == null || pair.length < 2) {
                continue;
            }
            if (distance >= pair[0]) {
                factor = pair[1];
            }
        }
        return Mth.clamp(factor, 0f, 1f);
    }

    private float evaluateLinear(float value) {
        float start = startDistance;
        float end = Math.max(endDistance, start + 1e-3f);
        float factorStart = resolveStartFactor();
        float factorEnd = resolveEndFactor();
        if (value <= start) {
            return factorStart;
        }
        if (value >= end) {
            return factorEnd;
        }
        float t = (value - start) / (end - start);
        return Mth.clamp(Mth.lerp(t, factorStart, factorEnd), 0f, 1f);
    }

    private float evaluateExponential(float distance) {
        float r = Math.max(rate, 0f);
        float factor = (float) Math.exp(-r * distance);
        return Mth.clamp(Math.max(factor, resolveEndFactor()), 0f, 1f);
    }

    private float evaluateCurve(float value) {
        if (isAngleDomain() || (hasExplicitBandFactors() && endDistance > startDistance + 1e-3f)) {
            float start = startDistance;
            float end = Math.max(endDistance, start + 1e-3f);
            float factorStart = resolveStartFactor();
            float factorEnd = resolveEndFactor();
            if (value <= start) {
                return factorStart;
            }
            if (value >= end) {
                return factorEnd;
            }
            float t = Mth.clamp((value - start) / (end - start), 0f, 1f);
            float p = Math.max(power, 0.01f);
            float curved = (float) Math.pow(t, p);
            return Mth.clamp(Mth.lerp(curved, factorStart, factorEnd), 0f, 1f);
        }
        float maxRange = Math.max(range, 1e-3f);
        float p = Math.max(power, 0.01f);
        float t = Mth.clamp(value / maxRange, 0f, 1f);
        float factor = 1f - (float) Math.pow(t, p);
        return Mth.clamp(Math.max(factor, resolveEndFactor()), 0f, 1f);
    }

    private boolean hasExplicitBandFactors() {
        return startFactor != null || endFactor != null;
    }
}
