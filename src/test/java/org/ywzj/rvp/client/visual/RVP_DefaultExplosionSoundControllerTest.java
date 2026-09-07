package org.ywzj.rvp.client.visual;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_DefaultExplosionSoundControllerTest {
    @Test
    void soundIsImmediateThroughTwentyFourBlocksThenUsesSpeedOfSound() {
        assertEquals(0, RVP_DefaultExplosionSoundController.resolveArrivalTick(0.0D));
        assertEquals(0, RVP_DefaultExplosionSoundController.resolveArrivalTick(24.0D));
        assertEquals(2, RVP_DefaultExplosionSoundController.resolveArrivalTick(24.01D));
        assertEquals(8, RVP_DefaultExplosionSoundController.resolveArrivalTick(128.0D));
        assertEquals(90, RVP_DefaultExplosionSoundController.resolveArrivalTick(1536.0D));
    }

    @Test
    void weaponKindsResolveToIndependentProfilesWithoutWeaponIds() {
        assertEquals(RVP_DefaultExplosionSoundController.SoundProfile.MACHINEGUN,
                RVP_DefaultExplosionSoundController.resolveProfile(RVP_EnumWeaponKind.MACHINEGUN, 4.0F));
        assertEquals(RVP_DefaultExplosionSoundController.SoundProfile.ROCKET,
                RVP_DefaultExplosionSoundController.resolveProfile(RVP_EnumWeaponKind.ROCKET, 4.0F));
        assertEquals(RVP_DefaultExplosionSoundController.SoundProfile.MISSILE,
                RVP_DefaultExplosionSoundController.resolveProfile(RVP_EnumWeaponKind.MISSILE, 4.0F));
        assertEquals(RVP_DefaultExplosionSoundController.SoundProfile.BOMB,
                RVP_DefaultExplosionSoundController.resolveProfile(RVP_EnumWeaponKind.BOMB, 4.0F));
        assertEquals(RVP_DefaultExplosionSoundController.SoundProfile.ROCKET,
                RVP_DefaultExplosionSoundController.resolveProfile(RVP_EnumWeaponKind.DISPENSER, 4.0F));
        assertEquals(RVP_DefaultExplosionSoundController.SoundProfile.MISSILE,
                RVP_DefaultExplosionSoundController.resolveProfile(RVP_EnumWeaponKind.DISPENSER, 8.0F));
    }

}
