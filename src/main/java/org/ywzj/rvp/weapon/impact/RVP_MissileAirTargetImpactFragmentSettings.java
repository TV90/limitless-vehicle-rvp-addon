package org.ywzj.rvp.weapon.impact;

import net.minecraft.util.Mth;

/**
 * 导弹命中离地空中目标后的视觉碎片运行时参数。
 *
 * <p>参数只存在当前服务端进程内，由 {@code /rvpdebug missileImpactFragments} 调整；
 * 不读取、不写入武器 JSON，也不影响爆炸伤害。</p>
 */
public final class RVP_MissileAirTargetImpactFragmentSettings {
    /** 是否启用空中目标命中后的视觉碎片。 */
    public static final boolean DEFAULT_ENABLED = true;
    /** 每次命中事件的碎片数量上限默认值；实际生成数量在 1～此上限内随机。 */
    public static final int DEFAULT_COUNT = 4;
    /** 目标远侧外移距离默认值，单位格。 */
    public static final float DEFAULT_FAR_SIDE_OFFSET = 0.15F;
    /** 导弹速度继承倍率默认值。 */
    public static final float DEFAULT_SPEED_SCALE = 1.0F;
    /** 碎片相对导弹命中方向的圆锥最大偏转半角默认值，单位度。 */
    public static final float DEFAULT_FRAGMENT_CONE_HALF_ANGLE_DEGREES = 17.0F;
    /** 每 tick 速度保留比例默认值。 */
    public static final float DEFAULT_DAMPING = 0.90F;
    /** 视为速度归零的阈值默认值，单位格/tick。 */
    public static final float DEFAULT_STOP_SPEED = 0.05F;
    /** 碎片出生位置横向分散距离默认值，单位格。 */
    public static final float DEFAULT_SPAWN_SPREAD = 0.65F;
    /** 布朗运动每 tick 的随机速度扰动默认值，单位格/tick。 */
    public static final float DEFAULT_BROWNIAN_STRENGTH = 0.03F;
    /** 白烟生成间隔默认值，单位 tick。 */
    public static final int DEFAULT_SMOKE_INTERVAL = 1;
    /** 单个白烟粒子尺寸倍率默认值。 */
    public static final float DEFAULT_SMOKE_SIZE = 5.0F;
    /** 单个白烟粒子寿命默认值，单位 tick。 */
    public static final int DEFAULT_SMOKE_LIFETIME = 150;
    /** 白烟粒子出生透明度默认值。 */
    public static final float DEFAULT_SMOKE_START_ALPHA = 0.8F;
    /** 白烟粒子寿命结束透明度默认值。 */
    public static final float DEFAULT_SMOKE_END_ALPHA = 0.0F;
    /** 每个轨迹采样点叠加的白烟贴图层数默认值。 */
    public static final int DEFAULT_SMOKE_LAYERS = 2;
    /** 白烟轨迹采样点之间允许的最大距离默认值，单位格。 */
    public static final float DEFAULT_SMOKE_POINT_SPACING = 0.35F;
    /** 白烟贴图随机旋转范围默认值，单位度。 */
    public static final float DEFAULT_SMOKE_ROTATION_DEGREES = 180.0F;
    /** 双层白烟贴图相对尺寸随机差异默认值。 */
    public static final float DEFAULT_SMOKE_LAYER_SCALE_VARIANCE = 0.18F;
    /** 单个碎片每次白烟生成最多补出的轨迹点数默认值。 */
    public static final int DEFAULT_SMOKE_MAX_POINTS_PER_TICK = 8;
    /** 客户端视觉事件广播距离默认值，单位格。 */
    public static final double DEFAULT_BROADCAST_RANGE = 1536.0D;
    /** 碎片的异常寿命安全上限默认值，单位 tick。 */
    public static final int DEFAULT_MAX_LIFETIME = 600;

