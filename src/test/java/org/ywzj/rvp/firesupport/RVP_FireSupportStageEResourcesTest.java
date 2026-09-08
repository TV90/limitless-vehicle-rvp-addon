package org.ywzj.rvp.firesupport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.firesupport.config.RVP_FireSupportProfileParser;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportResolvedWeapon;
import org.ywzj.rvp.firesupport.config.RVP_FireSupportWeaponBudget;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportProfile;
import org.ywzj.rvp.firesupport.schedule.RVP_FireSupportSchedulePlanner;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportMissionManager;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportSpawnChunkLeaseManager;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段 E 正式资源、双语文案与保守性能预算回归。 */
class RVP_FireSupportStageEResourcesTest {
    /** 文档中的可分发作者示例。 */ private static final Path EXAMPLE = Path.of(
            "docs/examples/fire_support_profiles/default.json");
    /** 载具包实际发布的默认炮火 profile。 */ private static final Path RUNTIME_PROFILE = Path.of(
            "run/client_1/limitless_vehicle/rvp/data/rvp/fire_support_profiles/default.json");
    /** 终端生成物品模型。 */ private static final Path ITEM_MODEL = Path.of(
            "src/main/resources/assets/ywzj_rvp/models/item/fire_support_terminal.json");
    /** 终端透明物品贴图。 */ private static final Path ITEM_TEXTURE = Path.of(
            "src/main/resources/assets/ywzj_rvp/textures/item/fire_support_terminal.png");

    @Test
    void authorExampleParsesAsCurrentSchemaAndUsesConfiguredMunitions() throws Exception {
        JsonObject json = JsonParser.parseString(Files.readString(EXAMPLE)).getAsJsonObject();
        ResourceLocation profileId = ResourceLocation.fromNamespaceAndPath("rvp", "default");
        ResourceLocation weaponId = ResourceLocation.fromNamespaceAndPath("rvp", "f14d_mk84");
        java.util.Set<ResourceLocation> configuredWeapons = java.util.Set.of(weaponId,
                ResourceLocation.fromNamespaceAndPath("rvp", "gb_1000"),
                ResourceLocation.fromNamespaceAndPath("rvp", "j10c_gb3_ir"),
                ResourceLocation.fromNamespaceAndPath("rvp", "m142_m30"),
                ResourceLocation.fromNamespaceAndPath("rvp", "m142_rocket"),
                ResourceLocation.fromNamespaceAndPath("rvp", "m142_m30_wp"),
                ResourceLocation.fromNamespaceAndPath("rvp", "f14d_maodie_yeshenggounai"),
                ResourceLocation.fromNamespaceAndPath("rvp", "f14a_iriaf_yasser"));
        RVP_FireSupportProfile profile = RVP_FireSupportProfileParser.parseAll(Map.of(profileId, json),
                id -> configuredWeapons.contains(id) ? resolved(id.getPath().contains("mk84")
                        || id.getPath().contains("maodie") || id.getPath().startsWith("gb_")
                        || id.getPath().contains("gb3") ? RVP_EnumWeaponKind.BOMB : RVP_EnumWeaponKind.ROCKET)
                        : null).get(profileId);

        assertEquals(2, profile.schemaVersion());
        assertEquals(weaponId, profile.munitions().get("mk84_he").weapons().get(0).weaponId());
        assertEquals(java.util.List.of(2, 1), profile.munitions().get("mk84_yasser_mixed").weapons().stream()
                .map(RVP_FireSupportProfile.MunitionWeapon::weight).toList());
        assertEquals(32, profile.limits().maxActiveMissionsGlobal());
        assertEquals(128, profile.limits().maxRoundsPerMission());
        // 作者示例刻意关闭单武器 MK-84 的试射，用于展示方案级 registration 开关。
        assertFalse(profile.munitions().get("mk84_he").registrationPhaseEnabled());
        assertEquals(3, profile.patterns().size());
        var monitor = RVP_FireSupportSchedulePlanner.plan(profile.callStage().baseDurationTicks(),
                profile.munitions().get("mk84_yasser_mixed"), profile.fireModes().get("monitor"),
                11L, profile.limits());
        assertTrue(monitor.rounds().size() == 14 || monitor.rounds().size() == 15);
        assertTrue(monitor.lastRoundFromAcceptanceTicks() <= profile.limits().maxMissionDurationTicks());
    }

