package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 单条飞行距离伤害衰减规则（MCH {@code BulletDecay} 等价）。
 * 写在 {@link RVP_DamageData} 的 {@code decay} 数组中，多条规则伤害系数相乘。
 */
public class RVP_DamageDecayRuleData {

    /**
     * 衰减类型：{@code none}、{@code linear}、{@code exponential}/{@code exp}、
     * {@code curve}/{@code polynomial}、{@code segmented}。
     */
    @SerializedName("type")
    private String type = "none";

    /**
     * Segmented: pairs {@code [start_distance, damage_multiplier]}.
     * Uses the last segment whose {@code start_distance <= traveled} (MCH {@link mcheli.weapon.MCH_BulletDecaySegmented}).
     */
    @SerializedName("segments")
    private List<float[]> segments = new ArrayList<>();

    @SerializedName("start_distance")
    private float startDistance = 0f;

    @SerializedName("end_distance")
    private float endDistance = 256f;

    @SerializedName("min_factor")
    private float minFactor = 0.25f;

    /** Exponential: {@code exp(-rate * distance)}, clamped to min_factor. */
    @SerializedName("rate")
    private float rate = 0.002f;

    /** Curve: {@code 1 - (distance/range)^power}, clamped to min_factor. */
    @SerializedName("range")
    private float range = 256f;

    @SerializedName("power")
    private float power = 2f;

    public String getType() {
        return type == null ? "none" : type;
    }

    public float evaluate(float distanceTraveled) {
        float d = Math.max(distanceTraveled, 0f);
        return switch (getType().toLowerCase(Locale.ROOT)) {
            case "segmented" -> evaluateSegmented(d);
            case "linear" -> evaluateLinear(d);
            case "exponential", "exp" -> evaluateExponential(d);
            case "curve", "polynomial" -> evaluateCurve(d);
            case "none" -> 1f;
            default -> 1f;
        };
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

    private float evaluateLinear(float distance) {
        float start = startDistance;
        float end = Math.max(endDistance, start + 1e-3f);
        if (distance <= start) {
            return 1f;
        }
        if (distance >= end) {
            return Mth.clamp(minFactor, 0f, 1f);
        }
        float t = (distance - start) / (end - start);
        return Mth.clamp(1f - t * (1f - minFactor), minFactor, 1f);
    }

    private float evaluateExponential(float distance) {
        float r = Math.max(rate, 0f);
        float factor = (float) Math.exp(-r * distance);
        return Mth.clamp(Math.max(factor, minFactor), 0f, 1f);
    }

    private float evaluateCurve(float distance) {
        float maxRange = Math.max(range, 1e-3f);
        float p = Math.max(power, 0.01f);
        float t = Mth.clamp(distance / maxRange, 0f, 1f);
        float factor = 1f - (float) Math.pow(t, p);
        return Mth.clamp(Math.max(factor, minFactor), 0f, 1f);
    }
}
