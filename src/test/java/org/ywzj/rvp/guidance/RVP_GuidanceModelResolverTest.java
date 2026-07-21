package org.ywzj.rvp.guidance;

import com.google.gson.Gson;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataAdapter;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataARM;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_GuidanceModelResolverTest {

    private final Gson gson = new Gson();

    @Test
    void launchConfigUsesOnlyMainFireControlFields() {
        RVP_GuidanceData guidance = parse("""
                {
                  "guidance_type":"IR",
                  "lock_target_distance_range":"[[0,120]]",
                  "lock_altitude_range":"[[20,inf]]",
                  "enable_ir_hmd":false,
                  "max_lock_angle":10,
                  "max_off_axis_lock_angle":45,
                  "lock_angle_gate":{"[[0,100]]":null,"[[100,inf]]":"[[0,30]]"},
                  "terminal_guidance":{
                    "guidance_type":"ARH",
                    "max_lock_angle":30
                  }
                }
                """);

        RVP_GuidanceLaunchConfig launch = RVP_GuidanceModelResolver.resolveLaunch(guidance);
        assertEquals(RVP_EnumGuidanceType.IR, launch.guidanceType());
        assertTrue(launch.targetDistanceRange().contains(120f));
        assertTrue(launch.altitudeRange().contains(20f));
        assertFalse(launch.enableIrHmd());
        assertEquals(10, launch.maxLockAngle());
        assertEquals(5f, launch.maxLockHalfAngle());
        assertEquals(45, launch.maxOffAxisLockAngle());
        assertEquals(2, launch.angleGate().size());
    }

    @Test
    void activeResolverSelectsExactlyOnePhase() {
        RVP_GuidanceData guidance = parse("""
                {
                  "guidance_type":"ARM",
                  "radiation_pulse_memory_tick":80,
                  "arm_memory_tick":160,
                  "arm_locked_emitter_bonus":0.75,
                  "guidance_tick_range":"[[0,100]]",
                  "guidance_target_distance_range":"[[100,inf]]",
                  "max_lock_angle":8,
                  "max_guidance_angle":50,
                  "predict_target_pos":false,
                  "terminal_guidance":{
                    "guidance_type":"ARH",
                    "guidance_target_distance_range":"[[0,100]]",
                    "max_lock_angle":20,
                    "max_guidance_angle":70,
                    "scan_interval_tick":3,
                    "predict_target_pos":true,
                    "active_radar_activation_range":32,
                    "enable_inertial_guidance":true
                  }
                }
                """);

        RVP_GuidanceActiveConfig main = RVP_GuidanceModelResolver.resolveActive(
                guidance,
                RVP_GuidancePhase.MAIN
        );
        assertEquals(RVP_GuidancePhase.MAIN, main.phase());
        assertEquals(RVP_EnumGuidanceType.ARM, main.guidanceType());
        assertTrue(main.tickRange().contains(100));
        assertTrue(main.targetDistanceRange().contains(500f));
        assertEquals(4f, main.maxLockHalfAngle());
        assertEquals(0f, main.gpsSpreadRadius());
        assertFalse(main.predictTargetPos());

        RVP_GuidanceActiveConfig terminal = RVP_GuidanceModelResolver.resolveActive(
                guidance,
                RVP_GuidancePhase.TERMINAL
        );
        assertEquals(RVP_GuidancePhase.TERMINAL, terminal.phase());
        assertEquals(RVP_EnumGuidanceType.ARH, terminal.guidanceType());
        assertNull(terminal.tickRange());
        assertTrue(terminal.targetDistanceRange().contains(100f));
        assertEquals(10f, terminal.maxLockHalfAngle());
        assertEquals(70, terminal.maxGuidanceAngle());
        assertEquals(3, terminal.scanIntervalTick());
        assertTrue(terminal.predictTargetPos());
        assertEquals(32, terminal.activeRadarActivationRange());
        assertTrue(terminal.enableInertialGuidance());
        assertEquals(80, terminal.radiationPulseMemoryTick());
        assertEquals(160, terminal.armMemoryTick());
        assertEquals(0.75f, terminal.armLockedEmitterBonus());
        assertEquals(0f, terminal.gpsSpreadRadius());
    }

    @Test
    void armSubtypeCarriesMemoryValues() {
        RVP_GuidanceData guidance = parse("""
                {
                  "guidance_type":"ARM",
                  "radiation_pulse_memory_tick":45,
                  "arm_memory_tick":90,
                  "arm_locked_emitter_bonus":1.25
                }
                """);

        RVP_GuidanceDataARM arm = (RVP_GuidanceDataARM) guidance;
        assertEquals(45, arm.getRadiationPulseMemoryTick());
        assertEquals(90, arm.getArmMemoryTick());
        assertEquals(1.25f, arm.getArmLockedEmitterBonus());
    }

    @Test
    void terminalRequestWithoutTerminalDataFallsBackToMain() {
        RVP_GuidanceData guidance = parse("""
                {"guidance_type":"SARH","max_guidance_angle":55}
                """);

        RVP_GuidanceActiveConfig active = RVP_GuidanceModelResolver.resolveActive(
                guidance,
                RVP_GuidancePhase.TERMINAL
        );

        assertEquals(RVP_GuidancePhase.MAIN, active.phase());
        assertEquals(RVP_EnumGuidanceType.SARH, active.guidanceType());
        assertEquals(55, active.maxGuidanceAngle());
    }

    @Test
    void hitlSpecificValuesArePresentOnlyInMainSnapshot() {
        RVP_GuidanceData guidance = parse("""
                {
                  "guidance_type":"HITL_TV",
                  "hitl_max_turn_deg_per_tick":3,
                  "signal_source":"FIBER",
                  "hitl_max_control_dist":800,
                  "hitl_max_control_tick":250,
                  "hitl_max_look_offset":35,
                  "hitl_video_modes":["MONO","THERMAL"]
                }
                """);

        RVP_GuidanceActiveConfig active = RVP_GuidanceModelResolver.resolveActive(
                guidance,
                RVP_GuidancePhase.MAIN
        );

        assertEquals(3, active.hitlMaxTurnDegPerTick());
        assertEquals("FIBER", active.hitlSignalSource());
        assertEquals(800, active.hitlMaxControlDist());
        assertEquals(250, active.hitlMaxControlTick());
        assertEquals(35, active.hitlMaxLookOffset());
        assertEquals(2, active.hitlVideoModes().size());
    }

    private RVP_GuidanceData parse(String guidanceJson) {
        String json = "{\"guidance_data\":" + guidanceJson + "}";
        return gson.fromJson(json, GuidanceHolder.class).guidanceData;
    }

    private static final class GuidanceHolder {
        @SerializedName("guidance_data")
        @JsonAdapter(RVP_GuidanceDataAdapter.class)
        private RVP_GuidanceData guidanceData;
    }
}
