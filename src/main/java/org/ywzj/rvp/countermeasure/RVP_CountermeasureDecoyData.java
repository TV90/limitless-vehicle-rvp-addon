package org.ywzj.rvp.countermeasure;

import com.google.gson.annotations.SerializedName;

/**
 * 干扰物实体属性（RVP_CountermeasureDecoyData）。
 *
 * <p>字段说明见 {@code docs/plan/RVP干扰物重构数据模型/RVP 干扰物重构数据模型文档.md}
 * 的 RVP_CountermeasureDecoyData 表（驼峰字段名 ↔ JSON 全小写+下划线）。</p>
 */
public final class RVP_CountermeasureDecoyData {

    /** 单发干扰物存活 tick（有效干扰窗口）。 */
    @SerializedName("lifetime_tick")
    private int lifetimeTick = 160;

    /** 出膛初速（m/tick，沿发射装置瞄准方向，叠加载具速度）。 */
    @SerializedName("speed")
    private float speed = 0.5F;

    /** 下落加速度（热焰弹建议 0.02 漂浮更久；箔条建议 0 悬浮）。 */
    @SerializedName("gravity")
    private float gravity = 0.02F;

    /** 空气阻力系数，速度按 {@code velocity *= (1 - drag)} 衰减。 */
    @SerializedName("drag")
    private float drag = 0.05F;

    /** 发射散布半径（格，发射时随机偏移）。 */
    @SerializedName("spread")
    private float spread = 1.0F;

    /** 发光颜色（0xRRGGBB），作用于 billboard 贴图的颜色倍乘。 */
    @SerializedName("glow_color")
    private int glowColor = 0xFFFFFF;

    /** 光晕/billboard 尺寸倍率（光圈大小）。 */
    @SerializedName("halo_scale")
    private float haloScale = 1.0F;

    public int getLifetimeTick() {
        return Math.max(1, lifetimeTick);
    }

    public float getSpeed() {
        return Float.isFinite(speed) ? Math.max(0.0F, speed) : 0.5F;
    }

    public float getGravity() {
        return Float.isFinite(gravity) ? gravity : 0.02F;
    }

    public float getDrag() {
        return Float.isFinite(drag) ? Math.max(0.0F, drag) : 0.05F;
    }

    public float getSpread() {
        return Float.isFinite(spread) ? Math.max(0.0F, spread) : 1.0F;
    }

    public int getGlowColor() {
        return glowColor;
    }

    public float getHaloScale() {
        return Float.isFinite(haloScale) ? Math.max(0.05F, haloScale) : 1.0F;
    }
}
