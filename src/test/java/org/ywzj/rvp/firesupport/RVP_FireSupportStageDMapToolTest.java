package org.ywzj.rvp.firesupport;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.firesupport.RVP_ClientFireSupportProfile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段 D 地图工具的 profile 解析与架构边界测试。 */
class RVP_FireSupportStageDMapToolTest {
    /** 最小但包含三类预设的当前 schema 客户端快照。 */
    private static final String PROFILE_JSON = """
            {
              "schema_version":2,
              "display":{"translation_key":"profile.test"},
              "holder_policy":{"required_item":"ywzj_rvp:fire_support_terminal","allowed_hands":["main","off"]},
              "call_stage":{"base_duration_ticks":800,"cancel_on_player_death":true,"cancel_on_terminal_lost":true,"cancel_on_disconnect":true},
              "strike_stage":{"cease_fire_delay_ticks":80},
              "limits":{"min_target_distance_m":16,"max_target_distance_m":2048,"max_rounds_per_mission":96,"max_active_missions_per_player":1,"max_active_missions_global":16,"request_cooldown_ticks":100,"max_mission_duration_ticks":2400,"max_loaded_chunks_per_mission":8,"max_parameter_count":16},
              "munitions":[{"id":"he","translation_key":"munition.he","rounds_per_unit":6,"registration_phase_enabled":true,"weapons":[{"weapon":"rvp:test","weight":1,"delivery":{"type":"rvp:vertical_projectile","data":{}}}]}],
              "fire_modes":[{"id":"effect","translation_key":"mode.effect","call_duration_multiplier":1.5,"dispersion_multiplier":1.0,"phases":[{"id":"main","translation_key":"phase.main","start_delay_ticks":0,"rounds":{"base_multiplier":2.0,"rounding":"ceil"},"duration_ticks":100}]}],
              "patterns":[
                {"id":"point","translation_key":"pattern.point","type":"rvp:point","data":{"parameters":{"radius_m":{"type":"double","default":40,"min":5,"max":100,"step":5,"unit":"m"}}}},
                {"id":"line","translation_key":"pattern.line","type":"rvp:line","data":{"parameters":{"length_m":{"type":"double","default":120,"min":20,"max":320,"step":5,"unit":"m"},"width_m":{"type":"double","default":24,"min":4,"max":80,"step":1,"unit":"m"}}}},
                {"id":"creeping","translation_key":"pattern.creeping","type":"rvp:creeping","data":{"parameters":{"length_m":{"type":"double","default":160,"min":40,"max":480,"step":10,"unit":"m"},"width_m":{"type":"double","default":32,"min":4,"max":128,"step":1,"unit":"m"},"step_m":{"type":"double","default":40,"min":10,"max":80,"step":5,"unit":"m"}}}}
              ]
            }
            """;

    @Test
    void clientProfilePreservesDynamicParametersAndGenericPhases() {
        RVP_ClientFireSupportProfile profile = RVP_ClientFireSupportProfile.parse(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("rvp", "test"), PROFILE_JSON);
        assertEquals(3, profile.patterns().size());
        assertEquals(java.util.Set.of("length_m", "width_m", "step_m"), profile.patterns().get(2).parameters().keySet());
        assertEquals(2.0, profile.fireModes().get(0).phases().get(0).baseMultiplier());
        assertEquals(800, profile.baseCallDurationTicks());
    }

    @Test
    void tacticalMapHasToolHostAndNoUnsafeDirectVehicleReads() throws Exception {
        String source = Files.readString(Path.of("src/main/java/org/ywzj/rvp/client/screen/RVP_TacticalMapScreen.java"));
        assertTrue(source.contains("implements RVP_TacticalMapHost"));
        assertTrue(source.contains("mapTool.renderOverlay(this"));
        assertFalse(source.contains("LocalVehiclePlayer.instance.getWeaponUnit()"));
        assertFalse(source.contains("LocalVehiclePlayer.instance.serverEntities"));
        assertFalse(source.contains("LocalVehiclePlayer.instance.vehicle"));
    }

    @Test
    void terminalToolUsesTypedPreviewAndStageCNetworkMessages() throws Exception {
        String source = Files.readString(Path.of("src/main/java/org/ywzj/rvp/client/firesupport/RVP_FireSupportMapTool.java"));
        assertTrue(source.contains("RVP_FireSupportPreviewTypes.get(pattern.type())"));
        assertTrue(source.contains("new C2SRequestFireSupport("));
        assertTrue(source.contains("new C2SRequestFireSupportCeaseFire("));
        assertTrue(source.contains("RVP_FireSupportMissionState.CALLING"));
        assertTrue(source.contains("primaryActionLabel(mission)"));
        assertTrue(source.contains("inboundHeadingDegrees"));
        assertTrue(source.contains("host.blocksPerPixel() * 48.0"));
        assertTrue(source.contains("inboundHeadingDegrees + side(host, mouseX) * 5.0"));
        assertTrue(source.contains("initializeInboundHeading(point)"));
        assertFalse(source.contains("weaponId.getPath().equals"));
    }
}
