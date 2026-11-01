package org.ywzj.rvp.client.light;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** 构建期校验喷焰程序清单与 GLSL 声明配对，避免新增 uniform 漏登记后静默失效。 */
class RVP_RocketPlumeResourcesTest {
    /** 喷焰程序资源根路径。 */
    private static final String ROOT = "/assets/ywzj_rvp/shaders/core/rocket_plume";

    /** 清单必须登记全部 GLSL 参数，否则到显卡阶段才丢接口且不报错。 */
    @Test
    void shaderManifestMatchesEveryProductionUniformAndSampler() throws Exception {
        var manifest = JsonParser.parseString(resource(".json")).getAsJsonObject();
        assertEquals("ywzj_rvp:rocket_plume", manifest.get("vertex").getAsString());
        assertEquals("ywzj_rvp:rocket_plume", manifest.get("fragment").getAsString());
        Map<String, String> declarations = new HashMap<>();
        var matcher = Pattern.compile("uniform\\s+(\\w+)\\s+(\\w+)\\s*;").matcher(resource(".fsh"));
        while (matcher.find()) { assertNull(declarations.put(matcher.group(2), matcher.group(1))); }
        var declaredNames = new HashSet<String>();
        for (var entry : manifest.getAsJsonArray("uniforms")) {
            var parameter = entry.getAsJsonObject();
            String name = parameter.get("name").getAsString();
            String type = declarations.get(name);
            assertNotNull(type, "清单多出了 GLSL 未声明的参数：" + name);
            assertTrue(declaredNames.add(name), "重复参数：" + name);
            int expectedCount = type.equals("mat4") ? 16 : type.startsWith("vec") ? Integer.parseInt(type.substring(3)) : 1;
            assertEquals(expectedCount, parameter.get("count").getAsInt(), name);
            assertEquals(expectedCount, parameter.getAsJsonArray("values").size(), name);
            assertEquals(type.equals("mat4") ? "matrix4x4" : type.equals("int") ? "int" : "float",
                    parameter.get("type").getAsString(), name);
        }
        for (var entry : manifest.getAsJsonArray("samplers")) {
            String name = entry.getAsJsonObject().get("name").getAsString();
            assertEquals("sampler2D", declarations.get(name), name);
            assertTrue(declaredNames.add(name));
        }
        assertEquals(declarations.keySet(), declaredNames, "遗漏参数会导致实际游戏中接口缺失");
        assertEquals(3, manifest.getAsJsonArray("samplers").size(), "只读取场景深度、DH 深度与可见度三份输入");
    }

    /** 光晕开关必须同时出现在 GLSL 与清单中，Java 端才能实际改写。 */
    @Test
    void haloSwitchIsDeclaredForRenderer() throws Exception {
        assertTrue(resource(".fsh").contains("uniform int HaloEnabled;"));
        assertTrue(resource(".json").contains("\"HaloEnabled\""));
    }

    /** 读取实际 classpath 资源，缺失即失败，不绕过 Gradle 的资源打包。 */
    private static String resource(String extension) throws Exception {
        try (var input = RVP_RocketPlumeResourcesTest.class.getResourceAsStream(ROOT + extension)) {
            assertNotNull(input, ROOT + extension);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