    /** 碎片数量可调范围。 */
    public static final int MIN_COUNT = 1;
    /** 碎片数量可调范围上限。 */
    public static final int MAX_COUNT = 4;
    /** 远侧偏移可调范围上限，单位格。 */
    public static final float MAX_FAR_SIDE_OFFSET = 8.0F;
    /** 速度继承倍率可调范围上限。 */
    public static final float MAX_SPEED_SCALE = 2.0F;
    /** 碎片圆锥最大偏转半角可调范围上限，单位度。 */
    public static final float MAX_FRAGMENT_CONE_HALF_ANGLE_DEGREES = 90.0F;
    /** 阻尼可调范围下限。 */
    public static final float MIN_DAMPING = 0.01F;
    /** 阻尼可调范围上限。 */
    public static final float MAX_DAMPING = 0.999F;
    /** 停止速度可调范围上限，单位格/tick。 */
    public static final float MAX_STOP_SPEED = 1.0F;
    /** 出生分散距离可调范围上限，单位格。 */
    public static final float MAX_SPAWN_SPREAD = 2.0F;
    /** 布朗运动强度可调范围上限，单位格/tick。 */
    public static final float MAX_BROWNIAN_STRENGTH = 0.25F;
    /** 白烟生成间隔可调范围上限，单位 tick。 */
    public static final int MAX_SMOKE_INTERVAL = 20;
    /** 白烟尺寸可调范围下限。 */
    public static final float MIN_SMOKE_SIZE = 0.1F;
    /** 白烟尺寸可调范围上限。 */
    public static final float MAX_SMOKE_SIZE = 34.0F;
    /** 白烟寿命可调范围上限，单位 tick。 */
    public static final int MAX_SMOKE_LIFETIME = 600;
    /** 白烟起止透明度可调范围下限。 */
    public static final float MIN_SMOKE_ALPHA = 0.0F;
    /** 白烟起止透明度可调范围上限。 */
    public static final float MAX_SMOKE_ALPHA = 1.0F;
    /** 白烟贴图层数可调范围下限。 */
    public static final int MIN_SMOKE_LAYERS = 1;
    /** 白烟贴图层数可调范围上限；默认双层叠加。 */
    public static final int MAX_SMOKE_LAYERS = 2;
    /** 白烟轨迹采样点间距可调范围下限，单位格。 */
    public static final float MIN_SMOKE_POINT_SPACING = 0.05F;
    /** 白烟轨迹采样点间距可调范围上限，单位格。 */
    public static final float MAX_SMOKE_POINT_SPACING = 2.0F;
    /** 白烟随机旋转范围可调上限，单位度。 */
    public static final float MAX_SMOKE_ROTATION_DEGREES = 360.0F;
    /** 白烟层间相对尺寸差异可调上限。 */
    public static final float MAX_SMOKE_LAYER_SCALE_VARIANCE = 0.5F;
    /** 单个碎片每次白烟生成最多补点数可调上限。 */
    public static final int MAX_SMOKE_MAX_POINTS_PER_TICK = 32;
    /** 广播距离可调范围下限，单位格。 */
    public static final double MIN_BROADCAST_RANGE = 16.0D;
    /** 广播距离可调范围上限，单位格。 */
    public static final double MAX_BROADCAST_RANGE = 4096.0D;
    /** 碎片安全寿命可调范围上限，单位 tick。 */
    public static final int MAX_MAX_LIFETIME = 2400;

