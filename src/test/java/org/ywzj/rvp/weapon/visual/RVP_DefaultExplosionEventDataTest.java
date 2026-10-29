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
