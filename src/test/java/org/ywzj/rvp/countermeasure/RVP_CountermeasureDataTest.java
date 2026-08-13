package org.ywzj.rvp.countermeasure;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_CountermeasureDataTest {

    /** 完整配置解析：flare/chaff 各字段正确、类型回退、sub-system 取用。 */
    @Test
    void parseFullConfig() {
        String json = """
                {
                  "flare": {
                    "launcher_parts": ["cm_flare_left", "cm_flare_right"],
                    "total": 32,
                    "per_round": 4,
                    "burst_rounds": 8,
                    "launch_interval_tick": 4,
                    "reload_tick": 200,
                    "decoy": {
                      "lifetime_tick": 160,
                      "speed": 1.0,
                      "gravity": 0.05,
                      "drag": 0.02,
                      "spread": 0.6,
                      "glow_color": 16711680,
                      "halo_scale": 1.6
                    }
                  },
                  "chaff": {
                    "launcher_parts": ["cm_chaff_dispenser"],
                    "total": 32,
                    "per_round": 4,
                    "burst_rounds": 8,
                    "launch_interval_tick": 4,
                    "reload_tick": 200,
                    "radar_jam_radius": 8,
                    "radar_jam_count": 3,
                    "radar_jam_cooldown_tick": 60,
                    "decoy": {
                      "lifetime_tick": 100,
                      "speed": 0.2,
                      "gravity": 0.0,
                      "drag": 0.3,
                      "spread": 2.5,
                      "glow_color": 16777215,
                      "halo_scale": 0.7
                    }
                  }
                }
                """;
        RVP_CountermeasureData data = RVP_CountermeasureData.parse(JsonParser.parseString(json));
        assertNotNull(data);
        assertTrue(data.isEnabled());

        RVP_CountermeasureSystemData flare = data.getFlare();
        assertNotNull(flare);
        assertEquals(RVP_EnumCountermeasureType.FLARE, flare.resolveType(RVP_EnumCountermeasureType.FLARE));
        assertEquals(2, flare.getLauncherParts().size());
        assertEquals(32, flare.getTotal());
        assertEquals(4, flare.getPerRound());
        assertEquals(8, flare.getBurstRounds());
        assertEquals(4, flare.getLaunchIntervalTick());
        assertEquals(200, flare.getReloadTick());
        assertEquals(160, flare.getDecoy().getLifetimeTick());
        assertEquals(1.0F, flare.getDecoy().getSpeed());
        assertEquals(0.05F, flare.getDecoy().getGravity());
        assertEquals(0.02F, flare.getDecoy().getDrag());
        assertEquals(0.6F, flare.getDecoy().getSpread());
        assertEquals(16711680, flare.getDecoy().getGlowColor());
        assertEquals(1.6F, flare.getDecoy().getHaloScale());

        RVP_CountermeasureSystemData chaff = data.getChaff();
        assertNotNull(chaff);
        assertEquals(8F, chaff.getRadarJamRadius());
        assertEquals(3, chaff.getRadarJamCount());
        assertEquals(60, chaff.getRadarJamCooldownTick());
        assertEquals(0.7F, chaff.getDecoy().getHaloScale());

        assertSame(flare, data.system(RVP_EnumCountermeasureType.FLARE));
        assertSame(chaff, data.system(RVP_EnumCountermeasureType.CHAFF));
    }

    /** 空对象解析为 null（无启用子系统）。 */
    @Test
    void parseEmptyReturnsNull() {
        assertNull(RVP_CountermeasureData.parse(JsonParser.parseString("{}")));
    }

    /** 非对象 JSON 解析为 null。 */
    @Test
    void parseNonObjectReturnsNull() {
        assertNull(RVP_CountermeasureData.parse(JsonParser.parseString("[]")));
        assertNull(RVP_CountermeasureData.parse(JsonParser.parseString("\"x\"")));
        assertNull(RVP_CountermeasureData.parse(null));
    }

    /** 子系统 total=0 视为禁用。 */
    @Test
    void zeroTotalDisablesSystem() {
        String json = """
                { "chaff": { "launcher_parts": ["cm"], "total": 0 } }
                """;
        RVP_CountermeasureData data = RVP_CountermeasureData.parse(JsonParser.parseString(json));
        assertNull(data);
    }

    /** 缺省字段使用类默认值。 */
    @Test
    void defaultsApply() {
        String json = """
                { "flare": { "launcher_parts": ["cm"] } }
                """;
        RVP_CountermeasureData data = RVP_CountermeasureData.parse(JsonParser.parseString(json));
        assertNotNull(data);
        RVP_CountermeasureSystemData flare = data.getFlare();
        assertNotNull(flare);
        assertEquals(32, flare.getTotal());
        assertEquals(4, flare.getPerRound());
        assertEquals(8, flare.getBurstRounds());
        assertEquals(200, flare.getReloadTick());
    }

    /** 实际用法：传入整个载具 JSON（顶层 countermeasure 键），应正确提取子对象。 */
    @Test
    void parseVehicleJsonWithCountermeasureKey() {
        String json = """
                {
                  "type": "ywzj_vehicle:fixed_wing_vehicle",
                  "countermeasure": {
                    "flare": {
                      "launcher_parts": ["decoy_flare_barrel"],
                      "total": 32,
                      "per_round": 4,
                      "burst_rounds": 8,
                      "launch_interval_tick": 4,
                      "reload_tick": 200,
                      "decoy": { "glow_color": 16711680 }
                    },
                    "chaff": {
                      "launcher_parts": ["decoy_flare_barrel"],
                      "total": 32,
                      "per_round": 4,
                      "burst_rounds": 8,
                      "launch_interval_tick": 4,
                      "reload_tick": 200,
                      "radar_jam_radius": 8,
                      "radar_jam_count": 3,
                      "radar_jam_cooldown_tick": 60
                    }
                  },
                  "attributes": { "thrust": 0.025 }
                }
                """;
        RVP_CountermeasureData data = RVP_CountermeasureData.parse(JsonParser.parseString(json));
        assertNotNull(data);
        assertTrue(data.isEnabled());
        assertEquals("decoy_flare_barrel", data.getFlare().getLauncherParts().get(0));
        assertEquals(16711680, data.getFlare().getDecoy().getGlowColor());
        assertEquals(60, data.getChaff().getRadarJamCooldownTick());
    }
}
