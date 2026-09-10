package org.ywzj.rvp.firesupport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportProblemCollector;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportResolvedWeapon;
import org.ywzj.rvp.firesupport.delivery.RVP_FireSupportDeliveryTypes;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 两种真实入场投送的严格 parser、类型化 codec 与能力边界回归。 */
class RVP_FireSupportDeliveryTypesTest {
    @Test
    void groundAndAirDataRoundTripWithoutTypeSpecialCases() {
        roundTrip(RVP_FireSupportDeliveryTypes.GROUND_LAUNCHED_PROJECTILE, """
                {"launch_distance_m":768,"min_launch_distance_m":256,"launch_height_above_ground_m":2.5,
                "max_apex_above_impact_m":512,"preload_ticks":80,"heading_jitter_deg":0,
                "entry_speed_m_per_tick":0}
                """);
        roundTrip(RVP_FireSupportDeliveryTypes.AIR_LAUNCHED_PROJECTILE, """
                {"aircraft_id":"ywzj_vehicle:rafale","rack_offset":{"x":0,"y":-2,"z":0},
                "entry_distance_m":1280,"exit_distance_m":768,
                "release_altitude_above_impact_m":256,"min_release_altitude_above_impact_m":96,
                "gps_release_distance_m":900,"carrier_speed_m_per_tick":2.5,
                "preload_ticks":80,"heading_jitter_deg":0}
                """);
        assertEquals(3, RVP_FireSupportDeliveryTypes.all().size());
    }

    @Test
    void gpsAirReleaseDistanceDefaultsTo768AndRejectsOutOfRangeValues() {
        var factory = RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.AIR_LAUNCHED_PROJECTILE);
        RVP_FireSupportProblemCollector defaultProblems = new RVP_FireSupportProblemCollector();
        Object parsed = factory.parse(JsonParser.parseString("""
                {"aircraft_id":"ywzj_vehicle:rafale","rack_offset":{"x":0,"y":-2,"z":0}}
                """).getAsJsonObject(), defaultProblems, "delivery.data");
        defaultProblems.throwIfAny();
        assertEquals(768.0D, ((RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData) parsed)
                .gpsReleaseDistanceMeters());

