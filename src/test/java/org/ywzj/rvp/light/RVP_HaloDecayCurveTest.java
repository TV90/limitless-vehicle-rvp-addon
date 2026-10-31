package org.ywzj.rvp.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 独立验证曲线的实际亮度、分段连续性和非法输入，避免只复述渲染实现。 */
class RVP_HaloDecayCurveTest {
    @Test
    void threeTickPeakStaysFullThenSmoothlyReachesZero() {
        for (double age : new double[] {0, 1, 2.99, 3}) {
            // 调用真实游戏刻函数，首3刻保持100%，不能从出生起就开始降亮。
            assertEquals(1, RVP_HaloDecayCurve.peakHoldFade(age, 3, 29));
        }
        assertTrue(RVP_HaloDecayCurve.peakHoldFade(3.5, 3, 29) < 1);
        assertEquals(0.5F, RVP_HaloDecayCurve.peakHoldFade(17.5, 3, 29));
        assertEquals(0, RVP_HaloDecayCurve.peakHoldFade(32, 3, 29));
        assertEquals(0, RVP_HaloDecayCurve.peakHoldFade(Double.NaN, 3, 29));
        assertEquals(0, RVP_HaloDecayCurve.peakHoldFade(4, 3, 0));
    }

    @Test
    void linearLosesTheSameBrightnessEveryQuarterAndEndsExactlyAtZero() {
        for (int quarter = 0; quarter <= 4; quarter++) {
            // 调用生产曲线；传入无关的旧包络，确认线性衰减没有再叠乘旧曲线。
            assertEquals(1 - quarter / 4F, RVP_HaloDecayCurve.LINEAR.fade(quarter / 4F, 0.37F, 0.1F, 0.15F, 0.8F));
        }
        assertEquals(1, RVP_HaloDecayCurve.LINEAR.fade(-1, 0, 0.1F, 0.15F, 0.8F));
        assertEquals(0, RVP_HaloDecayCurve.LINEAR.fade(2, 1, 0.1F, 0.15F, 0.8F));
    }

    @Test
    void flashDropsQuicklyHoldsAnActualPlateauAndFadesOutSmoothly() {
        // 调用生产曲线验证设计目标：中途已明显变暗，保持段亮度固定，寿命末端连续归零。
        assertEquals(1, RVP_HaloDecayCurve.FLASH_HOLD.fade(0, 0, 0.1F, 0.15F, 0.8F));
        assertEquals(0.25625F, RVP_HaloDecayCurve.FLASH_HOLD.fade(0.05F, 1, 0.1F, 0.15F, 0.8F), 0.000001F);
        for (float progress : new float[] {0.1F, 0.2F, 0.5F, 0.8F}) {
            assertEquals(0.15F, RVP_HaloDecayCurve.FLASH_HOLD.fade(progress, 0.7F, 0.1F, 0.15F, 0.8F));
        }
        assertEquals(0.075F, RVP_HaloDecayCurve.FLASH_HOLD.fade(0.9F, 1, 0.1F, 0.15F, 0.8F), 0.000001F);
        assertEquals(0, RVP_HaloDecayCurve.FLASH_HOLD.fade(1, 1, 0.1F, 0.15F, 0.8F));
        for (float boundary : new float[] {0.1F, 0.8F, 1F}) {
            float before = RVP_HaloDecayCurve.FLASH_HOLD.fade(boundary - 0.00001F, 1, 0.1F, 0.15F, 0.8F);
            float after = RVP_HaloDecayCurve.FLASH_HOLD.fade(boundary + 0.00001F, 1, 0.1F, 0.15F, 0.8F);
            assertEquals(before, after, 0.000001F, "分段连接处不应出现可见亮度跳变");
        }
    }

    @Test
    void extremeSettingsStayFiniteBoundedAndMonotonicallyDecrease() {
        for (float drop : new float[] {0.001F, 0.5F, Float.NaN}) {
            for (float low : new float[] {0, 0.15F, 1}) {
                for (float hold : new float[] {0.5F, 0.99F, Float.POSITIVE_INFINITY}) {
                    float previous = 1;
                    for (int step = 0; step <= 1000; step++) {
                        // 调用实际分段函数，边界组合也不能升亮、越界或留下永久残光。
                        float actual = RVP_HaloDecayCurve.FLASH_HOLD.fade(step / 1000F, 1, drop, low, hold);
                        assertTrue(Float.isFinite(actual) && actual >= 0 && actual <= previous + 0.000001F);
                        previous = actual;
                    }
                    assertEquals(0, previous);
                }
            }
        }
        for (RVP_HaloDecayCurve curve : RVP_HaloDecayCurve.values()) {
            assertEquals(0, curve.fade(Float.NaN, 1, 0.1F, 0.15F, 0.8F));
        }
        assertEquals(0.37F, RVP_HaloDecayCurve.ORIGINAL.fade(0.5F, 0.37F, 0.1F, 0.15F, 0.8F));
    }
}
