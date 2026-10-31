package org.ywzj.rvp.weapon.visual;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_DefaultExplosionEventDataTest {
    @Test
    void roundTripsEveryWeaponKind() {
        for (RVP_EnumWeaponKind kind : RVP_EnumWeaponKind.values()) {
            assertEquals(kind, RVP_DefaultExplosionEventData.decodeWeaponKind(
                    RVP_DefaultExplosionEventData.encode(kind)));
        }
    }

    @Test
    void malformedOrMissingKindFallsBackToRocket() {
        assertEquals(RVP_EnumWeaponKind.ROCKET,
                RVP_DefaultExplosionEventData.decodeWeaponKind(null));
        assertEquals(RVP_EnumWeaponKind.ROCKET,
                RVP_DefaultExplosionEventData.decodeWeaponKind("not-json"));
        assertEquals(RVP_EnumWeaponKind.ROCKET,
                RVP_DefaultExplosionEventData.decodeWeaponKind("{}"));
        assertEquals(RVP_EnumWeaponKind.ROCKET,
                RVP_DefaultExplosionEventData.decodeWeaponKind("{\"weapon_kind\":\"unknown\"}"));
    }

    @Test
    void cannonSoundFlagRoundTripsAndDefaultsToFalse() {
        assertTrue(RVP_DefaultExplosionEventData.decodeCannonSound(
                RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.MACHINEGUN, null, true)));
        assertFalse(RVP_DefaultExplosionEventData.decodeCannonSound(
                RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.MACHINEGUN, null, false)));
        assertFalse(RVP_DefaultExplosionEventData.decodeCannonSound(
                RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.MACHINEGUN)));
    }

    @Test
    void malformedOrMissingCannonSoundFlagFallsBackToFalse() {
        assertFalse(RVP_DefaultExplosionEventData.decodeCannonSound(null));
        assertFalse(RVP_DefaultExplosionEventData.decodeCannonSound("not-json"));
        assertFalse(RVP_DefaultExplosionEventData.decodeCannonSound("{}"));
        assertFalse(RVP_DefaultExplosionEventData.decodeCannonSound(
                "{\"cannon_sound\":\"true\"}"));
        assertFalse(RVP_DefaultExplosionEventData.decodeCannonSound(
                "{\"cannon_sound\":false}"));
    }

    @Test
    void airBurstFlagRoundTripsAndLegacyPayloadsStayAirBurst() {
        // 起爆性质决定客户端是否建灯：显式 true/false 必须原样往返。
        assertTrue(RVP_DefaultExplosionEventData.decodeAirBurst(
                RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.MISSILE, null, false, true)));
        assertFalse(RVP_DefaultExplosionEventData.decodeAirBurst(
                RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.MISSILE, null, false, false)));
        // 加入该标记之前的事件没有这个键，按“空中起爆”处理，保持旧光效。
        assertTrue(RVP_DefaultExplosionEventData.decodeAirBurst(null));
        assertTrue(RVP_DefaultExplosionEventData.decodeAirBurst("{}"));
        assertTrue(RVP_DefaultExplosionEventData.decodeAirBurst("{\"weapon_kind\":\"missile\"}"));
        assertTrue(RVP_DefaultExplosionEventData.decodeAirBurst("{\"air_burst\":\"yes\"}"));
    }

    @Test
    void screenLightRequiresBothFlashAndAirBurst() {
        String ground = RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.ROCKET, null, true, false);
        String air = RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.ROCKET, null, true, true);
        // 地面/命中起爆不建灯，空爆建灯；关闭闪光时两者都不建灯。
        assertFalse(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, ground));
        assertTrue(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, air));
        assertFalse(RVP_DefaultExplosionEventData.shouldEmitScreenLight(false, air));
        // 缺省载荷没有标记时保持旧行为（建灯）。
        assertTrue(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, "{}"));
    }

    @Test
    void vehicleSoundProfileRoundTripsWithoutPretendingToBeAWeaponKind() {
        String encoded = RVP_DefaultExplosionEventData.encodeVehicleSoundProfile();
        assertTrue(RVP_DefaultExplosionEventData.decodeVehicleSoundProfile(encoded));
        assertFalse(RVP_DefaultExplosionEventData.decodeVehicleSoundProfile(null));
        assertFalse(RVP_DefaultExplosionEventData.decodeVehicleSoundProfile("not-json"));
        assertFalse(RVP_DefaultExplosionEventData.decodeVehicleSoundProfile(
                "{\"sound_profile\":\"missile\"}"));
        assertFalse(RVP_DefaultExplosionEventData.decodeVehicleSoundProfile(
                "{\"sound_profile\":true}"));
    }
}