    /** 当前是否启用视觉碎片。 */
    private static volatile boolean enabled = DEFAULT_ENABLED;
    /** 当前每次命中事件的碎片数量上限；实际生成数量在 1～此上限内随机。 */
    private static volatile int count = DEFAULT_COUNT;
    /** 当前目标远侧外移距离。 */
    private static volatile float farSideOffset = DEFAULT_FAR_SIDE_OFFSET;
    /** 当前导弹速度继承倍率。 */
    private static volatile float speedScale = DEFAULT_SPEED_SCALE;
    /** 当前碎片相对导弹命中方向的圆锥最大偏转半角，单位度。 */
    private static volatile float fragmentConeHalfAngleDegrees = DEFAULT_FRAGMENT_CONE_HALF_ANGLE_DEGREES;
    /** 当前速度阻尼。 */
    private static volatile float damping = DEFAULT_DAMPING;
    /** 当前速度归零阈值。 */
    private static volatile float stopSpeed = DEFAULT_STOP_SPEED;
    /** 当前出生位置分散距离。 */
    private static volatile float spawnSpread = DEFAULT_SPAWN_SPREAD;
    /** 当前布朗运动强度。 */
    private static volatile float brownianStrength = DEFAULT_BROWNIAN_STRENGTH;
    /** 当前白烟生成间隔。 */
    private static volatile int smokeInterval = DEFAULT_SMOKE_INTERVAL;
    /** 当前白烟尺寸倍率。 */
    private static volatile float smokeSize = DEFAULT_SMOKE_SIZE;
    /** 当前白烟寿命。 */
    private static volatile int smokeLifetime = DEFAULT_SMOKE_LIFETIME;
    /** 当前白烟粒子出生透明度。 */
    private static volatile float smokeStartAlpha = DEFAULT_SMOKE_START_ALPHA;
    /** 当前白烟粒子寿命结束透明度。 */
    private static volatile float smokeEndAlpha = DEFAULT_SMOKE_END_ALPHA;
    /** 当前每个轨迹采样点的白烟贴图层数。 */
    private static volatile int smokeLayers = DEFAULT_SMOKE_LAYERS;
    /** 当前白烟轨迹采样点最大间距，单位格。 */
    private static volatile float smokePointSpacing = DEFAULT_SMOKE_POINT_SPACING;
    /** 当前白烟贴图随机旋转范围，单位度。 */
    private static volatile float smokeRotationDegrees = DEFAULT_SMOKE_ROTATION_DEGREES;
    /** 当前白烟层间相对尺寸随机差异。 */
    private static volatile float smokeLayerScaleVariance = DEFAULT_SMOKE_LAYER_SCALE_VARIANCE;
    /** 当前单个碎片每次白烟生成最多补出的轨迹点数。 */
    private static volatile int smokeMaxPointsPerTick = DEFAULT_SMOKE_MAX_POINTS_PER_TICK;
    /** 当前视觉事件广播距离。 */
    private static volatile double broadcastRange = DEFAULT_BROADCAST_RANGE;
    /** 当前碎片异常寿命安全上限。 */
    private static volatile int maxLifetime = DEFAULT_MAX_LIFETIME;

    private RVP_MissileAirTargetImpactFragmentSettings() {
    }

    /** 当前参数快照；事件发送时复制一次，避免飞行中的视觉效果被后续调参改写。 */
    public static Snapshot snapshot() {
        return new Snapshot(enabled, count, farSideOffset, speedScale,
                fragmentConeHalfAngleDegrees, damping, stopSpeed, spawnSpread, brownianStrength, smokeInterval,
                smokeSize, smokeLifetime,
                smokeStartAlpha, smokeEndAlpha, smokeLayers, smokePointSpacing,
                smokeRotationDegrees, smokeLayerScaleVariance,
                smokeMaxPointsPerTick, broadcastRange, maxLifetime);
    }

    /** 设置总开关。 */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** 设置每次命中事件的碎片数量上限；实际生成数量在 1～此上限内随机。 */
    public static void setCount(int value) {
        count = Mth.clamp(value, MIN_COUNT, MAX_COUNT);
    }

    /** 设置远侧外移距离。 */
    public static void setFarSideOffset(float value) {
        farSideOffset = Mth.clamp(value, 0.0F, MAX_FAR_SIDE_OFFSET);
    }

    /** 设置速度继承倍率。 */
    public static void setSpeedScale(float value) {
        speedScale = Mth.clamp(value, 0.0F, MAX_SPEED_SCALE);
    }

    /** 设置碎片相对导弹命中方向的圆锥最大偏转半角，单位度。 */
    public static void setFragmentConeHalfAngleDegrees(float value) {
        fragmentConeHalfAngleDegrees = Mth.clamp(
                value, 0.0F, MAX_FRAGMENT_CONE_HALF_ANGLE_DEGREES);
    }

    /** 设置速度阻尼。 */
    public static void setDamping(float value) {
        damping = Mth.clamp(value, MIN_DAMPING, MAX_DAMPING);
    }

    /** 设置速度归零阈值。 */
    public static void setStopSpeed(float value) {
        stopSpeed = Mth.clamp(value, 0.0001F, MAX_STOP_SPEED);
    }

