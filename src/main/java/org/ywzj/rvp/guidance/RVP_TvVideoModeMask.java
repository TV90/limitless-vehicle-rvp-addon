package org.ywzj.rvp.guidance;

import java.util.Locale;

/**
 * TV 制导画面模式位掩码，供 {@link org.ywzj.rvp.weapon.data.RVP_GuidanceSourceParamsData} 与弹体共用。
 */
public final class RVP_TvVideoModeMask {

    public static final int COLOR = 1;
    public static final int BW = 1 << 1;
    public static final int THERMAL = 1 << 2;
    public static final int ALL = COLOR | BW | THERMAL;

    private RVP_TvVideoModeMask() {}

    public static int parse(String mode) {
        if (mode == null) {
            return 0;
        }
        return switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "COLOR" -> COLOR;
            case "BW", "BLACK_WHITE", "BLACKWHITE", "MONO", "MONOCHROME" -> BW;
            case "THERMAL", "IR" -> THERMAL;
            default -> 0;
        };
    }
}
