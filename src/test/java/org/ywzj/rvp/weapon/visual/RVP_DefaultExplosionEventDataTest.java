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
        // 旧布尔标记仍可原样往返；只有缺少离地高度时才由它兜底。
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
    void clearanceRoundTripsAndMissingValueFallsBackToNull() {
        // 新载荷只带事实：离地高度按小数往返，不再携带布尔结论。
        assertEquals(35.5F, RVP_DefaultExplosionEventData.decodeClearance(
                RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.ROCKET, null, true, 35.5F)), 0.00001F);
        assertEquals(-12F, RVP_DefaultExplosionEventData.decodeClearance(
                RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.ROCKET, null, true, -12F)), 0.00001F);
        // 非有限值不写该键，客户端据此回退旧标记。
        assertEquals(null, RVP_DefaultExplosionEventData.decodeClearance(
                RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.ROCKET, null, true, Float.NaN)));
        assertEquals(null, RVP_DefaultExplosionEventData.decodeClearance("{}"));
        assertEquals(null, RVP_DefaultExplosionEventData.decodeClearance(null));
        assertEquals(null, RVP_DefaultExplosionEventData.decodeClearance("{\"clearance\":\"high\"}"));
    }

    @Test
    void screenLightComparesClearanceAgainstTheClientThreshold() {
        String low = RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.ROCKET, null, true, 30F);
        String high = RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.ROCKET, null, true, 100F);
        // 默认门槛100格：30格不建灯，恰好100格建灯；门槛调低后同一事件建灯。
        assertFalse(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, low, 100F));
        assertTrue(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, high, 100F));
        assertTrue(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, low, 20F));
        // 0或负数门槛表示不限制：贴地、洞穴内（负离地高度）也建灯。
        String underground = RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.ROCKET, null, true, -5F);
        assertTrue(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, underground, 0F));
        assertTrue(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, underground, -1F));
        // 闪光关闭时任何离地高度都不建灯。
        assertFalse(RVP_DefaultExplosionEventData.shouldEmitScreenLight(false, high, 0F));
    }

    @Test
    void legacyPayloadWithoutClearanceFallsBackToAirBurstFlag() {
        String legacyGround = RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.ROCKET, null, true, false);
        String legacyAir = RVP_DefaultExplosionEventData.encode(RVP_EnumWeaponKind.ROCKET, null, true, true);
        // 旧载荷没有离地高度：完全按旧布尔判定，不受新门槛影响。
        assertTrue(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, legacyAir, 100F));
        assertFalse(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, legacyGround, 100F));
        // 连布尔都没有的最旧载荷按“空中起爆”处理（保持加入标记前的行为）。
        assertTrue(RVP_DefaultExplosionEventData.shouldEmitScreenLight(true, "{}", 100F));
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
