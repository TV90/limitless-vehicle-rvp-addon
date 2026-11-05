package org.ywzj.rvp.client.visual.cookoff;

import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings.Parameter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证殉燃游戏内调参的默认值、重置值和参数有序约束。 */
class RVP_WreckCookoffSettingsTest {

    @Test
    void parametersResetToTheImplementedDefaults() {
        // 调用本项目会话重置入口，确认新参数与当前视觉方案的默认值一致。
        RVP_WreckCookoffSettings.reset();
        assertEquals(9.0D, Parameter.HEIGHT.value(), 1.0E-9D);
        assertEquals(3.0D, Parameter.MUZZLE_LENGTH.value(), 1.0E-9D);
        assertEquals(1.0D, Parameter.DENSITY.value(), 1.0E-9D);
        assertEquals(10.0D, Parameter.GROWTH_TICKS.value(), 1.0E-9D);
        assertEquals(20.0D, Parameter.SHRINK_TICKS.value(), 1.0E-9D);
        assertEquals(0.8D, Parameter.ROOF_SPEED_MIN.value(), 1.0E-9D);
        assertEquals(1.0D, Parameter.ROOF_SPEED_MAX.value(), 1.0E-9D);
        assertEquals(0.12D, Parameter.ROOF_CONE_BASE.value(), 1.0E-9D);
        assertEquals(0.95D, Parameter.ROOF_CONE_TIP.value(), 1.0E-9D);
        assertEquals(0.11D, Parameter.ROOF_OUTWARD.value(), 1.0E-9D);
        assertEquals(1.0D, Parameter.ROOF_SCALE_MIN.value(), 1.0E-9D);
        assertEquals(3.0D, Parameter.ROOF_SCALE_MAX.value(), 1.0E-9D);
        assertEquals(-0.5D, Parameter.ROOF_VERTICAL_OFFSET.value(), 1.0E-9D);
        assertEquals(0.8D, Parameter.MUZZLE_DENSITY.value(), 1.0E-9D);
        assertEquals(0.65D, Parameter.MUZZLE_SIZE.value(), 1.0E-9D);
        assertEquals(36.0D, Parameter.MUZZLE_LIFETIME.value(), 1.0E-9D);
        assertEquals(0.18D, Parameter.MUZZLE_SPEED.value(), 1.0E-9D);
        assertEquals(0.08D, Parameter.MUZZLE_UPDRAFT.value(), 1.0E-9D);
        assertEquals(0.003D, Parameter.MUZZLE_SPREAD.value(), 1.0E-9D);
        assertEquals(0.60D, Parameter.MUZZLE_ALPHA.value(), 1.0E-9D);
        assertEquals(0.13D, Parameter.MUZZLE_GREY.value(), 1.0E-9D);
    }

    @Test
    void coupledSettersKeepSpeedAndConeRangesOrdered() {
        RVP_WreckCookoffSettings.reset();
        try {
            // 调用速度下限设置入口，超过旧上限时必须同步抬高上限。
            RVP_WreckCookoffSettings.setRoofAxisSpeedMin(1.2D);
            assertEquals(1.2D, Parameter.ROOF_SPEED_MIN.value(), 1.0E-9D);
            assertEquals(1.2D, Parameter.ROOF_SPEED_MAX.value(), 1.0E-9D);
            // 调用圆锥远端设置入口，低于根部时必须同步收窄根部。
            RVP_WreckCookoffSettings.setRoofConeTip(0.05D);
            assertEquals(0.05D, Parameter.ROOF_CONE_BASE.value(), 1.0E-9D);
            assertEquals(0.05D, Parameter.ROOF_CONE_TIP.value(), 1.0E-9D);
            // 调用贴图倍率上限设置入口，低于当前下限时必须同步收窄下限。
            RVP_WreckCookoffSettings.setRoofTextureScaleMax(0.5D);
            assertEquals(0.5D, Parameter.ROOF_SCALE_MIN.value(), 1.0E-9D);
            assertEquals(0.5D, Parameter.ROOF_SCALE_MAX.value(), 1.0E-9D);
            // 调用车顶偏置设置入口，越界值必须钳制在指令约定范围内。
            RVP_WreckCookoffSettings.setRoofVerticalOffset(6.0D);
            assertEquals(RVP_WreckCookoffSettings.ROOF_VERTICAL_OFFSET_MAX,
                    Parameter.ROOF_VERTICAL_OFFSET.value(), 1.0E-9D);
            RVP_WreckCookoffSettings.setRoofVerticalOffset(-6.0D);
            assertEquals(RVP_WreckCookoffSettings.ROOF_VERTICAL_OFFSET_MIN,
                    Parameter.ROOF_VERTICAL_OFFSET.value(), 1.0E-9D);
        } finally {
            // 调用本项目重置入口，避免静态会话参数污染其他测试。
            RVP_WreckCookoffSettings.reset();
        }
    }
}
