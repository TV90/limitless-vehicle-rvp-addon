package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/**
 * 一帧只读光源快照，位置保持 double，送入显卡前先减相机位置。
 * @param position 世界位置
 * @param red 红色
 * @param green 绿色
 * @param blue 蓝色
 * @param radius 照亮半径，单位格；0 表示不照亮环境
 * @param haloRadius 独立光晕半径，单位格；不由已调参的照亮半径推导
 * @param intensity 已应用年龄衰减的强度
 * @param haloIntensity 独立采样的光晕强度；爆炸不随照亮brightness变化，炮口沿用本身光强
 * @param remainingTicks 剩余 tick
 * @param type 来源类型
 * @param persistentKey 发动机 UUID，短光为 null
 * @param surfaceWeight 表面受光权重
 * @param bloom 是否进入光晕提取
 * @param volumetricStrength 空气散射强度
 * @param hemisphereAxis 世界空间半球轴；null 表示全向光，只有干扰机等定向持续光提供
 */
public record RVP_ScreenLight(Vec3 position, float red, float green, float blue, float radius, float haloRadius,
        float intensity, float haloIntensity, float remainingTicks,
        RVP_LightSourceType type, UUID persistentKey,
        float surfaceWeight, boolean bloom, float volumetricStrength, Vec3 hemisphereAxis) {
    /** 普通全向光构造入口；现有爆炸、炮口和发动机快照不需要提供方向。 */
    public RVP_ScreenLight(Vec3 position, float red, float green, float blue, float radius, float haloRadius,
                           float intensity, float haloIntensity, float remainingTicks, RVP_LightSourceType type,
                           UUID persistentKey, float surfaceWeight, boolean bloom, float volumetricStrength) {
        this(position, red, green, blue, radius, haloRadius, intensity, haloIntensity, remainingTicks,
                type, persistentKey, surfaceWeight, bloom, volumetricStrength, null);
    }
    /** 只使用仍有可见贡献的一路范围，照亮结束后不能拿它的大半径占据光晕预算。 */
    public float visibleRadius() {
        return Math.max(intensity > 0.001F ? radius : 0, haloIntensity > 0.001F ? haloRadius : 0);
    }
}
