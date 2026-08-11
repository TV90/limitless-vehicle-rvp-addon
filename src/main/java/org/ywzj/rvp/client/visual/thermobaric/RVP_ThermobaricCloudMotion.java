package org.ywzj.rvp.client.visual.thermobaric;

import net.minecraft.util.Mth;

/**
 * 计算温压后燃烟云的无状态运动曲线。
 *
 * <p>外缘环流由项目内 HBM 核云的环面卷吸运动搬运并缩短为参数曲线：烟气先从内侧
 * 上升，再越过菌盖顶部向外侧翻卷。这里保留独立实现，温压效果不引用核爆管理器，
 * 也不会继承其长寿命大型蘑菇云状态。</p>
 */
final class RVP_ThermobaricCloudMotion {
    /** 外缘环流开始占据可见位移的局部进度。 */
    private static final float ROLL_START_PROGRESS = 0.18F;
    /** 外缘环流从内侧上升到外侧下卷所经过的弧度。 */
    private static final float ROLL_ARC_RADIANS = Mth.PI * 1.25F;

    private RVP_ThermobaricCloudMotion() {
    }

    /**
     * 按云团层级、全生命周期进度和两类速度倍率返回当前横向、竖直与旋转偏移。
     * 动画从 {@code cloud_start_tick} 连续推进到淡出结束，{@code cloud_full_tick}
     * 不参与运动计算；升起进度到达一后保持高度上限，翻滚时钟则继续周期性推进。
     *
     * @param cloud 当前云团的确定性运动参数
     * @param animationProgress 从烟云开始到消失的归一化生命周期进度
     * @param riseSpeedFactor 竖直基础升起速度倍率，零值停止基础升起
     * @param rollSpeedFactor 翻滚、卷吸与连续湍流速度倍率，零值冻结对应运动
     */
    static Motion resolve(RVP_ThermobaricEffectInstance.Cloud cloud,
            float animationProgress, float riseSpeedFactor, float rollSpeedFactor) {
        float progress = Mth.clamp(animationProgress, 0.0F, 1.0F);
        float speedExponent = 1.0F / Math.max(0.05F, cloud.formationSpeedFactor());
        float riseProgress = Mth.clamp(progress * Math.max(0.0F, riseSpeedFactor),
                0.0F, 1.0F);
        float timedRiseProgress = (float) Math.pow(riseProgress, speedExponent);
        float riseGrowth = easeOutCubic(timedRiseProgress);
        double rollProgress = (double) progress * Math.max(0.0F, rollSpeedFactor);
        double timedRollProgress = Math.pow(rollProgress, speedExponent);
        float boundedRollProgress = timedRollProgress >= 1.0D
                ? 1.0F : (float) timedRollProgress;
        float rollGrowth = easeOutCubic(boundedRollProgress);
        double motionTime = timedRollProgress * (2.15D + cloud.rollSpeedFactor() * 0.45D)
                + cloud.phase() * 0.42F;
        float noiseEnvelope = 0.18F + rollGrowth * 0.82F;
        float radialNoise = (float) Math.sin(cloud.noisePhase()
                + motionTime * Mth.TWO_PI * cloud.noiseFrequency())
                * cloud.radialNoiseFactor() * noiseEnvelope;
        float verticalNoise = (float) Math.sin(cloud.noisePhase() * 1.37F
                + motionTime * Mth.TWO_PI * (cloud.noiseFrequency() * 0.73F + 0.31F))
                * cloud.verticalNoiseFactor() * noiseEnvelope;
        return switch (cloud.layer()) {
            case CENTER -> resolveCenter(cloud, motionTime, riseGrowth, rollGrowth,
                    radialNoise, verticalNoise);
            case UPDRAFT -> resolveUpdraft(cloud, motionTime, riseGrowth, rollGrowth,
                    radialNoise, verticalNoise);
            case ROLLER -> resolveRoller(cloud, boundedRollProgress, motionTime,
                    riseGrowth, rollGrowth, radialNoise, verticalNoise);
        };
    }

