package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;

/**
 * 一条定向程序喷流的只读快照；所有位置在送显卡前先用 double 减相机。
 * @param position 喷口世界坐标，单位格
 * @param direction 从喷口向弹尾喷出的单位方向
 * @param length 喷流长度，单位格，只按既有 flame_scale 缩放
 * @param radius 喷流最大半径，单位格，只按既有 flame_scale 缩放
 * @param haloRadius 独立光晕半径，单位格；由口径基础半径乘光晕倍率，范围 0～8192
 * @param strength 已包含推进阶段/燃尽包络的亮度倍率，不改变形状大小
 * @param seed 每枚弹体稳定的动画相位，避免齐射时所有喷流同步闪动
 */
public record RVP_RocketPlume(Vec3 position, Vec3 direction, float length, float radius,
        float haloRadius, float strength, float seed) {
    /** 包围球中心，用于整条喷流的裁剪，不能只检查喷口是否在屏幕内。 */
    public Vec3 center() { return position.add(direction.scale(length * 0.5)); }

    /** 包围完整轴向长度和径向宽度；后续投影还会补足极远处的像素覆盖。 */
    public float boundRadius() { return (float) Math.hypot(length * 0.5, radius); }

    /** 预览按年龄衰减只更新亮度，不让喷焰尺寸跟着收缩。 */
    public RVP_RocketPlume withStrength(float value) {
        return new RVP_RocketPlume(position, direction, length, radius, haloRadius, value, seed);
    }
}