    /** 设置出生位置分散距离。 */
    public static void setSpawnSpread(float value) {
        spawnSpread = Mth.clamp(value, 0.0F, MAX_SPAWN_SPREAD);
    }

    /** 设置布朗运动强度。 */
    public static void setBrownianStrength(float value) {
        brownianStrength = Mth.clamp(value, 0.0F, MAX_BROWNIAN_STRENGTH);
    }

    /** 设置白烟生成间隔。 */
    public static void setSmokeInterval(int value) {
        smokeInterval = Mth.clamp(value, 1, MAX_SMOKE_INTERVAL);
    }

    /** 设置白烟尺寸倍率。 */
    public static void setSmokeSize(float value) {
        smokeSize = Mth.clamp(value, MIN_SMOKE_SIZE, MAX_SMOKE_SIZE);
    }

    /** 设置白烟寿命。 */
    public static void setSmokeLifetime(int value) {
        smokeLifetime = Mth.clamp(value, 1, MAX_SMOKE_LIFETIME);
    }

    /** 设置白烟粒子出生透明度。 */
    public static void setSmokeStartAlpha(float value) {
        smokeStartAlpha = Mth.clamp(value, MIN_SMOKE_ALPHA, MAX_SMOKE_ALPHA);
    }

    /** 设置白烟粒子寿命结束透明度。 */
    public static void setSmokeEndAlpha(float value) {
        smokeEndAlpha = Mth.clamp(value, MIN_SMOKE_ALPHA, MAX_SMOKE_ALPHA);
    }

    /** 设置每个轨迹采样点叠加的白烟贴图层数。 */
    public static void setSmokeLayers(int value) {
        smokeLayers = Mth.clamp(value, MIN_SMOKE_LAYERS, MAX_SMOKE_LAYERS);
    }

    /** 设置白烟轨迹采样点的最大间距，单位格。 */
    public static void setSmokePointSpacing(float value) {
        smokePointSpacing = Mth.clamp(value, MIN_SMOKE_POINT_SPACING, MAX_SMOKE_POINT_SPACING);
    }

    /** 设置白烟贴图随机旋转范围，单位度。 */
    public static void setSmokeRotationDegrees(float value) {
        smokeRotationDegrees = Mth.clamp(value, 0.0F, MAX_SMOKE_ROTATION_DEGREES);
    }

    /** 设置白烟层间相对尺寸随机差异。 */
    public static void setSmokeLayerScaleVariance(float value) {
        smokeLayerScaleVariance = Mth.clamp(value, 0.0F, MAX_SMOKE_LAYER_SCALE_VARIANCE);
    }

    /** 设置单个碎片每次白烟生成最多补出的轨迹点数。 */
    public static void setSmokeMaxPointsPerTick(int value) {
        smokeMaxPointsPerTick = Mth.clamp(value, 1, MAX_SMOKE_MAX_POINTS_PER_TICK);
    }

    /** 设置广播距离。 */
    public static void setBroadcastRange(double value) {
        broadcastRange = Mth.clamp(value, MIN_BROADCAST_RANGE, MAX_BROADCAST_RANGE);
    }

    /** 设置碎片安全寿命。 */
    public static void setMaxLifetime(int value) {
        maxLifetime = Mth.clamp(value, 1, MAX_MAX_LIFETIME);
    }

