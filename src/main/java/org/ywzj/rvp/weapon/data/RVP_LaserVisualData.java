package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * MCH {@code BulletColor}/{@code Radius}/{@code TimeFuse}/{@code TurningFactor} parity for {@code rvp:laser}.
 */
public class RVP_LaserVisualData {

    /** RGBA 0–255，对应 MCH {@code BulletColor = r, g, b, a}。 */
    @SerializedName("color")
    private List<Integer> color;

    /** 光束宽度（方块单位），对应 MCH {@code Radius}。 */
    @SerializedName("width")
    private float width = 0.2f;

    /**
     * 停火后光束仍显示的 tick 数（对应 MCH {@code TimeFuse}）。
     * 连发时每次开火会刷新该计时；渲染每帧从当前炮口追踪到射线命中点。
     */
    @SerializedName("duration_tick")
    private int durationTick = 20;

    /** 是否脉动宽度，MCH 激光默认为 true。 */
    @SerializedName("pulsate")
    private boolean pulsate = true;

    /**
     * 从起点起跳过多少距离再绘制光束，对应 MCH {@code TurningFactor} / {@code LaserStartDistance}。
     */
    @SerializedName("render_start_distance")
    private double renderStartDistance = 0.0;

    /** 分段渲染每段长度（格）；过长光束会合并段数至 {@link #getMaxSegments()}。 */
    @SerializedName("segment_length")
    private float segmentLength = 0.4f;

    private static final int DEFAULT_MAX_SEGMENTS = 64;

    public int toArgb() {
        return toArgb(1f);
    }

    /** @param chargeRatio 0–1 while weapon is charging; scales alpha for a dimmer pre-fire beam. */
    public int toArgb(float chargeRatio) {
        if (color == null || color.size() < 3) {
            return 0xFFA0A0A0;
        }
        int r = clampChannel(color.size() > 0 ? color.get(0) : 160);
        int g = clampChannel(color.size() > 1 ? color.get(1) : 160);
        int b = clampChannel(color.size() > 2 ? color.get(2) : 160);
        int a = clampChannel(color.size() > 3 ? color.get(3) : 255);
        float scale = Mth.clamp(chargeRatio, 0.15f, 1f);
        a = clampChannel(Math.round(a * scale));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public RVP_LaserVisualData withChargeRatio(float chargeRatio) {
        RVP_LaserVisualData copy = new RVP_LaserVisualData();
        copy.color = this.color;
        copy.width = this.width;
        copy.durationTick = this.durationTick;
        copy.pulsate = this.pulsate;
        copy.renderStartDistance = this.renderStartDistance;
        copy.segmentLength = this.segmentLength;
        copy.chargeRatio = Mth.clamp(chargeRatio, 0.15f, 1f);
        return copy;
    }

    private transient float chargeRatio = 1f;

    public float getChargeRatio() {
        return chargeRatio;
    }

    public float getWidth() {
        return width <= 0f ? 0.2f : width;
    }

    public int getDurationTick() {
        return Math.max(durationTick, 2);
    }

    public boolean isPulsate() {
        return pulsate;
    }

    public double getRenderStartDistance() {
        return Math.max(renderStartDistance, 0.0);
    }

    public float getSegmentLength() {
        return segmentLength <= 0f ? 0.4f : segmentLength;
    }

    public int getMaxSegments() {
        return DEFAULT_MAX_SEGMENTS;
    }

    private static int clampChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
