package org.ywzj.rvp.vehicle;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 骨骼模块（Bone Module）类型：一个骨骼可同时挂多个模块，各模块独立判定失效。
 *
 * <p>当前架构落地两个维度的行为，由消费点（{@code RVP_VehicleHitboxFactorManager}、
 * 动画脚本等）按类型分派：</p>
 * <ul>
 *   <li>{@link #ERA}：失效后该骨块不再阻拦弹药（穿透）。</li>
 *   <li>{@link #TRACK} / {@link #JAMMER}：失效后仍参与碰撞，仅功能损失（动力/干扰）。
 *       —— 功能消费点暂未实现，本阶段只建立状态与同步架构。</li>
 * </ul>
 */
public enum BoneModuleType {
    ERA,
    TRACK,
    JAMMER;

    private static final BoneModuleType[] VALUES = values();

    /** 按配置/脚本中的名字（大小写不敏感）解析；未知名字返回 null。 */
    public static @Nullable BoneModuleType byName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        for (BoneModuleType type : VALUES) {
            if (type.name().equals(upper)) {
                return type;
            }
        }
        return null;
    }

    /** 是否为"可被爆炸百分比破坏"的模块（默认仅 ERA 参与爆炸破坏）。 */
    public boolean participatesInBlastDestruction() {
        return this == ERA;
    }
}
