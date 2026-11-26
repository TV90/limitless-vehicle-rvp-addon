package org.ywzj.rvp.guidance;

import org.ywzj.rvp.config.RVP_ThermalImagerType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TV 制导画面模式位掩码，供 HITL 数据模型与弹体共用。
 */
public final class RVP_TvVideoModeMask {

    public static final int COLOR = 1;
    public static final int BW = 1 << 1;
    public static final int THERMAL = 1 << 2;
    public static final int ALL = COLOR | BW | THERMAL;

    /** 允许通用热像档的弹头可循环全部热像。 */
    public static final List<RVP_ThermalImagerType> THERMAL_TYPES = List.of(RVP_ThermalImagerType.values());

    private RVP_TvVideoModeMask() {}

    public static int parse(String mode) {
        if (mode == null) {
            return 0;
        }
        return switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "COLOR" -> COLOR;
            case "BW", "BLACK_WHITE", "BLACKWHITE", "MONO", "MONOCHROME" -> BW;
            case "THERMAL", "IR" -> THERMAL;
            default -> thermalType(mode) != null ? THERMAL : 0;
        };
    }

    /** 读取按数据顺序指定的热像。通用 THERMAL/IR 在没有指定具体热像时使用完整列表。 */
    public static List<RVP_ThermalImagerType> thermalTypes(List<String> modes) {
        List<RVP_ThermalImagerType> result = new ArrayList<>();
        for (String name : modes) {
            RVP_ThermalImagerType type = thermalType(name);
            if (type != null && !result.contains(type)) result.add(type);
        }
        return result.isEmpty() ? THERMAL_TYPES : List.copyOf(result);
    }

    private static RVP_ThermalImagerType thermalType(String name) {
        if (name == null) return null;
        String value = name.trim();
        for (RVP_ThermalImagerType type : THERMAL_TYPES) {
            // 枚举名与热像数据标识使用同一组名称。
            if (type.name().equalsIgnoreCase(value)) return type;
        }
        return null;
    }
}