        for (double invalid : new double[] {0.0D, -1.0D, 4096.1D}) {
            RVP_FireSupportProblemCollector rangeProblems = new RVP_FireSupportProblemCollector();
            factory.parse(JsonParser.parseString("""
                    {"aircraft_id":"ywzj_vehicle:rafale","rack_offset":{"x":0,"y":-2,"z":0},
                    "gps_release_distance_m":%s}
                    """.formatted(invalid)).getAsJsonObject(), rangeProblems, "delivery.data");
            assertThrows(IllegalArgumentException.class, rangeProblems::throwIfAny);
        }
    }

    @Test
    void groundEntrySpeedDefaultsToWeaponSpeedAndRejectsOutOfRangeValues() {
        var factory = RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.GROUND_LAUNCHED_PROJECTILE);
        RVP_FireSupportProblemCollector defaultProblems = new RVP_FireSupportProblemCollector();
        Object parsed = factory.parse(JsonParser.parseString("""
                {"launch_distance_m":768,"min_launch_distance_m":256,"launch_height_above_ground_m":2.5,
                "max_apex_above_impact_m":512,"preload_ticks":80,"heading_jitter_deg":0}
                """).getAsJsonObject(), defaultProblems, "delivery.data");
        defaultProblems.throwIfAny();
        assertEquals(0.0D, ((RVP_FireSupportDeliveryTypes.GroundLaunchedProjectileData) parsed)
                .entrySpeedMetersPerTick());

        RVP_FireSupportProblemCollector rangeProblems = new RVP_FireSupportProblemCollector();
        factory.parse(JsonParser.parseString("""
                {"launch_distance_m":768,"min_launch_distance_m":256,"launch_height_above_ground_m":2.5,
                "max_apex_above_impact_m":512,"preload_ticks":80,"heading_jitter_deg":0,
                "entry_speed_m_per_tick":64.1}
                """).getAsJsonObject(), rangeProblems, "delivery.data");
        assertThrows(IllegalArgumentException.class, rangeProblems::throwIfAny);
    }

    @Test
    void parserRejectsUnknownKeysAndConditionalRanges() {
        var factory = RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.GROUND_LAUNCHED_PROJECTILE);
        RVP_FireSupportProblemCollector problems = new RVP_FireSupportProblemCollector();
        factory.parse(JsonParser.parseString("""
                {"launch_distance_m":100,"min_launch_distance_m":256,"legacy_height":2}
                """).getAsJsonObject(), problems, "delivery.data");
        assertThrows(IllegalArgumentException.class, problems::throwIfAny);
    }

    @Test
    void capabilityValidationAcceptsGpsChainsAndRejectsAirToAir() {
        ResourceLocation weaponId = ResourceLocation.fromNamespaceAndPath("rvp", "test");
        RVP_FireSupportResolvedWeapon guidedRocket = new RVP_FireSupportResolvedWeapon(
                RVP_EnumWeaponKind.ROCKET, RVP_EnumGuidanceType.GPS, false, false,
                false, false, false, 1200, 0, false, 10.0F, 2.0F);
        RVP_FireSupportProblemCollector groundProblems = new RVP_FireSupportProblemCollector();
        RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.GROUND_LAUNCHED_PROJECTILE)
                .validateWeapon(weaponId, guidedRocket, groundProblems, "weapon");
        groundProblems.throwIfAny();

        RVP_FireSupportProblemCollector airProblems = new RVP_FireSupportProblemCollector();
        RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.AIR_LAUNCHED_PROJECTILE)
                .validateWeapon(weaponId, guidedRocket, airProblems, "weapon");
        airProblems.throwIfAny();

        RVP_FireSupportResolvedWeapon terminalGpsMissile = new RVP_FireSupportResolvedWeapon(
                RVP_EnumWeaponKind.MISSILE, RVP_EnumGuidanceType.NONE, true, false,
                true, false, false, false, false, 1200, 0, false, 10.0F, 2.0F);
        RVP_FireSupportProblemCollector terminalGroundProblems = new RVP_FireSupportProblemCollector();
        RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.GROUND_LAUNCHED_PROJECTILE)
                .validateWeapon(weaponId, terminalGpsMissile, terminalGroundProblems, "weapon");
        terminalGroundProblems.throwIfAny();

        RVP_FireSupportResolvedWeapon nonGpsBomb = new RVP_FireSupportResolvedWeapon(
                RVP_EnumWeaponKind.BOMB, RVP_EnumGuidanceType.NONE, false, false,
                false, false, false, 1200, 0, false, 10.0F, 2.0F);
        RVP_FireSupportProblemCollector nonGpsGroundProblems = new RVP_FireSupportProblemCollector();
        RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.GROUND_LAUNCHED_PROJECTILE)
                .validateWeapon(weaponId, nonGpsBomb, nonGpsGroundProblems, "weapon");
        assertThrows(IllegalArgumentException.class, nonGpsGroundProblems::throwIfAny);

        RVP_FireSupportResolvedWeapon airToAirMissile = new RVP_FireSupportResolvedWeapon(
                RVP_EnumWeaponKind.MISSILE, RVP_EnumGuidanceType.AIR, true, true,
                false, false, false, 1200, 0, false, 10.0F, 2.0F);
        RVP_FireSupportProblemCollector rejectedAir = new RVP_FireSupportProblemCollector();
        RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.AIR_LAUNCHED_PROJECTILE)
                .validateWeapon(weaponId, airToAirMissile, rejectedAir, "weapon");
        assertThrows(IllegalArgumentException.class, rejectedAir::throwIfAny);
    }

    private static void roundTrip(ResourceLocation type, String json) {
        var factory = RVP_FireSupportDeliveryTypes.get(type);
        JsonObject input = JsonParser.parseString(json).getAsJsonObject();
        RVP_FireSupportProblemCollector problems = new RVP_FireSupportProblemCollector();
        Object parsed = factory.parse(input, problems, "delivery.data");
        problems.throwIfAny();
        assertEquals(input, factory.encode(parsed));
    }
}
