package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

import java.util.OptionalDouble;

/**
 * {@code projectile_data.wind_data} 的服务器权威风漂配置。
 */
public class RVP_WindData {

    /** 是否启用风漂，默认 false；仅非机枪类 RVP 实体弹道生效。 */
    @SerializedName("enabled")
    private boolean enabled = false;

    /**
     * 风向来源，默认 {@code parent_facing_reverse}；该模式仅对子弹药生效并在释放瞬间
     * 固化母弹当前朝向的反向，也可写 {@code north:<角度>} 固定世界水平风向，未知值按禁用处理。
     */
    @SerializedName("direction_mode")
    private String directionMode = "parent_facing_reverse";

    /** 目标风速，单位格/Tick，默认 0.1；启用风漂时限制为非负有限值。 */
    @SerializedName("speed")
    private float speed = 0.1f;

    /** 每 Tick 向目标风速收敛的比例，范围 0～1，默认 0.03；启用风漂时生效。 */
    @SerializedName("response")
    private float response = 0.03f;

    /** 风向垂直分量保留比例，范围 0～1，默认 0；0 表示仅使用水平风向。 */
    @SerializedName("vertical_factor")
    private float verticalFactor = 0f;

    /** 每 Tick 水平确定性扰动幅度，单位格/Tick，默认 0；启用风漂时生效。 */
    @SerializedName("turbulence")
    private float turbulence = 0f;

    /**
     * 水平扰动方向的基础旋转频率，单位周期/Tick，默认 0.02；仅风漂已启用且
     * {@code turbulence > 0} 时生效，有限值限制为 0～0.5。
     */
    @SerializedName("turbulence_frequency")
    private float turbulenceFrequency = 0.02f;

    public boolean isEnabled() {
        return enabled && isDirectionModeSupported() && getSpeed() > 0f && getResponse() > 0f;
    }

    public boolean isParentFacingReverse() {
        return "parent_facing_reverse".equalsIgnoreCase(directionMode);
    }

    public boolean isDirectionModeSupported() {
        return isParentFacingReverse() || getFixedNorthAngleDegrees().isPresent();
    }

    /**
     * 解析 {@code north:<角度>} 固定水平风向；正角从北方顺时针旋转，单位度。
     *
     * @return 合法有限角度；非固定模式或非法值返回空
     */
    public OptionalDouble getFixedNorthAngleDegrees() {
        if (directionMode == null) {
            return OptionalDouble.empty();
        }
        String normalized = directionMode.trim();
        int separator = normalized.indexOf(':');
        if (separator <= 0 || !"north".equalsIgnoreCase(normalized.substring(0, separator).trim())) {
            return OptionalDouble.empty();
        }
        String angleText = normalized.substring(separator + 1).trim();
        if (angleText.isEmpty()) {
            return OptionalDouble.empty();
        }
        try {
            double angle = Double.parseDouble(angleText);
            return Double.isFinite(angle) ? OptionalDouble.of(angle) : OptionalDouble.empty();
        } catch (NumberFormatException ignored) {
            return OptionalDouble.empty();
        }
    }

    public float getSpeed() {
        return Float.isFinite(speed) ? Math.max(speed, 0f) : 0f;
    }

    public float getResponse() {
        return Float.isFinite(response) ? Math.max(0f, Math.min(response, 1f)) : 0f;
    }

    public float getVerticalFactor() {
        return Float.isFinite(verticalFactor) ? Math.max(0f, Math.min(verticalFactor, 1f)) : 0f;
    }

    public float getTurbulence() {
        return Float.isFinite(turbulence) ? Math.max(turbulence, 0f) : 0f;
    }

    public float getTurbulenceFrequency() {
        return Float.isFinite(turbulenceFrequency)
                ? Math.max(0f, Math.min(turbulenceFrequency, 0.5f))
                : 0.02f;
    }
}