    /** 爆心层低速铺开，仅做轻微抬升，确保主火球消退后爆心仍有不完全烟云覆盖。 */
    private static Motion resolveCenter(RVP_ThermobaricEffectInstance.Cloud cloud,
            double motionTime, float riseGrowth, float rollGrowth,
            float radialNoise, float verticalNoise) {
        float radial = cloud.radialFactor() * (0.42F + rollGrowth * 0.58F)
                + radialNoise * 0.55F;
        float rise = cloud.riseFactor() * riseGrowth * 0.34F
                + (float) Math.sin(motionTime * Mth.PI + cloud.rollPhase()) * 0.018F
                + verticalNoise * 0.45F
                + Math.max(0.0F, cloud.verticalDrift()) * riseGrowth * 0.05F;
        float angleOffset = cloud.curlRadians() * rollGrowth * 0.40F
                + radialNoise * 0.8F;
        return new Motion(Math.max(0.0F, radial), Mth.clamp(rise, 0.0F, 1.0F),
                angleOffset, 1.0F);
    }

    /** 中心上升层在窄柱内螺旋抬升，为外缘翻滚层持续提供向上的视觉流向。 */
    private static Motion resolveUpdraft(RVP_ThermobaricEffectInstance.Cloud cloud,
            double motionTime, float riseGrowth, float rollGrowth,
            float radialNoise, float verticalNoise) {
        float radial = cloud.radialFactor() * (0.18F + rollGrowth * 0.48F)
                + radialNoise;
        float rise = cloud.riseFactor() * riseGrowth + verticalNoise
                + Math.max(0.0F, cloud.verticalDrift()) * riseGrowth * 0.18F;
        float angleOffset = cloud.curlRadians() * rollGrowth * 1.45F
                + (float) Math.sin(motionTime * Mth.PI) * 0.24F
                + radialNoise;
        return new Motion(Math.max(0.0F, radial), Mth.clamp(rise, 0.0F, 1.0F),
                angleOffset, 0.94F + rollGrowth * 0.20F);
    }

    /**
     * 外缘层沿环面截面从内侧上升、经过顶部并向外侧翻卷。
     * 该内上外下的截面运动与 HBM 核云环流一致，但尺寸和时间完全使用温压实例参数。
     */
    private static Motion resolveRoller(RVP_ThermobaricEffectInstance.Cloud cloud,
            float rollProgress, double motionTime, float riseGrowth, float rollGrowth,
            float radialNoise, float verticalNoise) {
        float normalizedRoll = Mth.clamp(
                (rollProgress - ROLL_START_PROGRESS) / (1.0F - ROLL_START_PROGRESS),
                0.0F, 1.0F);
        float rollEnvelope = smoothStep(normalizedRoll);
        float rollAngle = (float) Math.IEEEremainder(-Mth.PI + cloud.rollPhase()
                - motionTime * ROLL_ARC_RADIANS * cloud.rollSpeedFactor(), Mth.TWO_PI);
        float rollerEnvelope = smoothStep(Mth.clamp(rollProgress / 0.72F, 0.0F, 1.0F));
        float rollerRadius = cloud.rollerRadiusFactor()
                * (0.22F + rollerEnvelope * 0.96F);
        float torusRadius = cloud.radialFactor()
                * (0.42F + easeOutCubic(
                Mth.clamp((rollProgress - 0.12F) / 0.88F, 0.0F, 1.0F)) * 0.80F);
        float radial = Math.max(0.0F, torusRadius
                + Mth.cos(rollAngle) * rollerRadius * rollEnvelope + radialNoise);
        float rise = cloud.riseFactor() * riseGrowth * 0.82F
                + Mth.sin(rollAngle) * rollerRadius * rollEnvelope
                + verticalNoise
                + Math.max(0.0F, cloud.verticalDrift()) * riseGrowth * 0.15F;
        float angleOffset = cloud.curlRadians() * rollGrowth * 1.40F
                + Mth.sin(rollAngle) * 0.12F + radialNoise * 0.7F;
        float scalePulse = 1.0F + (0.5F + 0.5F * Mth.sin(rollAngle)) * 0.16F;
        return new Motion(radial, Mth.clamp(rise, 0.0F, 1.0F),
                angleOffset, scalePulse);
    }

    /** 三次缓出，让烟云起步快而接近目标位置时自然减速。 */
    private static float easeOutCubic(float value) {
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse;
    }

    /** 三次平滑步进，避免翻滚相位开始时出现速度突跳。 */
    private static float smoothStep(float value) {
        return value * value * (3.0F - 2.0F * value);
    }

    /**
     * @param radialFactor 当前横向半径相对预设云半径的倍率
     * @param riseFactor 全生命周期受 {@code cloud_rise_factor} 控制的归一化上升倍率，最大为 1
     * @param angleOffset 当前水平卷曲角偏移（弧度）
     * @param scaleMultiplier 当前云片尺寸呼吸倍率
     */
    record Motion(float radialFactor, float riseFactor,
                  float angleOffset, float scaleMultiplier) {
    }
}
