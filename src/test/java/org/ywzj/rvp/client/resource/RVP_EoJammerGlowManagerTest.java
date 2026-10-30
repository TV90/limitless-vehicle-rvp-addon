package org.ywzj.rvp.client.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验 display JSON 的 {@code eo_jammer_glow} 解析规则，以及两辆干扰载具的实际配置接线。 */
class RVP_EoJammerGlowManagerTest {

    /** 只解析合法条目：空骨名与非对象元素被丢弃，缺少 module_bone 时退化为例外条件不联动。 */
    @Test
    void parseRulesKeepsOnlyUsableEntries() {
        JsonObject root = json("""
                {
                  "eo_jammer_glow": [
                    { "glass_bone": "jammer_1", "module_bone": "ERA1" },
                    { "glass_bone": " jammer_2 " },
                    { "glass_bone": "" },
                    { "module_bone": "ERA3" },
                    "bad"
                  ]
                }
                """);
        List<RVP_EoJammerGlowManager.Rule> rules = RVP_EoJammerGlowManager.parseRules(root);
        assertEquals(2, rules.size());
        assertEquals("jammer_1", rules.get(0).glassBone());
        assertEquals("ERA1", rules.get(0).moduleBone());
        assertEquals("jammer_2", rules.get(1).glassBone());
        assertNull(rules.get(1).moduleBone(), "省略 module_bone 表示不检查干扰模块");
    }

    /** 缺少字段或类型错误时返回空表，渲染热路径据此直接跳过。 */
    @Test
    void parseRulesRejectsMissingOrWronglyTypedField() {
        assertTrue(RVP_EoJammerGlowManager.parseRules(null).isEmpty());
        assertTrue(RVP_EoJammerGlowManager.parseRules(json("{}")).isEmpty());
        assertTrue(RVP_EoJammerGlowManager.parseRules(json("{\"eo_jammer_glow\": {}}")).isEmpty());
        assertTrue(RVP_EoJammerGlowManager.parseRules(json("{\"eo_jammer_glow\": []}")).isEmpty());
    }

    /** 载具包实际配置：玻片骨必须存在于烘焙模型，联动骨必须在 bone_modules 里挂了 jammer 模块。 */
    @Test
    void packRulesPointToExistingBonesAndJammerModules() throws Exception {
        assertPackWiring("t84bm", List.of("ERA1", "ERA3"));
        assertPackWiring("m1a1hc", List.of("jammer"));
    }

    private static void assertPackWiring(String vehicle, List<String> expectedModuleBones) throws Exception {
        Path display = Path.of("limitless_vehicle/rvp/assets/rvp/display/vehicle", vehicle + ".json");
        JsonObject displayRoot = json(Files.readString(display, StandardCharsets.UTF_8));
        List<RVP_EoJammerGlowManager.Rule> rules = RVP_EoJammerGlowManager.parseRules(displayRoot);
        assertEquals(expectedModuleBones.size(), rules.size(), vehicle + " 玻片规则数量");

        Set<String> modelBones = collectBones(json(Files.readString(
                Path.of("limitless_vehicle/rvp/assets/rvp/models/bedrock/entity", vehicle + ".json"),
                StandardCharsets.UTF_8)));
        JsonObject boneModules = json(Files.readString(
                Path.of("limitless_vehicle/rvp/data/rvp/vehicles", vehicle + ".json"),
                StandardCharsets.UTF_8)).getAsJsonObject("bone_modules");
        assertNotNull(boneModules, vehicle + " 缺少 bone_modules");

        for (int index = 0; index < rules.size(); index++) {
            RVP_EoJammerGlowManager.Rule rule = rules.get(index);
            assertTrue(modelBones.contains(rule.glassBone()), vehicle + " 模型缺少玻片骨 " + rule.glassBone());
            assertEquals(expectedModuleBones.get(index), rule.moduleBone(), vehicle + " 联动骨名");
            JsonObject module = boneModules.getAsJsonObject(rule.moduleBone());
            assertNotNull(module, vehicle + " bone_modules 缺少 " + rule.moduleBone());
            boolean hasJammer = false;
            for (JsonElement type : module.getAsJsonArray("modules")) {
                hasJammer |= "jammer".equals(type.getAsString());
            }
            assertTrue(hasJammer, vehicle + " 的 " + rule.moduleBone() + " 未挂 jammer 模块");
        }
    }

    /** 模型骨骼分散在多层 geometry 下，递归收集任意 "bones" 数组。 */
    private static Set<String> collectBones(JsonElement element) {
        Set<String> bones = new HashSet<>();
        collectBones(element, bones);
        return bones;
    }

    private static void collectBones(JsonElement element, Set<String> bones) {
        if (element == null) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectBones(child, bones);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement list = object.get("bones");
        if (list != null && list.isJsonArray()) {
            for (JsonElement bone : list.getAsJsonArray()) {
                if (bone.isJsonObject() && bone.getAsJsonObject().has("name")) {
                    bones.add(bone.getAsJsonObject().get("name").getAsString());
                }
            }
        }
        for (JsonElement child : object.asMap().values()) {
            collectBones(child, bones);
        }
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