    @Test
    void runtimeProfileKeepsSingleWeaponAndAddsWeightedMixedStrike() throws Exception {
        JsonObject json = JsonParser.parseString(Files.readString(RUNTIME_PROFILE)).getAsJsonObject();
        ResourceLocation profileId = ResourceLocation.fromNamespaceAndPath("rvp", "default");
        ResourceLocation mk84 = ResourceLocation.fromNamespaceAndPath("rvp", "f14d_mk84");
        ResourceLocation yasser = ResourceLocation.fromNamespaceAndPath("rvp", "f14a_iriaf_yasser");
        RVP_FireSupportProfile profile = RVP_FireSupportProfileParser.parseAll(Map.of(profileId, json),
                id -> id.getPath().contains("mk84") || id.getPath().contains("maodie")
                        ? resolved(RVP_EnumWeaponKind.BOMB) : resolved(RVP_EnumWeaponKind.ROCKET)).get(profileId);

        assertEquals(2, profile.schemaVersion());
        assertEquals(6, profile.munitions().size());
        assertEquals(java.util.List.of(mk84, yasser), profile.munitions().get("mk84_yasser_mixed")
                .weapons().stream().map(RVP_FireSupportProfile.MunitionWeapon::weaponId).toList());
        assertEquals(java.util.List.of(2, 1), profile.munitions().get("mk84_yasser_mixed")
                .weapons().stream().map(RVP_FireSupportProfile.MunitionWeapon::weight).toList());
        assertEquals(6, profile.munitions().get("m142_m30").roundsPerUnit());
        assertEquals(6, profile.munitions().get("m142_m30_wp").roundsPerUnit());
        assertEquals(6, profile.munitions().get("m142_m31").roundsPerUnit());
        assertEquals(4, profile.munitions().get("novelty_heavy_bomb").roundsPerUnit());
    }

    private static RVP_FireSupportResolvedWeapon resolved(RVP_EnumWeaponKind kind) {
        return new RVP_FireSupportResolvedWeapon(kind, RVP_EnumGuidanceType.NONE,
                false, false, false, false, false, 1200, 0, false, 1200.0F, 18.0F);
    }

    @Test
    void itemAssetsAndBilingualKeysAreComplete() throws Exception {
        JsonObject model = JsonParser.parseString(Files.readString(ITEM_MODEL)).getAsJsonObject();
        assertEquals("ywzj_rvp:item/fire_support_terminal",
                model.getAsJsonObject("textures").get("layer0").getAsString());
        BufferedImage texture = ImageIO.read(ITEM_TEXTURE.toFile());
        assertNotNull(texture);
        assertEquals(32, texture.getWidth());
        assertEquals(32, texture.getHeight());
        assertTrue(texture.getColorModel().hasAlpha());
        assertTrue(hasTransparentPixel(texture));

        JsonObject zh = language("zh_cn");
        JsonObject en = language("en_us");
        for (String key : zh.keySet()) {
            if (key.contains("fire_support") || key.contains("fire_support_terminal")) {
                assertTrue(en.has(key), "英文缺少键 " + key);
            }
        }
        for (String key : en.keySet()) {
            if (key.contains("fire_support") || key.contains("fire_support_terminal")) {
                assertTrue(zh.has(key), "中文缺少键 " + key);
            }
        }
        for (var munition : JsonParser.parseString(Files.readString(EXAMPLE)).getAsJsonObject()
                .getAsJsonArray("munitions")) {
            String key = munition.getAsJsonObject().get("translation_key").getAsString();
            assertTrue(zh.has(key), "中文缺少示例弹药方案键 " + key);
            assertTrue(en.has(key), "英文缺少示例弹药方案键 " + key);
        }
    }

    @Test
    void schemaIsDraft202012AndRejectsUnknownPropertiesByConstruction() throws Exception {
        JsonObject schema = JsonParser.parseString(Files.readString(Path.of(
                "docs/schemas/fire_support/fire_support_profile.schema.json"))).getAsJsonObject();
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema").getAsString());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertTrue(schema.getAsJsonObject("$defs").getAsJsonObject("munition")
                .get("additionalProperties").isJsonPrimitive());
        assertEquals(2, schema.getAsJsonObject("properties").getAsJsonObject("schema_version")
                .get("const").getAsInt());
        assertFalse(schema.getAsJsonObject("$defs").getAsJsonObject("munitionWeapon")
                .get("additionalProperties").getAsBoolean());
    }

    @Test
    void multiplayerAndLowTpsBacklogRemainInsideHardBudgets() {
        int globalMissions = 32;
        int worstRoundsPerMission = 11;
        long topLevelEntities = (long) globalMissions * worstRoundsPerMission;
        assertEquals(352L, topLevelEntities);
        assertTrue(RVP_FireSupportMissionManager.MAX_SPAWNS_PER_TICK <= 8);
        assertTrue(RVP_FireSupportSpawnChunkLeaseManager.MAX_NEW_TICKETS_PER_TICK <= 24);
        assertEquals(1200, RVP_FireSupportSpawnChunkLeaseManager.MAX_WAIT_TICKS);
        // 低 TPS 只延后游戏 Tick；任务管理器每次实际 Tick 每任务至多尝试一发，并受全局 8 发预算保护。
        assertEquals(44L, (topLevelEntities + RVP_FireSupportMissionManager.MAX_SPAWNS_PER_TICK - 1L)
                / RVP_FireSupportMissionManager.MAX_SPAWNS_PER_TICK);
        assertEquals(352L, RVP_FireSupportWeaponBudget.estimateExpandedEntities((int) topLevelEntities, 0));
    }

    private static JsonObject language(String language) throws Exception {
        return JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/ywzj_rvp/lang/" + language + ".json"))).getAsJsonObject();
    }

    private static boolean hasTransparentPixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) == 0) return true;
            }
        }
        return false;
    }
}
