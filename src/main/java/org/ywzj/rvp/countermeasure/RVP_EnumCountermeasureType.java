package org.ywzj.rvp.countermeasure;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 干扰物类型：热焰弹（干扰 IR/AIR）与箔条（干扰 SARH/ARH）。
 */
public enum RVP_EnumCountermeasureType {
    FLARE,
    CHAFF;

    /** 按配置名（大小写不敏感）解析；未知名字返回 null。 */
    @Nullable
    public static RVP_EnumCountermeasureType byName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        for (RVP_EnumCountermeasureType type : values()) {
            if (type.name().equals(upper)) {
                return type;
            }
        }
        return null;
    }
}
