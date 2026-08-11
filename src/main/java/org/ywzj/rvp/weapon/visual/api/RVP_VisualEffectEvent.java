package org.ywzj.rvp.weapon.visual.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 不可变的服务端权威视觉事件，不包含任何客户端类型。
 *
 * @param effectType 客户端效果工厂类型
 * @param preset 客户端预设资源 ID
 * @param canonicalPresetDataJson 已规范化的不可变预设覆盖 JSON
 * @param dimension 事件发生的维度
 * @param position 服务端权威爆心
 * @param baseExplosionRadius 已解析空爆或近炸覆盖后的最终爆炸半径（格）
 * @param scale 视觉尺寸倍率
 * @param density 服务端允许的最大视觉密度
 * @param durationTicks 覆盖持续时间（tick），{@code -1} 表示使用预设
 * @param broadcastRange 网络广播距离（格）
 * @param seed 客户端确定性演算随机种子
 * @param startGameTime 服务端事件开始世界时间（tick）
 * @param sound 是否允许声音
 * @param flash 是否允许闪光
 * @param shake 是否允许镜头震动
 */
public record RVP_VisualEffectEvent(
        ResourceLocation effectType,
        ResourceLocation preset,
        String canonicalPresetDataJson,
        ResourceKey<Level> dimension,
        Vec3 position,
        float baseExplosionRadius,
        float scale,
        float density,
        int durationTicks,
        double broadcastRange,
        long seed,
        long startGameTime,
        boolean sound,
        boolean flash,
        boolean shake
) {
    /** 单个预设覆盖允许的最大 UTF-8 字节数。 */
    public static final int MAX_PRESET_DATA_BYTES = 8 * 1024;

    public RVP_VisualEffectEvent {
        Objects.requireNonNull(effectType, "effectType");
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(canonicalPresetDataJson, "canonicalPresetDataJson");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        if (canonicalPresetDataJson.getBytes(StandardCharsets.UTF_8).length > MAX_PRESET_DATA_BYTES) {
            throw new IllegalArgumentException("preset_data exceeds 8 KiB");
        }
        if (!positionIsFinite(position) || !Float.isFinite(baseExplosionRadius) || baseExplosionRadius < 0.0F
                || !Float.isFinite(scale) || scale < 0.0F
                || !Float.isFinite(density) || density < 0.0F
                || (durationTicks != -1 && durationTicks < 0)
                || !Double.isFinite(broadcastRange) || broadcastRange < 0.0D) {
            throw new IllegalArgumentException("visual event contains non-finite or negative values");
        }
    }

    private static boolean positionIsFinite(Vec3 position) {
        return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z);
    }
}
