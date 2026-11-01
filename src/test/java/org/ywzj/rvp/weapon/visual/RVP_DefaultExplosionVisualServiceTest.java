package org.ywzj.rvp.weapon.visual;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_DefaultExplosionVisualServiceTest {
    @Test
    void eitherCaliberFieldAboveSixteenMillimetersUsesCannonSound() {
        assertFalse(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.MACHINEGUN, null, null));
        assertFalse(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.MACHINEGUN,
                16.0F, 16.0F));
        assertTrue(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.MACHINEGUN,
                16.01F, 7.62F));
        assertTrue(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.MACHINEGUN,
                5.8F, 30.0F));
        assertFalse(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.MACHINEGUN,
                -1.0F, 0.0F));
        assertFalse(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.MACHINEGUN, Float.NaN, Float.POSITIVE_INFINITY));
    }

    @Test
    void clearanceIsTheOnlyFactSentToTheClient() {
        // 服务端只算离地高度事实：门槛（默认100格）由客户端配置决定，不在服务端判定。
        assertEquals(35F, RVP_DefaultExplosionVisualService.clearanceAt(99.0D, 64), 0.00001F);
        assertEquals(0F, RVP_DefaultExplosionVisualService.clearanceAt(64.0D, 64), 0.00001F);
        assertEquals(-24F, RVP_DefaultExplosionVisualService.clearanceAt(40.0D, 64), 0.00001F);
        // 非有限坐标按 0 处理，避免把 NaN 写进事件载荷。
        assertEquals(0F, RVP_DefaultExplosionVisualService.clearanceAt(Double.NaN, 64), 0.00001F);
        assertEquals(0F, RVP_DefaultExplosionVisualService.clearanceAt(Double.POSITIVE_INFINITY, 64), 0.00001F);
        // 世界或坐标缺失时返回 0，不抛异常。
        assertEquals(0, RVP_DefaultExplosionVisualService.groundHeightAt(null, null));
        assertEquals(0F, RVP_DefaultExplosionVisualService.clearance(null, null), 0.00001F);
    }

    @Test
    void largeCaliberDoesNotChangeNonMachinegunProfiles() {
        assertFalse(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.ROCKET, 120.0F, 125.0F));
        assertFalse(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.MISSILE, 120.0F, 125.0F));
        assertFalse(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.BOMB, 120.0F, 125.0F));
    }
}
