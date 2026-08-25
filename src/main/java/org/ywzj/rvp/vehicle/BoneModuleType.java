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
 *   <li>{@link #TRACK}：失效后仍参与碰撞，仅动力损失。</li>
 *   <li>{@link #JAMMER}：干扰设备（消费点 {@code RVP_JammingRuntime}），失效后失去干扰能力。</li>
 *   <li>{@link #APS}：主动防护发射器（消费点 {@code RVP_ApsRuntimeManager}），失效后该侧扇区失去拦截能力。</li>
 *   <li>{@link #COUNTERMEASURE}：干扰物发射装置（消费点 {@code RVP_CountermeasureRuntimeManager}），
 *       载具干扰物配置的 {@code bone_modules} 全部失效后失去抛洒功能。</li>
 *   <li>{@link #DIRCM}：定向红外对抗照射设备（消费点 {@code RVP_DircmRuntimeManager}），
 *       失效后该通道失去激光照射能力。</li>
 *   <li>{@link #ECM_PASSIVE}：被动电子战防御措施（消费点 {@code RVP_EcmPassiveManager}），
 *       失效后不再在被敌对雷达照射时生成假目标。</li>
 * </ul>
 */
public enum BoneModuleType {
    ERA,
    TRACK,
    JAMMER,
    APS,
    COUNTERMEASURE,
    DIRCM,
    ECM_PASSIVE;

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
