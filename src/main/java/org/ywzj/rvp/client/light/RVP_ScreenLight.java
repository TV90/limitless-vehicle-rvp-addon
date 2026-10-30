package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/**
 * 一帧只读光源快照，位置保持 double，送入显卡前先减相机位置。
 * @param position 世界位置
 * @param red 红色
 * @param green 绿色
 * @param blue 蓝色
 * @param radius 半径，单位格
 * @param intensity 已应用年龄衰减的强度
 * @param remainingTicks 剩余 tick
 * @param type 来源类型
 * @param persistentKey 发动机 UUID，短光为 null
 * @param surfaceWeight 表面受光权重
 * @param bloom 是否进入光晕提取
 * @param volumetricStrength 空气散射强度
 * @param hemisphereAxis 半球光轴向（世界空间）；null 表示全向球光
 */
public record RVP_ScreenLight(Vec3 position, float red, float green, float blue, float radius,
        float intensity, float remainingTicks, RVP_LightSourceType type, UUID persistentKey,
        float surfaceWeight, boolean bloom, float volumetricStrength, Vec3 hemisphereAxis) { }
