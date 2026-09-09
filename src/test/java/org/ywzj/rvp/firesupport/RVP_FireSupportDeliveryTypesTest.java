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
                "max_apex_above_impact_m":512,"preload_ticks":80,"heading_jitter_deg":0}
                """);
        roundTrip(RVP_FireSupportDeliveryTypes.AIR_LAUNCHED_PROJECTILE, """
                {"aircraft_id":"ywzj_vehicle:rafale","rack_offset":{"x":0,"y":-2,"z":0},
                "entry_distance_m":1280,"exit_distance_m":768,
                "release_altitude_above_impact_m":256,"min_release_altitude_above_impact_m":96,
                "carrier_speed_m_per_tick":2.5,"preload_ticks":80,"heading_jitter_deg":0}
                """);
        assertEquals(3, RVP_FireSupportDeliveryTypes.all().size());
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
    void capabilityValidationRejectsGuidanceAndWrongKinds() {
        ResourceLocation weaponId = ResourceLocation.fromNamespaceAndPath("rvp", "test");
        RVP_FireSupportResolvedWeapon guidedRocket = new RVP_FireSupportResolvedWeapon(
                RVP_EnumWeaponKind.ROCKET, RVP_EnumGuidanceType.GPS, false, false,
                false, false, false, 1200, 0, false, 10.0F, 2.0F);
        RVP_FireSupportProblemCollector groundProblems = new RVP_FireSupportProblemCollector();
        RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.GROUND_LAUNCHED_PROJECTILE)
                .validateWeapon(weaponId, guidedRocket, groundProblems, "weapon");
        assertThrows(IllegalArgumentException.class, groundProblems::throwIfAny);

        RVP_FireSupportProblemCollector airProblems = new RVP_FireSupportProblemCollector();
        RVP_FireSupportDeliveryTypes.get(RVP_FireSupportDeliveryTypes.AIR_LAUNCHED_PROJECTILE)
                .validateWeapon(weaponId, guidedRocket, airProblems, "weapon");
        airProblems.throwIfAny();

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
