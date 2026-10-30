package org.ywzj.rvp.client.light;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Minecraft 在运行时才解析程序接口；构建时验证清单和 GLSL 声明严格配对，避免无烟而无编译错误。 */
class RVP_RocketTrailResourcesTest {
    /** 本次已确认新增的程序资源根路径。 */
    private static final String ROOT = "/assets/ywzj_rvp/shaders/core/rocket_trail_volume";

    /** 调用真实打包资源，检查所有显卡参数的类型、分量数和采样器名称。 */
    @Test
    void shaderManifestMatchesEveryProductionUniformAndSampler() throws Exception {
        var manifest = JsonParser.parseString(resource(".json")).getAsJsonObject();
        assertEquals("ywzj_rvp:rocket_trail_volume", manifest.get("vertex").getAsString());
        assertEquals("ywzj_rvp:rocket_trail_volume", manifest.get("fragment").getAsString());
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
        assertEquals(2, manifest.getAsJsonArray("samplers").size(), "只读取两份场景深度，没有烟雾图片");
    }

    /** 读取实际 classpath 资源，缺失即失败，不绕过 Gradle 的资源打包。 */
    private static String resource(String extension) throws Exception {
        try (var input = RVP_RocketTrailResourcesTest.class.getResourceAsStream(ROOT + extension)) {
            assertNotNull(input, ROOT + extension);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
