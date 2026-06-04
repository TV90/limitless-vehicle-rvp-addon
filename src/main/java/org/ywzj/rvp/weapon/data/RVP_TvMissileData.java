package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Locale;

/**
 * 电视（TV）制导导弹专用参数。
 */
public class RVP_TvMissileData {

    public static final int MODE_COLOR = 1;
    public static final int MODE_BW = 1 << 1;
    public static final int MODE_THERMAL = 1 << 2;
    public static final int MODE_ALL = MODE_COLOR | MODE_BW | MODE_THERMAL;

    @SerializedName("control_range")
    private float controlRange = 2000f;

    @SerializedName("timeout_tick")
    private int timeoutTick = 200;

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
