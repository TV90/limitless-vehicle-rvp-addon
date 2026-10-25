package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.visual.shake.RVP_ScreenShakeExecutor;
import org.ywzj.rvp.client.visual.shake.RVP_ScreenShakeScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricScreenFeedbackTest {
    @Test
    void distanceFactorFallsLinearlyToZeroAtRange() {
        assertEquals(1.0F, RVP_ThermobaricScreenFeedback.resolveDistanceFactor(0.0D, 100.0D));
        assertEquals(0.5F, RVP_ThermobaricScreenFeedback.resolveDistanceFactor(50.0D, 100.0D));
        assertEquals(0.0F, RVP_ThermobaricScreenFeedback.resolveDistanceFactor(100.0D, 100.0D));
        assertEquals(0.0F, RVP_ThermobaricScreenFeedback.resolveDistanceFactor(10.0D, 0.0D));
    }

    @Test
    void progressSupportsImmediateFlashCatchUpAndFiniteLifetime() {
        assertEquals(0.0F, RVP_ThermobaricScreenFeedback.resolveProgress(100.0D, 100.0D, 10));
        assertEquals(0.4F, RVP_ThermobaricScreenFeedback.resolveProgress(104.0D, 100.0D, 10));
        assertEquals(1.0F, RVP_ThermobaricScreenFeedback.resolveProgress(112.0D, 100.0D, 10));
    }

    @Test
    void combinedShakeIsClampedToTwoDegreesPerAxis() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(
                RVP_ThermobaricScreenFeedback.MAX_COMBINED_SHAKE_DEGREES, () -> 1.0D);
        try {
            // 按原温压参数提交多次同相位事件，验证叠加后的正负上限均保持两度。
            for (int i = 0; i < 20; i++) {
                scheduler.schedule(i, 100, 100, 12345L, 0, 100,
                        RVP_ThermobaricScreenFeedback.SHAKE_PARAMETERS);
            }
            RVP_ScreenShakeExecutor.ShakeOffset offset = scheduler.sample(102.5D);
            assertEquals(-2.0F, offset.yaw());
            assertEquals(2.0F, offset.pitch());
            assertEquals(-2.0F, offset.roll());
        } finally {
            // 清理执行器的活动引用，避免测试之间共享震动任务。
            RVP_ScreenShakeExecutor.clearAll();
        }
    }

    @Test
    void shakeOffsetsAreDeterministicFadeAndRemainBelowSinglePulseLimits() {
        // 用通用执行器采样温压参数，验证重复性、种子差异及寿命末端归零。
        RVP_ScreenShakeExecutor.ShakeOffset first = RVP_ScreenShakeExecutor.resolveOffset(
                2.5D, 12345L, 0.8F, RVP_ThermobaricScreenFeedback.SHAKE_PARAMETERS);
        RVP_ScreenShakeExecutor.ShakeOffset repeated = RVP_ScreenShakeExecutor.resolveOffset(
                2.5D, 12345L, 0.8F, RVP_ThermobaricScreenFeedback.SHAKE_PARAMETERS);
        RVP_ScreenShakeExecutor.ShakeOffset otherSeed = RVP_ScreenShakeExecutor.resolveOffset(
                2.5D, 54321L, 0.8F, RVP_ThermobaricScreenFeedback.SHAKE_PARAMETERS);
        RVP_ScreenShakeExecutor.ShakeOffset ended = RVP_ScreenShakeExecutor.resolveOffset(
                12.0D, 12345L, 0.8F, RVP_ThermobaricScreenFeedback.SHAKE_PARAMETERS);

        assertEquals(first, repeated);
        assertNotEquals(first, otherSeed);
        assertTrue(Math.abs(first.yaw()) <= 0.90F);
        assertTrue(Math.abs(first.pitch()) <= 0.70F);
        assertTrue(Math.abs(first.roll()) <= 0.50F);
        assertEquals(0.0F, ended.yaw(), 0.0F);
        assertEquals(0.0F, ended.pitch(), 0.0F);
        assertEquals(0.0F, ended.roll(), 0.0F);
    }

    @Test
    void extractedWaveformMatchesOriginalReferenceSamples() {
        // 固定抽取前公式的参考结果，防止寿命、三轴振幅、频率、种子取位或衰减被改动。
        assertOriginalSample(2.5D, 12345L, 0.8F,
                -0.3400803208351135F, 0.10097145289182663F, -0.2502032518386841F);
        assertOriginalSample(6.0D, 0x123456789ABCDEFL, 0.5F,
                -0.11240045726299286F, -0.044111963361501694F, -0.014899687841534615F);
        assertOriginalSample(11.75D, 0xFEDCBA9876543210L, 1.0F,
                0.0002547223411966115F, -0.000026396575776743703F, 0.0000873598619364202F);
    }

    /** 使用固定参考向量校验真实温压参数，容差为零以检测计算顺序变化。 */
    private static void assertOriginalSample(double age, long seed, float distanceFactor,
            float yaw, float pitch, float roll) {
        // 调用抽取后的执行器并逐轴比较原公式结果。
        RVP_ScreenShakeExecutor.ShakeOffset offset = RVP_ScreenShakeExecutor.resolveOffset(
                age, seed, distanceFactor, RVP_ThermobaricScreenFeedback.SHAKE_PARAMETERS);
        assertEquals(yaw, offset.yaw(), 0.0F);
        assertEquals(pitch, offset.pitch(), 0.0F);
        assertEquals(roll, offset.roll(), 0.0F);
    }
}
