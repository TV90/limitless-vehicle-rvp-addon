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
    void airBurstRequiresThirtyFiveBlocksOfClearanceAboveGround() {
        // 恰好 35 格算空中起爆；差 0.1 格就不建灯；地下爆心得到负离地高度同样不建灯。
        assertTrue(RVP_DefaultExplosionVisualService.isAirBurstAt(99.0D, 64));
        assertTrue(RVP_DefaultExplosionVisualService.isAirBurstAt(100.0D, 65));
        assertFalse(RVP_DefaultExplosionVisualService.isAirBurstAt(98.9D, 64));
        assertFalse(RVP_DefaultExplosionVisualService.isAirBurstAt(64.0D, 64));
        assertFalse(RVP_DefaultExplosionVisualService.isAirBurstAt(40.0D, 64));
        // 非有限值一律按非空中起爆，不能把异常坐标当成空爆。
        assertFalse(RVP_DefaultExplosionVisualService.isAirBurstAt(Double.NaN, 64));
        assertFalse(RVP_DefaultExplosionVisualService.isAirBurstAt(Double.POSITIVE_INFINITY, 64));
        assertEquals(35.0D, RVP_DefaultExplosionVisualService.MIN_AIR_BURST_CLEARANCE);
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
