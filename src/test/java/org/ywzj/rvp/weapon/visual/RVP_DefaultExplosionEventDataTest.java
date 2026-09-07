package org.ywzj.rvp.weapon.visual;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
