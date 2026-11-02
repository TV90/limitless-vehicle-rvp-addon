package org.ywzj.rvp.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证多候选 schema、ID 去重、无效项隔离和默认候选回退。 */
class RVP_DeployableUavOptionsParserTest {
    @Test
    void rejectsNonFiniteCoordinatesLongIdsAndExtraOffsetValues() {
        JsonObject parent = new JsonObject();
        parent.addProperty("deployable_uav_enabled", true);
        JsonArray options = new JsonArray();
        JsonObject nonFinite = candidate("non_finite", "rvp:suav");
        JsonArray nanOffset = new JsonArray();
        nanOffset.add(new JsonPrimitive(Double.NaN)); nanOffset.add(new JsonPrimitive(0)); nanOffset.add(new JsonPrimitive(0));
        nonFinite.add("spawn_offset", nanOffset);
        options.add(nonFinite);
        JsonObject extraOffset = candidate("extra_offset", "rvp:other");
        JsonArray fourValues = new JsonArray();
        fourValues.add(0); fourValues.add(0); fourValues.add(0); fourValues.add(1);
        extraOffset.add("spawn_offset", fourValues);
        options.add(extraOffset);
        options.add(candidate("x".repeat(129), "rvp:third"));
        JsonObject valid = candidate("valid", "rvp:other");
        valid.addProperty("spawn_yaw_mode", "unsupported");
        options.add(valid);
        parent.add("deployable_uav_options", options);

        var parsed = RVP_DeployableUavOptionsParser.parse(parent);
        assertEquals(1, parsed.options().size());
        assertEquals("valid", parsed.defaultOptionId());
        assertEquals("parent", parsed.options().get(0).spawnYawMode());
    }

    @Test
    void nullSpeedKeepsBothStationaryRadarOptionsAndInheritedDeploymentPolicies() {
        // 对应 BUK-M3 与 IRIS-T SLM 当前候选：显式 null 表示地面雷达车不赋予初速度。
        for (String vehicle : new String[] {"rvp:96l6", "rvp:irist_slm_tads"}) {
            JsonObject parent = JsonParser.parseString("""
                    {"deployable_uav_enabled":true,
                     "deployable_uav_default_option":"radar",
                     "deployable_uav_allow_control_switch":true,
                     "deployable_uav_auto_link_datalink":true,
                     "deployable_uav_redeploy_cooldown_tick":1200,
                     "deployable_uav_options":[{"id":"radar","role":"radar_relay",
                       "spawn_offset":{"x":0,"y":0.5,"z":-12},
                       "spawn_yaw_mode":"parent","initial_speed":null}]}
                    """).getAsJsonObject();
            parent.getAsJsonArray("deployable_uav_options").get(0).getAsJsonObject().addProperty("vehicle_id", vehicle);
            var parsed = RVP_DeployableUavOptionsParser.parse(parent);
            assertEquals(1, parsed.options().size(), vehicle);
            var option = parsed.options().get(0);
            assertEquals("radar", parsed.defaultOptionId());
            assertEquals(vehicle, option.vehicleId().toString());
            assertEquals(0F, option.initialSpeed());
            assertEquals(0F, option.effectiveConfig().initialSpeed());
            assertTrue(option.effectiveConfig().isConfigured());
            assertEquals("radar_relay", option.role());
            assertTrue(option.effectiveConfig().autoLinkDatalink());
            assertEquals(1200, option.effectiveConfig().redeployCooldownTick());
        }
    }

    @Test
    void malformedSpeedStillRejectsCandidateInsteadOfSilentlyBecomingZero() {
        for (String malformed : new String[] {"true", "{}", "[]", "-1", "1e999"}) {
            JsonObject parent = new JsonObject();
            parent.addProperty("deployable_uav_enabled", true);
            JsonObject option = candidate("radar", "rvp:96l6");
            option.add("initial_speed", JsonParser.parseString(malformed));
            JsonArray options = new JsonArray(); options.add(option);
            parent.add("deployable_uav_options", options);
            assertTrue(RVP_DeployableUavOptionsParser.parse(parent).options().isEmpty(), malformed);
        }
    }

    private static JsonObject candidate(String id, String vehicleId) {
        JsonObject option = new JsonObject();
        option.addProperty("id", id);
        option.addProperty("vehicle_id", vehicleId);
        option.addProperty("role", "uav");
        JsonArray offset = new JsonArray(); offset.add(0); offset.add(0); offset.add(0);
        option.add("spawn_offset", offset);
        option.addProperty("spawn_yaw_mode", "parent");
        option.addProperty("initial_speed", 1.0);
        return option;
    }
    @Test
    void keepsDistinctValidOptionsAndFallsBackWhenDefaultIsMissing() {
        var parent = JsonParser.parseString("""
                {
                  "deployable_uav_enabled": true,
                  "deployable_uav_default_option": "missing",
                  "deployable_uav_options": [
                    {"id":"suav", "vehicle_id":"rvp:suav", "role":"uav",
                     "spawn_offset":[0, 100, 0], "spawn_yaw_mode":"parent", "initial_speed":1},
                    {"id":"suav", "vehicle_id":"rvp:another", "role":"uav",
                     "spawn_offset":[0, 0, 0], "spawn_yaw_mode":"parent", "initial_speed":1},
                    {"id":"invalid_vehicle", "vehicle_id":"bad id", "role":"uav",
                     "spawn_offset":[0, 0, 0], "spawn_yaw_mode":"parent", "initial_speed":1},
                    {"id":"radar", "vehicle_id":"rvp:96l6", "role":"radar_relay",
                     "spawn_offset":{"x":0,"y":0.5,"z":-10}, "spawn_yaw_mode":"parent", "initial_speed":0}
                  ]
                }
                """).getAsJsonObject();

        var parsed = RVP_DeployableUavOptionsParser.parse(parent);
        assertEquals(2, parsed.options().size());
        assertEquals("suav", parsed.defaultOptionId());
        assertEquals("rvp:suav", parsed.options().get(0).vehicleId().toString());
        assertEquals("rvp:96l6", parsed.options().get(1).vehicleId().toString());
        assertTrue(parsed.options().get(0).effectiveConfig().singleInstance());
        assertTrue(parsed.options().get(1).effectiveConfig().enabled());
        assertFalse(RVP_DeployableUavOptionsParser.parse(JsonParser.parseString(
                "{\"deployable_uav_enabled\":false,\"deployable_uav_options\":[]}").getAsJsonObject())
                .options().size() > 0);
    }
}