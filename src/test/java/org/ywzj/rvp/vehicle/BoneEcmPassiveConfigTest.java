package org.ywzj.rvp.vehicle;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.vehicle.BoneEcmPassiveConfig.Band;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link BoneEcmPassiveConfig} 配置解析与距离分档逻辑测试。
 */
class BoneEcmPassiveConfigTest {

    @Test
    void parsesFullJsonConfig() {
        JsonElement element = JsonParser.parseString("""
                {
                  "active_duration_ticks": 200,
                  "cooldown_ticks": 400,
                  "burn_through_distance": 300.0,
                  "nctr_names": ["F15", "J10"],
                  "decoy_speed_min": 0.5,
                  "decoy_speed_max": 1.0,
                  "bands": [
                    { "max_distance": 2000.0, "decoy_count": 8, "radius": 400.0 },
                    { "max_distance": 600.0, "decoy_count": 3, "radius": 120.0 }
                  ]
                }
                """);
        BoneEcmPassiveConfig cfg = BoneEcmPassiveConfig.parse(element);
        assertNotNull(cfg);
        assertEquals(200, cfg.activeDurationTicks());
        assertEquals(400, cfg.cooldownTicks());
        assertEquals(300.0, cfg.burnThroughDistance());
        assertEquals(2, cfg.nctrNames().size());
        assertEquals(0.5, cfg.decoySpeedMin());
        assertEquals(1.0, cfg.decoySpeedMax());
        assertEquals(2, cfg.bands().size());
        assertEquals(8, cfg.bands().get(0).decoyCount());
    }

    @Test
    void parseMissingFallsBackToDefaults() {
        BoneEcmPassiveConfig cfg = BoneEcmPassiveConfig.parse(JsonParser.parseString("{}"));
        assertNotNull(cfg);
        // 默认激活/充能/烧穿
        assertEquals(140, cfg.activeDurationTicks());
        assertEquals(300, cfg.cooldownTicks());
        assertEquals(250.0, cfg.burnThroughDistance());
        // 默认 NCTR 池（9 个机型）
        assertEquals(9, cfg.nctrNames().size());
        // 默认分档（4 档）
        assertEquals(4, cfg.bands().size());
    }

    @Test
    void resolveBandSelectsFirstMatchingDescending() {
        BoneEcmPassiveConfig cfg = BoneEcmPassiveConfig.parse(JsonParser.parseString("""
                {"bands":[
                  { "max_distance": 1500.0, "decoy_count": 6, "radius": 300.0 },
                  { "max_distance": 1000.0, "decoy_count": 4, "radius": 200.0 },
                  { "max_distance": 500.0,  "decoy_count": 3, "radius": 150.0 },
                  { "max_distance": 250.0,  "decoy_count": 2, "radius": 100.0 }
                ]}
                """));
        assertNotNull(cfg);
        // 远：> 1000 且 ≤1500 → 第一档
        Band far = cfg.resolveBand(1400.0);
        assertEquals(6, far.decoyCount());
        // 中远：≤1000 → 第二档
        Band midFar = cfg.resolveBand(1000.0);
        assertEquals(4, midFar.decoyCount());
        // 中：≤500 → 第三档
        Band mid = cfg.resolveBand(500.0);
        assertEquals(3, mid.decoyCount());
        // 近：≤250 → 第四档
        Band near = cfg.resolveBand(250.0);
        assertEquals(2, near.decoyCount());
    }

    @Test
    void resolveBandBeyondFarthestFallsBackToLastBand() {
        BoneEcmPassiveConfig cfg = BoneEcmPassiveConfig.parse(JsonParser.parseString(
                "{\"bands\":[{ \"max_distance\": 1500.0, \"decoy_count\": 6, \"radius\": 300.0 }]}"));
        assertNotNull(cfg);
        Band band = cfg.resolveBand(9999.0);
        assertNotNull(band);
        assertEquals(6, band.decoyCount());
    }

    @Test
    void parseNullOrEmptyBandsYieldsDefaultBands() {
        assertNull(BoneEcmPassiveConfig.parse(null));
        BoneEcmPassiveConfig cfg = BoneEcmPassiveConfig.parse(JsonParser.parseString("{\"bands\":[]}"));
        assertNotNull(cfg);
        // bands 空 → 落到默认分档
        assertEquals(4, cfg.bands().size());
    }
}
