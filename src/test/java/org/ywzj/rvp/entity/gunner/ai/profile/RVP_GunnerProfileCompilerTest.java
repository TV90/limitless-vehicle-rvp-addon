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
        candidates.put(new ResourceLocation("ywzj_rvp", "bad"), invalid);
        assertThrows(IllegalArgumentException.class, () -> GunnerProfileManager.compileSnapshot(candidates));
        assertThrows(IllegalArgumentException.class, () -> GunnerProfileManager.compileSnapshot(java.util.Map.of(
                new ResourceLocation("ywzj_rvp", "other"), valid)));
        assertEquals(1, GunnerProfileManager.compileSnapshot(java.util.Map.of(
                GunnerProfileManager.DEFAULT_PROFILE_ID, valid)).size());
    }

    @Test
    void targetDroneCompilesWithoutAnyCountermeasureBehavior() throws IOException {
        var directory = Path.of("limitless_vehicle/rvp/data/rvp/gunner");
        var targetPath = directory.resolve("target_drone.json");
        // 靶机配置随载具包交付，纯源码检出时没有这份集成测试数据。
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(targetPath));
        var targetDrone = compileFile(targetPath);
        var enemy = compileFile(directory.resolve("enemy.json"));
        var defenseIds = java.util.Set.of("base_defense", "rvp_defense", "ecm", "smoke_cover", "sead");
        // 保留敌对阵营、机动和常规交战，移除所有会提交反制意图的行为。
        assertEquals(RVP_EnumGunnerFaction.ENEMY, targetDrone.getFaction());
        assertEquals(enemy.getBehaviorPlan().behaviors().stream().map(behavior -> behavior.id())
                        .filter(id -> !defenseIds.contains(id)).toList(),
                targetDrone.getBehaviorPlan().behaviors().stream().map(behavior -> behavior.id()).toList());
    }

    private static GunnerProfile compile(String id, String json) {
        return RVP_GunnerProfileCompiler.compile(new ResourceLocation("rvp", id), JsonParser.parseString(json));
    }

    private static GunnerProfile compileFile(Path path) throws IOException {
        return compile(path.getFileName().toString().replace(".json", ""), Files.readString(path));
    }
}