    /** 恢复全部运行时默认值。 */
    public static void reset() {
        enabled = DEFAULT_ENABLED;
        count = DEFAULT_COUNT;
        farSideOffset = DEFAULT_FAR_SIDE_OFFSET;
        speedScale = DEFAULT_SPEED_SCALE;
        fragmentConeHalfAngleDegrees = DEFAULT_FRAGMENT_CONE_HALF_ANGLE_DEGREES;
        damping = DEFAULT_DAMPING;
        stopSpeed = DEFAULT_STOP_SPEED;
        spawnSpread = DEFAULT_SPAWN_SPREAD;
        brownianStrength = DEFAULT_BROWNIAN_STRENGTH;
        smokeInterval = DEFAULT_SMOKE_INTERVAL;
        smokeSize = DEFAULT_SMOKE_SIZE;
        smokeLifetime = DEFAULT_SMOKE_LIFETIME;
        smokeStartAlpha = DEFAULT_SMOKE_START_ALPHA;
        smokeEndAlpha = DEFAULT_SMOKE_END_ALPHA;
        smokeLayers = DEFAULT_SMOKE_LAYERS;
        smokePointSpacing = DEFAULT_SMOKE_POINT_SPACING;
        smokeRotationDegrees = DEFAULT_SMOKE_ROTATION_DEGREES;
        smokeLayerScaleVariance = DEFAULT_SMOKE_LAYER_SCALE_VARIANCE;
        smokeMaxPointsPerTick = DEFAULT_SMOKE_MAX_POINTS_PER_TICK;
        broadcastRange = DEFAULT_BROADCAST_RANGE;
        maxLifetime = DEFAULT_MAX_LIFETIME;
    }

    /** 返回命令反馈使用的参数摘要。 */
    public static String describe() {
        return "enabled=" + enabled
                + ", count=" + count
                + ", farSideOffset=" + farSideOffset
                + ", speedScale=" + speedScale
                + ", fragmentConeHalfAngleDegrees=" + fragmentConeHalfAngleDegrees
                + ", damping=" + damping
                + ", stopSpeed=" + stopSpeed
                + ", spawnSpread=" + spawnSpread
                + ", brownianStrength=" + brownianStrength
                + ", smokeInterval=" + smokeInterval
                + ", smokeSize=" + smokeSize
                + ", smokeLifetime=" + smokeLifetime
                + ", smokeStartAlpha=" + smokeStartAlpha
                + ", smokeEndAlpha=" + smokeEndAlpha
                + ", smokeLayers=" + smokeLayers
                + ", smokePointSpacing=" + smokePointSpacing
                + ", smokeRotationDegrees=" + smokeRotationDegrees
                + ", smokeLayerScaleVariance=" + smokeLayerScaleVariance
                + ", smokeMaxPointsPerTick=" + smokeMaxPointsPerTick
                + ", broadcastRange=" + broadcastRange
                + ", maxLifetime=" + maxLifetime;
    }

    /** 单次视觉事件使用的不可变参数快照。 */
    public record Snapshot(
            /** 是否启用视觉碎片。 */
            boolean enabled,
            /** 每次命中事件的碎片数量上限；实际生成数量在 1～此上限内随机。 */
            int count,
            /** 目标远侧外移距离，单位格。 */
            float farSideOffset,
            /** 导弹速度继承倍率。 */
            float speedScale,
            /** 碎片相对导弹命中方向的圆锥最大偏转半角，单位度。 */
            float fragmentConeHalfAngleDegrees,
            /** 每 tick 速度保留比例。 */
            float damping,
            /** 速度归零阈值，单位格/tick。 */
            float stopSpeed,
            /** 碎片出生位置分散距离，单位格。 */
            float spawnSpread,
            /** 布朗运动扰动强度，单位格/tick。 */
            float brownianStrength,
            /** 白烟生成间隔，单位 tick。 */
            int smokeInterval,
            /** 白烟尺寸倍率。 */
            float smokeSize,
            /** 白烟寿命，单位 tick。 */
            int smokeLifetime,
            /** 白烟粒子出生透明度。 */
            float smokeStartAlpha,
            /** 白烟粒子寿命结束透明度。 */
            float smokeEndAlpha,
            /** 每个轨迹采样点叠加的白烟贴图层数。 */
            int smokeLayers,
            /** 白烟轨迹采样点最大间距，单位格。 */
            float smokePointSpacing,
            /** 白烟贴图随机旋转范围，单位度。 */
            float smokeRotationDegrees,
            /** 白烟层间相对尺寸随机差异。 */
            float smokeLayerScaleVariance,
            /** 单个碎片每次白烟生成最多补出的轨迹点数。 */
            int smokeMaxPointsPerTick,
            /** 客户端视觉事件广播距离，单位格。 */
            double broadcastRange,
            /** 碎片异常寿命安全上限，单位 tick。 */
            int maxLifetime) {
    }
}
