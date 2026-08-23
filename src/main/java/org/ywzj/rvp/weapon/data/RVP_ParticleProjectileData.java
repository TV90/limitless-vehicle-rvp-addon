package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * {@code effects_data.particle_projectile_data} 的纯粒子弹体视觉配置。
 */
public class RVP_ParticleProjectileData {

    /** 是否启用纯粒子弹体，默认 false；启用后类型化 Renderer 不绘制弹体模型。 */
    @SerializedName("enabled")
    private boolean enabled = false;

    /** 粒子类型资源 ID，默认空；启用且 ID 已注册客户端发射器时生成主体和尾迹。 */
    @SerializedName("particle_type")
    private String particleType = "";

    /** 主体和尾迹是否使用全亮光照，默认 true；纯粒子弹体启用时生效。 */
    @SerializedName("full_bright")
    private boolean fullBright = true;

    /** 主体粒子尺寸倍率，默认 0.4；纯粒子弹体启用时限制为非负有限值。 */
    @SerializedName("body_scale")
    private float bodyScale = 0.4f;

    /** 单个主体粒子寿命，单位 Tick，默认 3；纯粒子弹体启用时至少为 1。 */
    @SerializedName("body_lifetime_ticks")
    private int bodyLifetimeTicks = 3;

    /** 主体 RGB 十六进制颜色，默认 {@code #FFC247}；非法值回退默认颜色。 */
    @SerializedName("body_color")
    private String bodyColor = "#FFC247";

    /** 主体尺寸随机闪烁比例，范围 0～1，默认 0.08；每次生成主体粒子时生效。 */
    @SerializedName("body_flicker")
    private float bodyFlicker = 0.08f;

    /** 是否生成路径尾迹，默认 true；纯粒子弹体启用时生效。 */
    @SerializedName("trail_enabled")
    private boolean trailEnabled = true;

    /** 沿客户端实际运动段的尾迹采样间距，单位格，默认 0.2；最小限制为 0.02。 */
    @SerializedName("trail_spacing")
    private float trailSpacing = 0.2f;

    /** 单个尾迹粒子寿命，单位 Tick，默认 24；启用尾迹时至少为 1。 */
    @SerializedName("trail_lifetime_ticks")
    private int trailLifetimeTicks = 24;

    /** 尾迹出生尺寸倍率，默认 0.32；启用尾迹时限制为非负有限值。 */
    @SerializedName("trail_start_scale")
    private float trailStartScale = 0.32f;

    /** 尾迹消失尺寸倍率，默认 0.02；启用尾迹时限制为非负有限值。 */
    @SerializedName("trail_end_scale")
    private float trailEndScale = 0.02f;

    /** 尾迹出生透明度，范围 0～1，默认 0.9；启用尾迹时生效。 */
    @SerializedName("trail_start_alpha")
    private float trailStartAlpha = 0.9f;

    /** 尾迹消失透明度，范围 0～1，默认 0；启用尾迹时生效。 */
    @SerializedName("trail_end_alpha")
    private float trailEndAlpha = 0f;

    /** 尾迹出生 RGB 十六进制颜色，默认白色；非法值回退白色。 */
    @SerializedName("trail_start_color")
    private String trailStartColor = "#FFFFFF";

    /** 尾迹消失 RGB 十六进制颜色，默认白色；非法值回退白色。 */
    @SerializedName("trail_end_color")
    private String trailEndColor = "#FFFFFF";

    public boolean isEnabled() {
        return enabled;
    }

    public String getParticleType() {
        return particleType == null ? "" : particleType.trim();
    }

    public boolean isFullBright() {
        return fullBright;
    }

    public float getBodyScale() {
        return finiteNonNegative(bodyScale, 0.4f);
    }

    public int getBodyLifetimeTicks() {
        return Math.max(bodyLifetimeTicks, 1);
    }

    public int getBodyColorRgb() {
        return parseRgb(bodyColor, 0xFFC247);
    }

    public float getBodyFlicker() {
        return clamp01(bodyFlicker, 0.08f);
    }

    public boolean isTrailEnabled() {
        return trailEnabled;
    }

    public float getTrailSpacing() {
        return Math.max(finiteNonNegative(trailSpacing, 0.2f), 0.02f);
    }

    public int getTrailLifetimeTicks() {
        return Math.max(trailLifetimeTicks, 1);
    }

    public float getTrailStartScale() {
        return finiteNonNegative(trailStartScale, 0.32f);
    }

    public float getTrailEndScale() {
        return finiteNonNegative(trailEndScale, 0.02f);
    }

    public float getTrailStartAlpha() {
        return clamp01(trailStartAlpha, 0.9f);
    }

    public float getTrailEndAlpha() {
        return clamp01(trailEndAlpha, 0f);
    }

    public int getTrailStartColorRgb() {
        return parseRgb(trailStartColor, 0xFFFFFF);
    }

    public int getTrailEndColorRgb() {
        return parseRgb(trailEndColor, 0xFFFFFF);
    }

    private static float finiteNonNegative(float value, float fallback) {
        return Float.isFinite(value) ? Math.max(value, 0f) : fallback;
    }

    private static float clamp01(float value, float fallback) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(value, 1f)) : fallback;
    }

    private static int parseRgb(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.length() != 6) {
            return fallback;
        }
        try {
            return Integer.parseInt(normalized, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
