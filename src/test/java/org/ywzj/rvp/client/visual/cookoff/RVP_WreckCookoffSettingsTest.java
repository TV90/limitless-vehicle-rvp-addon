package org.ywzj.rvp.client.visual.cookoff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证殉燃游戏内调参的默认值、重置值和参数有序约束。 */
class RVP_WreckCookoffSettingsTest {

    @Test
    void parametersResetToTheImplementedDefaults() {
        // 调用本项目会话重置入口，确认新参数与当前视觉方案的默认值一致。
        RVP_WreckCookoffSettings.reset();
        assertEquals(9.0D, RVP_WreckCookoffSettings.height, 1.0E-9D);
        assertEquals(3.0D, RVP_WreckCookoffSettings.length, 1.0E-9D);
        assertEquals(1.0D, RVP_WreckCookoffSettings.density, 1.0E-9D);
        assertEquals(10.0D, RVP_WreckCookoffSettings.growthTicks, 1.0E-9D);
        assertEquals(20.0D, RVP_WreckCookoffSettings.shrinkTicks, 1.0E-9D);
        assertEquals(0.8D, RVP_WreckCookoffSettings.roofAxisSpeedMin, 1.0E-9D);
        assertEquals(1.0D, RVP_WreckCookoffSettings.roofAxisSpeedMax, 1.0E-9D);
        assertEquals(0.12D, RVP_WreckCookoffSettings.roofConeBase, 1.0E-9D);
        assertEquals(0.95D, RVP_WreckCookoffSettings.roofConeTip, 1.0E-9D);
        assertEquals(0.11D, RVP_WreckCookoffSettings.roofOutwardSpeed, 1.0E-9D);
        assertEquals(1.0D, RVP_WreckCookoffSettings.roofTextureScaleMin, 1.0E-9D);
        assertEquals(3.0D, RVP_WreckCookoffSettings.roofTextureScaleMax, 1.0E-9D);
        assertEquals(-0.5D, RVP_WreckCookoffSettings.roofVerticalOffset, 1.0E-9D);
        assertEquals(1.0D, RVP_WreckCookoffSettings.muzzleSmokeDensity, 1.0E-9D);
        assertEquals(0.65D, RVP_WreckCookoffSettings.muzzleSmokeSize, 1.0E-9D);
        assertEquals(36.0D, RVP_WreckCookoffSettings.muzzleSmokeLifetime, 1.0E-9D);
        assertEquals(0.12D, RVP_WreckCookoffSettings.muzzleSmokeAxisSpeed, 1.0E-9D);
        assertEquals(0.045D, RVP_WreckCookoffSettings.muzzleSmokeUpdraft, 1.0E-9D);
        assertEquals(0.025D, RVP_WreckCookoffSettings.muzzleSmokeSpread, 1.0E-9D);
        assertEquals(0.60D, RVP_WreckCookoffSettings.muzzleSmokeAlpha, 1.0E-9D);
        assertEquals(0.13D, RVP_WreckCookoffSettings.muzzleSmokeGrey, 1.0E-9D);
    }

    @Test
    void coupledSettersKeepSpeedAndConeRangesOrdered() {
        RVP_WreckCookoffSettings.reset();
        try {
            // 调用速度下限设置入口，超过旧上限时必须同步抬高上限。
            RVP_WreckCookoffSettings.setRoofAxisSpeedMin(1.2D);
            assertEquals(1.2D, RVP_WreckCookoffSettings.roofAxisSpeedMin, 1.0E-9D);
            assertEquals(1.2D, RVP_WreckCookoffSettings.roofAxisSpeedMax, 1.0E-9D);
            // 调用圆锥远端设置入口，低于根部时必须同步收窄根部。
            RVP_WreckCookoffSettings.setRoofConeTip(0.05D);
            assertEquals(0.05D, RVP_WreckCookoffSettings.roofConeBase, 1.0E-9D);
            assertEquals(0.05D, RVP_WreckCookoffSettings.roofConeTip, 1.0E-9D);
            // 调用贴图倍率上限设置入口，低于当前下限时必须同步收窄下限。
            RVP_WreckCookoffSettings.setRoofTextureScaleMax(0.5D);
            assertEquals(0.5D, RVP_WreckCookoffSettings.roofTextureScaleMin, 1.0E-9D);
            assertEquals(0.5D, RVP_WreckCookoffSettings.roofTextureScaleMax, 1.0E-9D);
            // 调用车顶偏置设置入口，越界值必须钳制在指令约定范围内。
            RVP_WreckCookoffSettings.setRoofVerticalOffset(6.0D);
            assertEquals(RVP_WreckCookoffSettings.ROOF_VERTICAL_OFFSET_MAX,
                    RVP_WreckCookoffSettings.roofVerticalOffset, 1.0E-9D);
            RVP_WreckCookoffSettings.setRoofVerticalOffset(-6.0D);
            assertEquals(RVP_WreckCookoffSettings.ROOF_VERTICAL_OFFSET_MIN,
                    RVP_WreckCookoffSettings.roofVerticalOffset, 1.0E-9D);
        } finally {
            // 调用本项目重置入口，避免静态会话参数污染其他测试。
            RVP_WreckCookoffSettings.reset();
        }
    }
}
