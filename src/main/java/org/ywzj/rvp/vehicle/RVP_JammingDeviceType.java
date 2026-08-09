package org.ywzj.rvp.vehicle;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import java.util.Locale;

/**
 * 干扰器设备类型（Jamming Device Type）。
 *
 * <p>干扰器是骨骼的一种特殊功能（挂在骨块的 JAMMER 模块上，被击毁后失效）。
 * 不同类型干扰不同制导方式的导弹；未来新增干扰器（如电磁干扰机）只需在此
 * 增加枚举项并扩展 {@link #interferesWith} 与注入策略，不修改调用链。</p>
 *
 * <ul>
 *   <li>{@link #OPTICAL}：光电干扰机，干扰光学视线制导（当前实现 SACLOS 瞄准线）。</li>
 *   <li>{@link #ELECTRONIC}：电磁干扰机，干扰雷达制导（预留，本阶段不实现效果）。</li>
 * </ul>
 */
public enum RVP_JammingDeviceType {
    OPTICAL,
    ELECTRONIC;

    /** 该类型干扰器可干扰的制导方式。 */
    public boolean interferesWith(RVP_EnumGuidanceType guidanceType) {
        if (guidanceType == null) {
            return false;
        }
        switch (this) {
            case OPTICAL:
                return guidanceType == RVP_EnumGuidanceType.SACLOS;
            case ELECTRONIC:
                return guidanceType == RVP_EnumGuidanceType.ARH
                        || guidanceType == RVP_EnumGuidanceType.SARH;
            default:
                return false;
        }
    }

    /** 按配置名字（大小写不敏感）解析；未知名字回退 OPTICAL。 */
    public static RVP_JammingDeviceType byName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return OPTICAL;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        for (RVP_JammingDeviceType type : values()) {
            if (type.name().equals(upper)) {
                return type;
            }
        }
        return OPTICAL;
    }
}
