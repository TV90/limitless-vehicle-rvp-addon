package org.ywzj.rvp.weapon.sound;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.network.S2CVehicleSound.Kind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证导弹声音分类互斥、近远边界，以及只为未追踪载具的听者补发的兜底范围。 */
class RVP_VehicleSoundServiceTest {

    @Test
    void coldLaunchWinsForSurfaceMissilesAndAircraftAlwaysUsesAirSound() {
        // 调用双端共用分类：地面、水面冷发射只使用冷发射声，不叠加普通地面导弹声。
        assertEquals(Kind.COLD_LAUNCH, RVP_VehicleSoundService.resolveMissileKind(true, false, true, 1));
        assertEquals(Kind.SURFACE_TO_AIR, RVP_VehicleSoundService.resolveMissileKind(true, false, true, 0));
        // 空中载具分类不以是否接地为依据，冷发射配置也不能把机载导弹分到地面组。
        assertEquals(Kind.AIR2AIR, RVP_VehicleSoundService.resolveMissileKind(false, true, true, 0));
        assertEquals(Kind.AIR2AIR, RVP_VehicleSoundService.resolveMissileKind(false, true, true, 30));
        assertNull(RVP_VehicleSoundService.resolveMissileKind(true, false, false, 30));
        assertNull(RVP_VehicleSoundService.resolveMissileKind(false, false, true, 30));
    }

    @Test
    void coldLaunchKeepsFiveHundredTwelveBoundaryAndAircraftKeepsItsOwnRange() {
        // 调用消息共用距离规则：512 格包含在近音内，其外改用远音。
        assertFalse(Kind.COLD_LAUNCH.usesFarSound(0));
        assertFalse(Kind.COLD_LAUNCH.usesFarSound(512));
        assertTrue(Kind.COLD_LAUNCH.usesFarSound(512.01));
        assertEquals(2048, Kind.COLD_LAUNCH.maximumDistance());
        assertEquals(2048, Kind.SURFACE_TO_AIR.maximumDistance());
        assertEquals(150, Kind.AIR2AIR.maximumDistance());
        assertFalse(Kind.SURFACE_TO_AIR.usesFarSound(2048));
        assertFalse(Kind.AIR2AIR.usesFarSound(150));
    }

    @Test
    void fallbackOnlyCoversListenersOutsideTheEntityTrackingRange() {
        // 载具实体按 32 区块追踪，换算 512 格；追踪范围内的玩家已由客户端本地播放覆盖。
        assertEquals(512.0D, RVP_VehicleSoundService.trackingRangeBlocks(32));
        assertFalse(RVP_VehicleSoundService.needsFallback(0.0D, 512.0D));
        assertFalse(RVP_VehicleSoundService.needsFallback(512.0D * 512.0D, 512.0D));
        // 追踪范围外的听者收不到本体开火广播，只能由独立声音消息兜底。
        assertTrue(RVP_VehicleSoundService.needsFallback(512.01D * 512.01D, 512.0D));
        // 极端小的追踪范围不能低于服务端实体追踪下限 16 格。
        assertEquals(16.0D, RVP_VehicleSoundService.trackingRangeBlocks(0));
        // 本体取“实体追踪距离”与“服务端视距”的较小值：视距 10 区块时上限 160 格。
        assertEquals(160.0D, RVP_VehicleSoundService.effectiveTrackingBlocks(32, 10));
        assertEquals(512.0D, RVP_VehicleSoundService.effectiveTrackingBlocks(32, 32));
    }
}
