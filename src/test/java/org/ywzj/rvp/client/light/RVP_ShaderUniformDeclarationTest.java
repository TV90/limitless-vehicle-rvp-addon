package org.ywzj.rvp.client.light;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验生产着色程序的 uniform 与描述 JSON 一一对应。
 *
 * <p>Minecraft 只按 JSON 的 {@code uniforms} 列表创建 Uniform 对象，不反射显卡程序里的活动 uniform。
 * 因此“GLSL 里声明了、JSON 里没写”的 uniform 会让 {@code safeGetUniform} 返回 null，
 * 随后链式的 {@code .set(...)} 抛出空指针，被合成器的兜底 catch 吞掉后整帧环境光（含天空散射）都不会绘制。
 * 这类问题显卡回归脚本（直接编译 GLSL 并手写 uniform）看不出来，必须在这里做静态校验。</p>
 */
class RVP_ShaderUniformDeclarationTest {
    /** 着色程序目录。 */
    private static final Path CORE = Path.of("src", "main", "resources", "assets", "ywzj_rvp", "shaders", "core");
    /** 公共包含目录，片段程序通过 moj_import 展开。 */
    private static final Path INCLUDE = Path.of("src", "main", "resources", "assets", "ywzj_rvp", "shaders", "include");
    /** 与 RVP 注册入口一一对应的生产程序；未注册的旧资源不参与本约束。 */
    private static final List<String> PRODUCTION = List.of("screen_light_occlusion", "screen_light_bloom_multiscale",
            "smoke_light_occlusion", "rocket_plume_ranges", "rocket_trail_volume", "nvg_glow");
    /** 片段程序中的单行 uniform 声明，包含数组形式。 */
    private static final Pattern DECLARATION =
            Pattern.compile("^\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*(?:\\[\\d+\\])?\\s*;", Pattern.MULTILINE);
    /** 公共代码引用。 */
    private static final Pattern IMPORT = Pattern.compile("#moj_import\\s+<ywzj_rvp:([^>]+)>");

    @Test
    void everyProductionUniformIsDeclaredInItsShaderDescription() throws IOException {
        for (String program : PRODUCTION) {
            // 调用真实描述 JSON，逐项比对；缺一项就会在游戏内让整帧光照被跳过。
            JsonObject description = JsonParser.parseString(Files.readString(CORE.resolve(program + ".json")))
                    .getAsJsonObject();
            Set<String> declaredValues = names(description, "uniforms");
            Set<String> declaredSamplers = names(description, "samplers");
            Set<String> shaderValues = new LinkedHashSet<>();
            Set<String> shaderSamplers = new LinkedHashSet<>();
            // 调用包含展开，公共遮挡核心里的 uniform 同样必须被声明。
            collect(expand(program), shaderValues, shaderSamplers);
            Set<String> missingValues = new LinkedHashSet<>(shaderValues);
            missingValues.removeAll(declaredValues);
            assertTrue(missingValues.isEmpty(), program + " 的描述 JSON 缺少 uniform：" + missingValues
                    + "；safeGetUniform 会返回 null，进而中断整帧光照");
            Set<String> missingSamplers = new LinkedHashSet<>(shaderSamplers);
            missingSamplers.removeAll(declaredSamplers);
            assertTrue(missingSamplers.isEmpty(), program + " 的描述 JSON 缺少 sampler：" + missingSamplers);
            // 反向检查：JSON 声明了程序里根本不存在的名字，说明描述已经过期或改名后没有同步。
            Set<String> stale = new LinkedHashSet<>(declaredValues);
            stale.removeAll(shaderValues);
            assertTrue(stale.isEmpty(), program + " 的描述 JSON 声明了程序里不存在的 uniform：" + stale);
        }
    }

    /** 读取顶点与片段程序并展开公共包含；公共文件缺失时直接失败，不能静默跳过约束。 */
    private static String expand(String program) throws IOException {
        // 调用顶点程序，位置变换类 uniform 只在顶点侧声明也必须参与校验。
        String source = Files.readString(CORE.resolve(program + ".vsh"))
                + '\n' + Files.readString(CORE.resolve(program + ".fsh"));
        Matcher matcher = IMPORT.matcher(source);
        StringBuilder included = new StringBuilder();
        while (matcher.find()) {
            String file = matcher.group(1);
            Path path = INCLUDE.resolve(file);
            assertTrue(Files.isRegularFile(path), program + " 引用的公共代码不存在：" + file);
            included.append('\n').append(Files.readString(path));
        }
        return source + included;
    }

    /** 按类型前缀分流普通 uniform 与采样器，数组形式只取基础名字。 */
    private static void collect(String source, Set<String> values, Set<String> samplers) {
        Matcher matcher = DECLARATION.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (type.contains("sampler")) { samplers.add(name); }
            else { values.add(name); }
        }
    }

    /** 读取 JSON 里某一类条目声明的名字。 */
    private static Set<String> names(JsonObject description, String key) {
        Set<String> result = new LinkedHashSet<>();
        if (!description.has(key)) { return result; }
        description.getAsJsonArray(key).forEach(entry -> result.add(entry.getAsJsonObject().get("name").getAsString()));
        return result;
    }
}
