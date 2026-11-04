package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;

/**
 * 一条定向程序喷流的只读快照；所有位置在送显卡前先用 double 减相机。
 * @param position 喷口世界坐标，单位格
 * @param direction 从喷口向弹尾喷出的单位方向
 * @param length 喷流长度，单位格，稳态按既有 flame_scale 缩放，启停过渡时从喷口展开或收束
 * @param radius 喷流最大半径，单位格，稳态按既有 flame_scale 缩放，启停过渡时同步变细
 * @param haloRadius 独立光晕半径，单位格；由口径基础半径乘光晕倍率，范围 0～8192
 * @param strength 已包含推进阶段/燃尽包络的亮度倍率，不改变形状大小
 * @param seed 每枚弹体稳定的动画相位，避免齐射时所有喷流同步闪动
 * @param haloStrength 独立光晕亮度倍率；预览可采用其它衰减曲线，不改变白热喷焰本体
 * @param transition 点火/燃尽的显示过渡倍率，范围 0～1；在高亮映射后应用，避免核心一直饱和为白色
 */
public record RVP_RocketPlume(Vec3 position, Vec3 direction, float length, float radius,
        float haloRadius, float strength, float seed, float haloStrength, float transition) {
    /** 未提供启停时间线的预览和诊断沿用完整喷流；既有光晕曲线保持独立。 */
    public RVP_RocketPlume(Vec3 position, Vec3 direction, float length, float radius,
            float haloRadius, float strength, float seed, float haloStrength) {
        this(position, direction, length, radius, haloRadius, strength, seed, haloStrength, 1);
    }
    /** 默认光晕跟随喷焰原有亮度；已有正式喷焰和诊断调用无需另传曲线参数。 */
    public RVP_RocketPlume(Vec3 position, Vec3 direction, float length, float radius,
            float haloRadius, float strength, float seed) {
        this(position, direction, length, radius, haloRadius, strength, seed, strength);
    }
    /** 包围球中心，用于整条喷流的裁剪，不能只检查喷口是否在屏幕内。 */
    public Vec3 center() { return position.add(direction.scale(length * 0.5)); }

    /** 包围完整轴向长度和径向宽度；后续投影还会补足极远处的像素覆盖。 */
    public float boundRadius() { return (float) Math.hypot(length * 0.5, radius); }

    /** 预览按年龄衰减只更新亮度，不让喷焰尺寸跟着收缩。 */
    public RVP_RocketPlume withStrength(float value) {
        return new RVP_RocketPlume(position, direction, length, radius, haloRadius, value, seed,
                strength > 0 ? haloStrength * value / strength : value, transition);
    }
}
