package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.light.RVP_LightParameters;

import java.util.UUID;

/**
 * 一帧只读光源快照，位置保持 double，送入显卡前先减相机位置。
 * @param position 世界位置
 * @param red 红色
 * @param green 绿色
 * @param blue 蓝色
 * @param radius 照亮半径，单位格；0 表示不照亮环境
 * @param haloRadius 独立光晕半径，单位格；已按当前年龄向中心收缩，不由照亮半径推导
 * @param intensity 已应用年龄衰减的强度
 * @param haloIntensity 独立采样曲线后的光晕强度；爆炸独立设峰值，炮口以本身光强为峰值
 * @param skyIntensity 天空散射强度；只染没有深度的天空像素，0 表示不写天空
 * @param skySpread 天空亮区宽度，0～1；1 为整片覆盖，0 为只在爆心方向
 * @param emission 圆形白热核心与外围光团；null 表示该光源没有自发光
 * @param remainingTicks 剩余 tick；短光取照亮与光晕中较晚的结束时刻
 * @param type 来源类型
 * @param persistentKey 持续光归属实体 UUID；同车多灯共用归属，短光为 null
 * @param surfaceWeight 表面受光权重
 * @param bloom 是否进入光晕提取
 * @param volumetricStrength 空气散射强度
 * @param hemisphereAxis 世界空间半球轴；null 表示全向光，干扰机玻片和激光起点提供方向
 */
public record RVP_ScreenLight(Vec3 position, float red, float green, float blue, float radius, float haloRadius,
        float intensity, float haloIntensity, float skyIntensity, float skySpread,
        RVP_LightParameters.EngineEmission emission, float remainingTicks,
        RVP_LightSourceType type, UUID persistentKey,
        float surfaceWeight, boolean bloom, float volumetricStrength, Vec3 hemisphereAxis) {
    /** 普通全向光构造入口；现有爆炸、炮口和发动机快照不需要提供方向。 */
    public RVP_ScreenLight(Vec3 position, float red, float green, float blue, float radius, float haloRadius,
                           float intensity, float haloIntensity, float remainingTicks, RVP_LightSourceType type,
                           UUID persistentKey, float surfaceWeight, boolean bloom, float volumetricStrength) {
        // 未显式提供天空与自发光参数时保持“不染天空、无白热核心”，与加入这些通道之前的行为完全一致。
        this(position, red, green, blue, radius, haloRadius, intensity, haloIntensity, 0, 1, null, remainingTicks,
                type, persistentKey, surfaceWeight, bloom, volumetricStrength, null);
    }

    /** 定向持续光构造入口；未指定天空和核心参数时保持这两条通道关闭。 */
    public RVP_ScreenLight(Vec3 position, float red, float green, float blue, float radius, float haloRadius,
                           float intensity, float haloIntensity, float remainingTicks, RVP_LightSourceType type,
                           UUID persistentKey, float surfaceWeight, boolean bloom, float volumetricStrength,
                           Vec3 hemisphereAxis) {
        // 调用完整快照构造入口，支持远端定向灯调用，同时保留本地天空、核心独立字段。
        this(position, red, green, blue, radius, haloRadius, intensity, haloIntensity, 0, 1, null, remainingTicks,
                type, persistentKey, surfaceWeight, bloom, volumetricStrength, hemisphereAxis);
    }

    /** 只使用仍有可见贡献的一路范围，照亮结束后不能拿它的大半径占据光晕预算；天空散射没有半径概念，由预算单独保留。 */
    public float visibleRadius() {
        // 调用自发光核心与光团的有效半径，保证“只有白热核心”的光源也能通过预算与视锥裁剪。
        float emissionRadius = emission == null ? 0 : Math.max(
                emission.coreIntensity() > 0.001F ? emission.coreRadius() : 0,
                emission.haloIntensity() > 0.001F ? emission.haloRadius() : 0);
        return Math.max(emissionRadius,
                Math.max(intensity > 0.001F ? radius : 0, haloIntensity > 0.001F ? haloRadius : 0));
    }
}
