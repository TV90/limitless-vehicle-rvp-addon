package org.ywzj.rvp.client.visual.shake;

/**
 * 一次镜头震动的不可变参数；距离衰减和起始时间由调度器接收。
 * 三轴均使用正弦摆动，并按剩余寿命的平方逐渐减弱。
 *
 * @param durationTicks 持续游戏刻数，必须大于零；正常速度下 20 刻为 1 秒
 * @param yawDegrees 左右转动的最大角度，单位度，必须为有限非负数
 * @param pitchDegrees 上下转动的最大角度，单位度，必须为有限非负数
 * @param rollDegrees 画面左右倾斜的最大角度，单位度，必须为有限非负数
 * @param yawRadiansPerTick 左右摆动角频率，单位弧度/刻，必须为有限非负数
 * @param pitchRadiansPerTick 上下摆动角频率，单位弧度/刻，必须为有限非负数
 * @param rollRadiansPerTick 倾斜摆动角频率，单位弧度/刻，必须为有限非负数
 */
public record RVP_ScreenShakeParameters(int durationTicks,
        float yawDegrees, float pitchDegrees, float rollDegrees,
        double yawRadiansPerTick, double pitchRadiansPerTick, double rollRadiansPerTick) {
    public RVP_ScreenShakeParameters {
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("震动持续游戏刻数必须大于零");
        }
        // 校验参数边界，防止无效角度污染整个相机的旋转结果。
        requireNonNegativeFinite(yawDegrees);
        requireNonNegativeFinite(pitchDegrees);
        requireNonNegativeFinite(rollDegrees);
        requireNonNegativeFinite(yawRadiansPerTick);
        requireNonNegativeFinite(pitchRadiansPerTick);
        requireNonNegativeFinite(rollRadiansPerTick);
    }

    /** 拒绝负值和无穷值，允许使用零关闭某一轴。 */
    private static void requireNonNegativeFinite(double value) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException("震动振幅和角频率必须为有限非负数");
        }
    }
}
