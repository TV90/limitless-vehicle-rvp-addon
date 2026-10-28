package org.ywzj.rvp.weapon.visual;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

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
    void largeCaliberDoesNotChangeNonMachinegunProfiles() {
        assertFalse(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.ROCKET, 120.0F, 125.0F));
        assertFalse(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.MISSILE, 120.0F, 125.0F));
        assertFalse(RVP_DefaultExplosionVisualService.shouldUseCannonSound(
                RVP_EnumWeaponKind.BOMB, 120.0F, 125.0F));
    }
}
