package org.ywzj.rvp.entity.gunner.ai.profile;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.gunner.behavior.runtime.RVP_GunnerBehaviorRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段 F schema v2、注册表和载具包迁移结果测试。 */
class RVP_GunnerProfileCompilerTest {

    @Test
    void compilesBehaviorOrderPriorityAndMovementCapability() {
        GunnerProfile profile = compile("static_test", """
                {
                  "schema_version": 2,
                  "name": "static_test",
                  "faction": "friendly",
                  "behaviors": [
                    {"id":"target","type":"rvp:primary_targeting","priority":410,
                     "config":{"target_types":["vehicle"],"search_radius":200,"scan_interval_tick":8}},
                    {"id":"fire","type":"rvp:weapon_engagement","priority":420,"config":{}}
                  ]
                }
                """);

        assertEquals(java.util.List.of("target", "fire"), profile.getBehaviorPlan().behaviors().stream()
                .map(org.ywzj.rvp.entity.gunner.behavior.api.RVP_IGunnerBehavior::id).toList());
        assertEquals(410, profile.getBehaviorPlan().behavior("target").priority());
        assertFalse(profile.isAllowDrive());
    }

    @Test
    void acceptsRepeatedTypeButRejectsRepeatedInstanceIdAndUnknownFields() {
        GunnerProfile repeatedType = compile("repeat", """
                {"schema_version":2,"name":"repeat","faction":"team","behaviors":[
                  {"id":"radar_a","type":"rvp:ownship_radar","priority":500,"config":{}},
                  {"id":"radar_b","type":"rvp:ownship_radar","priority":510,"config":{}}
                ]}
                """);
        assertEquals(2, repeatedType.getBehaviorPlan().behaviors().size());

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> compile("bad", """
                {"schema_version":2,"behaviors":[
                  {"id":"same","type":"rvp:ownship_radar","config":{}},
                  {"id":"same","type":"rvp:external_radar","config":{}}
                ]}
                """));
        assertTrue(duplicate.getMessage().contains("behaviors[1].id"));

        IllegalArgumentException oldField = assertThrows(IllegalArgumentException.class, () -> compile("old", """
                {"schema_version":2,"name":"old","allow_drive":true,"behaviors":[]}
                """));
        assertTrue(oldField.getMessage().contains("allow_drive: 未知字段"));
    }

    @Test
    void rejectsUnknownTypeConfigKeyRangeAndOldSchema() {
        assertThrows(IllegalArgumentException.class, () -> compile("unknown", """
                {"schema_version":2,"behaviors":[{"id":"x","type":"rvp:not_registered","config":{}}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> compile("key", """
                {"schema_version":2,"behaviors":[{"id":"x","type":"rvp:ownship_radar","config":{"legacy":1}}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> compile("range", """
                {"schema_version":2,"behaviors":[{"id":"x","type":"rvp:weapon_engagement","priority":1001,"config":{}}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> compile("old", """
                {"name":"old","faction":"friendly","target_types":["vehicle"]}
                """));
    }

    @Test
    void everyVehiclePackProfileIsCurrentSchemaAndCompiles() throws IOException {
        Path root = Path.of("limitless_vehicle/rvp/data/rvp/gunner");
        java.util.List<Path> files;
        try (var stream = Files.list(root)) {
            files = stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertEquals(10, files.size());
        for (Path file : files) {
            JsonElement json = JsonParser.parseString(Files.readString(file));
            assertEquals(2, json.getAsJsonObject().get("schema_version").getAsInt(), file.toString());
            GunnerProfile profile = RVP_GunnerProfileCompiler.compile(
                    ResourceLocation.fromNamespaceAndPath("rvp", file.getFileName().toString().replace(".json", "")), json);
            assertFalse(profile.getBehaviorPlan().behaviors().isEmpty(), file.toString());
        }
        assertFalse(compileFile(root.resolve("air.json")).isAllowDrive());
        assertTrue(compileFile(root.resolve("default.json")).isAllowDrive());
    }

    @Test
    void registryIdsAreUniqueAndMigrationScriptDoesNotTargetRunDirectories() throws IOException {
        var ids = RVP_GunnerBehaviorRegistry.INSTANCE.types().keySet();
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertEquals(18, ids.size());
        String script = Files.readString(Path.of("scripts/migrate_gunner_profiles_v2.py"));
        assertTrue(script.contains("limitless_vehicle/rvp/data/rvp/gunner"));
        assertFalse(script.contains("run/client_1"));
        assertFalse(script.contains("run/client_2"));
        assertFalse(script.contains("run/server"));
    }

    @Test
    void snapshotCompilationIsAllOrNothingAndRequiresDefaultProfile() {
        JsonElement valid = JsonParser.parseString("""
                {"schema_version":2,"name":"default","faction":"friendly","behaviors":[]}
                """);
        JsonElement invalid = JsonParser.parseString("""
                {"schema_version":1,"name":"bad","behaviors":[]}
                """);
        var candidates = new LinkedHashMap<ResourceLocation, JsonElement>();
        candidates.put(GunnerProfileManager.DEFAULT_PROFILE_ID, valid);
        candidates.put(ResourceLocation.fromNamespaceAndPath("ywzj_rvp", "bad"), invalid);
        assertThrows(IllegalArgumentException.class, () -> GunnerProfileManager.compileSnapshot(candidates));
        assertThrows(IllegalArgumentException.class, () -> GunnerProfileManager.compileSnapshot(java.util.Map.of(
                ResourceLocation.fromNamespaceAndPath("ywzj_rvp", "other"), valid)));
        assertEquals(1, GunnerProfileManager.compileSnapshot(java.util.Map.of(
                GunnerProfileManager.DEFAULT_PROFILE_ID, valid)).size());
    }

    private static GunnerProfile compile(String id, String json) {
        return RVP_GunnerProfileCompiler.compile(ResourceLocation.fromNamespaceAndPath("rvp", id), JsonParser.parseString(json));
    }

    private static GunnerProfile compileFile(Path path) throws IOException {
        return compile(path.getFileName().toString().replace(".json", ""), Files.readString(path));
    }
}
