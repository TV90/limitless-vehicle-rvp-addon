package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Locale;

/**
 * 电视（TV）制导导弹专用参数。JSON 键 {@code tv_missile_data}，仅 {@code rvp:missile} +
 * {@link org.ywzj.rvp.guidance.RVP_EnumGuidanceType#TV} 使用。
 */
public class RVP_TvMissileData {

    /** 彩色电视画面模式位掩码。 */
    public static final int MODE_COLOR = 1;
    /** 黑白电视画面。 */
    public static final int MODE_BW = 1 << 1;
    /** 热成像画面。 */
    public static final int MODE_THERMAL = 1 << 2;
    public static final int MODE_ALL = MODE_COLOR | MODE_BW | MODE_THERMAL;

    /**
     * 玩家可接管控制的最大距离（格）；超出后 S2C TV 状态可能失效。
     */
    @SerializedName("control_range")
    private float controlRange = 2000f;

    /** TV 制导会话超时（tick），超时自动退出电视视角。 */
    @SerializedName("timeout_tick")
    private int timeoutTick = 200;

    /**
     * 可用画面模式列表：{@code COLOR}、{@code BW}/{@code MONO}、{@code THERMAL}/{@code IR}。
     * 空列表表示全部可用。
     */
    @SerializedName("video_modes")
    private List<String> videoModes;

    public float getControlRange() {
        return Math.max(controlRange, 1f);
    }

    public int getTimeoutTick() {
        return Math.max(timeoutTick, 1);
    }

    public int getVideoModeMask() {
        if (videoModes == null || videoModes.isEmpty()) {
            return MODE_ALL;
        }
        int mask = 0;
        for (String mode : videoModes) {
            mask |= parseMode(mode);
        }
        return mask != 0 ? mask : MODE_ALL;
    }

    public int getDefaultVideoMode() {
        if (videoModes != null) {
            for (String mode : videoModes) {
                int parsed = parseMode(mode);
                if (parsed != 0) {
                    return parsed;
                }
            }
        }
        return MODE_COLOR;
    }

    private static int parseMode(String mode) {
        if (mode == null) {
            return 0;
        }
        return switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "COLOR" -> MODE_COLOR;
            case "BW", "BLACK_WHITE", "BLACKWHITE", "MONO", "MONOCHROME" -> MODE_BW;
            case "THERMAL", "IR" -> MODE_THERMAL;
            default -> 0;
        };
    }
}
