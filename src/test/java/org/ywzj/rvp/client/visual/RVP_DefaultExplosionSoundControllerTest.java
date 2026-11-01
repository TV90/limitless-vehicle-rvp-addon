package org.ywzj.rvp.client.visual;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.visual.RVP_DefaultExplosionSoundController.SoundDistanceBand;
import org.ywzj.rvp.client.visual.RVP_DefaultExplosionSoundController.SoundProfile;
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
        assertEquals(RVP_DefaultExplosionSoundController.SoundProfile.CANNON,
                RVP_DefaultExplosionSoundController.resolveProfile(
                        RVP_EnumWeaponKind.MACHINEGUN, 4.0F, true));
        assertEquals(RVP_DefaultExplosionSoundController.SoundProfile.MACHINEGUN,
                RVP_DefaultExplosionSoundController.resolveProfile(
                        RVP_EnumWeaponKind.MACHINEGUN, 4.0F, false));
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

    @Test
    void cannonSoundUsesThreeDistanceBandsAndStopsAfterMaximumRange() {
        RVP_DefaultExplosionSoundController.SoundProfile cannon =
                RVP_DefaultExplosionSoundController.SoundProfile.CANNON;
        assertEquals(RVP_DefaultExplosionSoundController.SoundDistanceBand.NEAR,
                RVP_DefaultExplosionSoundController.resolveSoundBand(cannon, 200.0D, 4.0F));
        assertEquals(RVP_DefaultExplosionSoundController.SoundDistanceBand.FAR,
                RVP_DefaultExplosionSoundController.resolveSoundBand(cannon, 200.01D, 4.0F));
        assertEquals(RVP_DefaultExplosionSoundController.SoundDistanceBand.FAR,
                RVP_DefaultExplosionSoundController.resolveSoundBand(cannon, 512.0D, 4.0F));
        assertEquals(RVP_DefaultExplosionSoundController.SoundDistanceBand.VERY_FAR,
                RVP_DefaultExplosionSoundController.resolveSoundBand(cannon, 512.01D, 4.0F));
        assertEquals(RVP_DefaultExplosionSoundController.SoundDistanceBand.VERY_FAR,
                RVP_DefaultExplosionSoundController.resolveSoundBand(cannon, 1536.0D, 4.0F));
        // 炮弹/火箭/导弹/航弹/载具击毁的最大传播距离均为 3560 格。
        assertEquals(RVP_DefaultExplosionSoundController.SoundDistanceBand.VERY_FAR,
                RVP_DefaultExplosionSoundController.resolveSoundBand(cannon, 3560.0D, 4.0F));
        assertEquals(RVP_DefaultExplosionSoundController.SoundDistanceBand.OUT_OF_RANGE,
                RVP_DefaultExplosionSoundController.resolveSoundBand(cannon, 3560.01D, 4.0F));
        assertEquals(3560.0D, cannon.maximumRange());
        assertEquals(3560.0D, RVP_DefaultExplosionSoundController.SoundProfile.ROCKET.maximumRange());
        assertEquals(3560.0D, RVP_DefaultExplosionSoundController.SoundProfile.MISSILE.maximumRange());
        assertEquals(3560.0D, RVP_DefaultExplosionSoundController.SoundProfile.BOMB.maximumRange());
        assertEquals(512.0D, RVP_DefaultExplosionSoundController.SoundProfile.MACHINEGUN.maximumRange());
    }

    @Test
    void rocketMissileAndBombSwitchAtFiveHundredTwelveBlocksRegardlessOfBlastRadius() {
        for (SoundProfile profile : new SoundProfile[]{SoundProfile.ROCKET, SoundProfile.MISSILE,
                SoundProfile.BOMB}) {
            // 调用固定近音边界：512 格含在近音内，其外使用远音。
            assertEquals(SoundDistanceBand.NEAR,
                    RVP_DefaultExplosionSoundController.resolveSoundBand(profile, 0.0D, 4.0F));
            assertEquals(SoundDistanceBand.NEAR,
                    RVP_DefaultExplosionSoundController.resolveSoundBand(profile, 512.0D, 4.0F));
            assertEquals(SoundDistanceBand.FAR,
                    RVP_DefaultExplosionSoundController.resolveSoundBand(profile, 512.01D, 4.0F));
            // 大当量爆炸半径不再扩大近音范围，只按固定 512 格切换。
            assertEquals(SoundDistanceBand.FAR,
                    RVP_DefaultExplosionSoundController.resolveSoundBand(profile, 600.0D, 100.0F));
            assertEquals(3560.0D, profile.maximumRange());
            assertEquals(SoundDistanceBand.OUT_OF_RANGE,
                    RVP_DefaultExplosionSoundController.resolveSoundBand(profile, 3560.01D, 100.0F));
        }
    }

    @Test
    void vehicleSoundUsesFiveHundredTwelveBlockNearBoundaryAnd3560BlockMaximumRange() {
        SoundProfile vehicle = SoundProfile.VEHICLE;
        assertEquals(SoundDistanceBand.NEAR,
                RVP_DefaultExplosionSoundController.resolveSoundBand(vehicle, 512.0D, 100.0F));
        assertEquals(SoundDistanceBand.FAR,
                RVP_DefaultExplosionSoundController.resolveSoundBand(vehicle, 512.01D, 100.0F));
        assertEquals(SoundDistanceBand.FAR,
                RVP_DefaultExplosionSoundController.resolveSoundBand(vehicle, 3560.0D, 100.0F));
        assertEquals(SoundDistanceBand.OUT_OF_RANGE,
                RVP_DefaultExplosionSoundController.resolveSoundBand(vehicle, 3560.01D, 100.0F));
        assertEquals(3560.0D, vehicle.maximumRange());
    }
}
