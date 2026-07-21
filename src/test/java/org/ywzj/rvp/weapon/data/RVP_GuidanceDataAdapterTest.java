package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_GuidanceDataAdapterTest {

    private final Gson gson = new Gson();

    @Test
    void loadsBaseGuidanceAndPreservesAngleSemantics() {
        RVP_GuidanceData guidance = parseGuidance("""
                {
                  "guidance_type": "ir",
                  "guidance_tick_range": "[[0,200]]",
                  "lock_target_distance_range": "[[0,120]]",
                  "max_lock_angle": 10,
                  "max_off_axis_lock_angle": 45,
                  "max_guidance_angle": 60
                }
                """);

        assertEquals(RVP_GuidanceData.class, guidance.getClass());
        assertEquals(RVP_EnumGuidanceType.IR, guidance.getGuidanceType());
        assertEquals(5f, guidance.getMaxLockHalfAngle());
        assertEquals(45, guidance.getMaxOffAxisLockAngle());
        assertEquals(60, guidance.getMaxGuidanceAngle());
        assertTrue(guidance.getGuidanceTickRange().contains(200));
        assertTrue(guidance.getLockTargetDistanceRange().contains(120f));
    }

    @Test
    void loadsGpsSubtype() {
        RVP_GuidanceData guidance = parseGuidance("""
                {
                  "guidance_type": "GPS",
                  "gps_spread_radius": 6.5,
                  "guidance_target_distance_range": "[[0,inf]]"
                }
                """);

        RVP_GuidanceDataGPS gps = assertInstanceOf(RVP_GuidanceDataGPS.class, guidance);
        assertEquals(RVP_EnumGuidanceType.GPS, gps.getGuidanceType());
        assertEquals(6.5f, gps.getGpsSpreadRadius());
    }

    @Test
    void loadsHitlSubtype() {
        RVP_GuidanceData guidance = parseGuidance("""
                {
                  "guidance_type": "hitl_clos_tv",
                  "hitl_max_turn_deg_per_tick": 3,
                  "signal_source": "fiber",
                  "hitl_max_control_dist": 900,
                  "hitl_max_control_tick": 300,
                  "hitl_max_look_offset": 25,
                  "hitl_video_modes": ["MONO", "THERMAL"]
                }
                """);

        RVP_GuidanceDataHITL hitl = assertInstanceOf(RVP_GuidanceDataHITL.class, guidance);
        assertEquals(RVP_EnumGuidanceType.HITL_CLOS_TV, hitl.getGuidanceType());
        assertEquals(3, hitl.getHitlMaxTurnDegPerTick());
        assertEquals("FIBER", hitl.getSignalSource());
        assertEquals(900, hitl.getHitlMaxControlDist());
        assertEquals(2, hitl.getHitlVideoModes().size());
    }

    @Test
    void loadsViewOnlyHitlAlongsideArhGuidance() {
        assertThrows(JsonParseException.class, () -> parseGuidance("""
                {
                  "guidance_type": "ARH",
                  "hitl_max_control_dist": 2000,
                  "hitl_max_control_tick": 300,
                  "hitl_video_modes": ["COLOR", "THERMAL"]
                }
                """));
    }

    @Test
    void loadsTerminalGuidanceWithIndependentRanges() {
        RVP_GuidanceData guidance = parseGuidance("""
                {
                  "guidance_type": "GPS",
                  "guidance_target_distance_range": "[[100,inf]]",
                  "terminal_guidance": {
                    "guidance_type": "AIR",
                    "guidance_start_tick": 40,
                    "guidance_start_dist": 80,
                    "guidance_start_horizontal_dist": 60,
                    "active_radar_activation_range": 32,
                    "max_lock_angle": 12,
                    "guidance_target_distance_range": "[[0,100]]",
                    "guidance_altitude_range": "[[inf,20],[40,inf]]"
                  }
                }
                """);

        RVP_TerminalGuidanceData terminal = guidance.getTerminalGuidance();
        assertEquals(RVP_EnumGuidanceType.AIR, terminal.getGuidanceType());
        assertEquals(40, terminal.getGuidanceStartTick());
        assertEquals(80f, terminal.getGuidanceStartDist());
        assertEquals(60f, terminal.getGuidanceStartHorizontalDist());
        assertEquals(32, terminal.getActiveRadarActivationRange());
        assertEquals(6f, terminal.getMaxLockHalfAngle());
        assertTrue(terminal.getGuidanceTargetDistanceRange().contains(100f));
        assertTrue(terminal.getGuidanceAltitudeRange().contains(10f));
        assertTrue(terminal.getGuidanceAltitudeRange().contains(50f));
    }

    @Test
    void allowsInfraredTerminalGuidanceAfterGpsMidcourse() {
        RVP_GuidanceData guidance = parseGuidance("""
                {
                  "guidance_type":"GPS",
                  "terminal_guidance":{
                    "guidance_type":"IR",
                    "guidance_start_dist":100,
                    "max_lock_angle":40,
                    "guidance_target_distance_range":"[[0,80]]"
                  }
                }
                """);

        assertEquals(RVP_EnumGuidanceType.GPS, guidance.getGuidanceType());
        assertEquals(RVP_EnumGuidanceType.IR, guidance.getTerminalGuidance().getGuidanceType());
        assertEquals(20f, guidance.getTerminalGuidance().getMaxLockHalfAngle());
    }

    @Test
    void rejectsSubtypeFieldsThatDoNotMatchGuidanceType() {
        assertThrows(JsonParseException.class, () -> parseGuidance("""
                {"guidance_type":"IR","gps_spread_radius":4}
                """));
        assertThrows(JsonParseException.class, () -> parseGuidance("""
                {"gps_spread_radius":4}
                """));
    }

    @Test
    void gpsRejectsHitlSubtypeFields() {
        RVP_GuidanceData plain = parseGuidance("""
                {"guidance_type":"GPS","gps_spread_radius":4}
                """);
        RVP_GuidanceDataGPS plainGps = assertInstanceOf(RVP_GuidanceDataGPS.class, plain);
        assertEquals(4f, plainGps.getGpsSpreadRadius());
        assertThrows(JsonParseException.class, () -> parseGuidance("""
                {
                  "guidance_type":"GPS",
                  "gps_spread_radius":4,
                  "hitl_max_control_tick":200
                }
                """));
    }

    @Test
    void explicitHitlGuidanceTypesRemainHitlSubtype() {
        RVP_GuidanceData guidance = parseGuidance("""
                {"guidance_type":"HITL_CLOS_TV"}
                """);

        assertInstanceOf(RVP_GuidanceDataHITL.class, guidance);
    }

    @Test
    void loadsArmSubtype() {
        RVP_GuidanceData guidance = parseGuidance("""
                {
                  "guidance_type":"ARM",
                  "radiation_pulse_memory_tick":80,
                  "arm_memory_tick":160,
                  "arm_locked_emitter_bonus":0.75
                }
                """);

        RVP_GuidanceDataARM arm = assertInstanceOf(RVP_GuidanceDataARM.class, guidance);
        assertEquals(RVP_EnumGuidanceType.ARM, arm.getGuidanceType());
        assertEquals(80, arm.getRadiationPulseMemoryTick());
        assertEquals(160, arm.getArmMemoryTick());
        assertEquals(0.75f, arm.getArmLockedEmitterBonus());
    }

    @Test
    void rejectsUnsupportedTerminalTypeAndPublicIog() {
        assertThrows(JsonParseException.class, () -> parseGuidance("""
                {
                  "guidance_type":"GPS",
                  "terminal_guidance":{"guidance_type":"SARH"}
                }
                """));
        assertThrows(JsonParseException.class, () -> parseGuidance("""
                {"guidance_type":"IOG"}
                """));
    }

    private RVP_GuidanceData parseGuidance(String guidanceJson) {
        String weaponJson = "{\"guidance_data\":" + guidanceJson + "}";
        return gson.fromJson(weaponJson, GuidanceHolder.class).guidanceData;
    }

    private static final class GuidanceHolder {
        @SerializedName("guidance_data")
        @JsonAdapter(RVP_GuidanceDataAdapter.class)
        private RVP_GuidanceData guidanceData;
    }
}
