package org.ywzj.rvp.client.light;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.ywzj.rvp.light.RVP_LightParameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 单灯、单遮挡条的生产程序诊断；只读磁盘 Shader，不创建或覆盖游戏资源。
 * 原型只在内存中替换 visibility；另行编译并校验正式修复程序，不能用原型输出冒充生产结果。
 */
public final class ScreenLightShadowDiagnostic {
    /** 横向分辨率，四像素宽的遮挡条可暴露跳采样造成的断影。 */
    private static final int WIDTH = 512;
    /** 纵向分辨率，场景为贯穿全高的竖条，只分析中间一行。 */
    private static final int HEIGHT = 64;
    /** 接收光照的平面距离，单位格。 */
    private static final float RECEIVER = 10F;
    /** 遮挡条正面距离，单位格。 */
    private static final float BLOCKER = 4F;
    /** 遮挡条左边界所在像素列，包含该列。 */
    private static final int STRIP_LEFT = 250;
    /** 遮挡条右边界所在像素列，不包含该列。 */
    private static final int STRIP_RIGHT = 254;
    /** 唯一光源的横坐标，单位格；投影仍在屏幕内。 */
    private static final float LIGHT_X = 1.5F;
    /** 唯一光源距相机的距离，单位格。 */
    private static final float LIGHT_DISTANCE = 2F;
    /** 标准透视投影的深度缩放，近面 0.1、远面 100。 */
    private static final float A = -100.1F / 99.9F;
    /** 标准透视投影的深度偏移。 */
    private static final float B = -20F / 99.9F;
    /** 本进程创建的纹理，仅在显存中释放。 */
    private static final List<Integer> TEXTURES = new ArrayList<>();
    /** 本进程创建的绘制目标，仅在显存中释放。 */
    private static final List<Integer> TARGETS = new ArrayList<>();
    /** 本进程编译的显卡程序。 */
    private static final List<Integer> PROGRAMS = new ArrayList<>();

    private ScreenLightShadowDiagnostic() { }

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) { throw new IllegalStateException("GLFW initialization failed"); }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "RVP single-light shadow diagnostic", 0, 0);
        if (window == 0) { throw new IllegalStateException("Hidden GL context unavailable"); }
        int vao = 0;
        int vbo = 0;
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GPU=" + GL11.glGetString(GL11.GL_RENDERER));
            Path shaders = Path.of(args[0]).resolve("src/main/resources/assets/ywzj_rvp/shaders/core");
            String vertex = Files.readString(shaders.resolve("screen_light_halo_timeline.vsh"));
            String fragment = Files.readString(shaders.resolve("screen_light_halo_timeline.fsh"));
            // 调用只读编译：第一个程序逐字使用当前生产 Shader，后续程序只改变内存字符串。
            int production = program(vertex, fragment);
            // 调用已接入游戏的另名生产程序；保留旧程序复现，避免把内存原型冒充正式修复。
            int fixed = program(vertex, ScreenLightShaderSources.read(shaders.resolve("screen_light_occlusion.fsh")));
            int dense = program(vertex, fragment.replace("step <= 8;", "step <= 64;")
                    .replace("float(step) / 9.0", "float(step) / 65.0"));
            // 调用函数级替换，分别验证连续遍历与有限厚度两个因素，不混入其它渲染改动。
            int continuous = program(vertex, replaceVisibility(fragment, traversal(false)));
            int bounded = program(vertex, replaceVisibility(fragment, traversal(true)));
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1,-1,0, 3,-1,0, -1,3,0}, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDepthMask(false);
            // 调用显存场景构造：同一个平面，只切换有无唯一遮挡条，深度使用生产同款最近点采样。
            int clear = depth(false);
            int blocked = depth(true);
            int color = texture(GL30.GL_RGBA16F, GL11.GL_RGBA, new float[WIDTH * HEIGHT * 4]);
            int output = GL30.glGenFramebuffers();
            TARGETS.add(output);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, output);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, color, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
                    "Output framebuffer incomplete");
            GL11.glViewport(0, 0, WIDTH, HEIGHT);
            // 调用几何参考：与生产的受光点法线偏移一致，但直接求线段与遮挡条平面的交点。
            boolean[] reference = reference();
            System.out.println("SCENE receiver=10, blocker=4, strip=[250,254), light=(1.5,0,-2), lights=1, bloom=off, emission=off");
            System.out.println("GEOMETRY expected=" + spans(reference));
            RVP_LightParameters[] presets = {
                    // 调用三类真实预设，只改变来源颜色/强度/半径；不增加灯数，也不绘制粒子。
                    RVP_LightParameters.explosion(4), RVP_LightParameters.muzzle(125, false), RVP_LightParameters.engine()
            };
            String[] names = {"explosion", "muzzle", "engine"};
            for (int index = 0; index < names.length; index++) {
                // 调用同场景有无遮挡对照，以比值识别阴影，排除自然半径衰减和预设亮度差异。
                boolean[] original = mask(production, presets[index], clear, blocked, color, output);
                boolean[] denser = mask(dense, presets[index], clear, blocked, color, output);
                boolean[] swept = mask(continuous, presets[index], clear, blocked, color, output);
                boolean[] finite = mask(bounded, presets[index], clear, blocked, color, output);
                boolean[] repaired = mask(fixed, presets[index], clear, blocked, color, output);
                System.out.println(names[index] + " production-8=" + spans(original)
                        + " dense-64=" + spans(denser));
                System.out.println(names[index] + " continuous-unbounded=" + spans(swept)
                        + " continuous-bounded=" + spans(finite));
                System.out.println(names[index] + " mismatchPixels production=" + mismatch(original, reference)
                        + " dense64=" + mismatch(denser, reference) + " unbounded=" + mismatch(swept, reference)
                        + " bounded=" + mismatch(finite, reference));
                System.out.println(names[index] + " FIXED production=" + spans(repaired)
                        + " mismatchPixels=" + mismatch(repaired, reference));
                // 调用形态和几何双重检查：多条阴影必须能被生产程序复现，原型必须只剩一个正确位置的阴影。
                require(countSpans(original) >= 3, names[index] + " did not reproduce repeated shadows");
                require(countSpans(finite) == 1 && mismatch(finite, reference) <= 2,
                        names[index] + " bounded prototype disagrees with geometric reference");
                require(countSpans(repaired) == 1 && mismatch(repaired, reference) <= 2,
                        names[index] + " repaired production disagrees with geometric reference");
            }
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error at completion");
            System.out.println("PASS old repeated shadows reproduced; repaired production shader matches geometry for all three presets");
        } finally {
            GL20.glUseProgram(0);
            for (int value : PROGRAMS) { GL20.glDeleteProgram(value); }
            for (int value : TARGETS) { GL30.glDeleteFramebuffers(value); }
            for (int value : TEXTURES) { GL11.glDeleteTextures(value); }
            if (vbo != 0) { GL15.glDeleteBuffers(vbo); }
            if (vao != 0) { GL30.glDeleteVertexArrays(vao); }
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    /** 只替换内存中的目标函数，磁盘生产文件始终只读。 */
    private static String replaceVisibility(String source, String replacement) {
        int start = source.indexOf("float visibility(vec3 surface, vec3 normal, vec3 light) {");
        require(start >= 0, "Production visibility signature changed; inspect diagnostic before reuse");
        int end = source.indexOf("\n}", start);
        require(end >= 0, "Production visibility body not found");
        return source.substring(0, start) + replacement + source.substring(end + 2);
    }

    /**
     * 显存原型：DDA 即逐个遍历投影线段穿过的像素格，避免相邻采样点跨过遮挡物。
     * 每个像素比较透视正确的射线深度区间；有界版只把表面后 0.01 格作为遮挡厚度。
     * 此处固定上限只适用于本诊断分辨率，未解决生产的性能、离屏或复杂实体厚度策略。
     */
    private static String traversal(boolean bounded) {
        return """
                float visibility(vec3 surface, vec3 normal, vec3 light) {
                    vec3 start = surface + normal * 0.08;
                    vec4 a = Projection * vec4(start, 1.0);
                    vec4 b = Projection * vec4(light, 1.0);
                    if (a.w <= 0.00001 || b.w <= 0.00001) { return 0.0; }
                    vec2 p = (a.xy / a.w * 0.5 + 0.5) * ScreenSize;
                    vec2 end = (b.xy / b.w * 0.5 + 0.5) * ScreenSize;
                    vec2 delta = end - p;
                    ivec2 cell = ivec2(floor(p));
                    ivec2 direction = ivec2(sign(delta));
                    vec2 stride = 1.0 / max(abs(delta), vec2(0.000001));
                    vec2 edge = vec2(cell) + vec2(delta.x > 0.0 ? 1.0 : 0.0, delta.y > 0.0 ? 1.0 : 0.0);
                    vec2 next = vec2(abs(delta.x) < 0.000001 ? 1e20 : (edge.x - p.x) / delta.x,
                                     abs(delta.y) < 0.000001 ? 1e20 : (edge.y - p.y) / delta.y);
                    vec3 qa = start / a.w;
                    vec3 qb = light / b.w;
                    float ka = 1.0 / a.w;
                    float kb = 1.0 / b.w;
                    float entry = 0.0;
                    for (int i = 0; i < 2048; ++i) {
                        if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(vec2(cell), ScreenSize))) { return 0.0; }
                        float exit = min(1.0, min(next.x, next.y));
                        float z0 = -(mix(qa, qb, entry) / mix(ka, kb, entry)).z;
                        float z1 = -(mix(qa, qb, exit) / mix(ka, kb, exit)).z;
                        vec4 blocker = scenePosition((vec2(cell) + 0.5) / ScreenSize);
                        // 区间而非离散点；有界版再要求射线仍在可见表面的有限厚度内。
                        if (blocker.w > 0.5 && max(z0, z1) >= -blocker.z + 0.001
                                BOUNDED_CONDITION) { return 0.0; }
                        if (exit >= 1.0) { return 1.0; }
                        bool crossX = next.x <= next.y;
                        bool crossY = next.y <= next.x;
                        if (crossX) { cell.x += direction.x; next.x += stride.x; }
                        if (crossY) { cell.y += direction.y; next.y += stride.y; }
                        entry = exit;
                    }
                    return 0.0;
                }
                """.replace("BOUNDED_CONDITION", bounded ? "&& min(z0, z1) <= -blocker.z + 0.01" : "");
    }

    /** 两次绘制只切换深度，不做 Bloom 或显示合成，保证暗区来自光路可见性。 */
    private static boolean[] mask(int program, RVP_LightParameters preset, int clear, int blocked, int color, int output) {
        GL20.glUseProgram(program);
        // 调用正式新程序的整数采样器空绑定，当前场景继续单独验证旧深度缺陷与改进。
        ScreenLightShaderSources.bindEmptyGeometry(program);
        float[] projection = {1,0,0,0, 0,1,0,0, 0,0,A,-1, 0,0,B,0};
        float[] inverse = {1,0,0,0, 0,1,0,0, 0,0,0,1/B, 0,0,-1,A/B};
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "Projection"), false, projection);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "InverseProjection"), false, inverse);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "ScreenSize"), WIDTH, HEIGHT);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "NearEmptyDepth"), 1);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "OutputMode"), 1);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "LightCount"), 1);
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "LightPositionRadius0"),
                LIGHT_X, 0, -LIGHT_DISTANCE, preset.radius());
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "LightColorIntensity0"),
                preset.red(), preset.green(), preset.blue(), preset.intensity());
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "LightShape0"), preset.surfaceWeight(), 0, 0, 0);
        // 调用有效纹理绑定：所有活动采样器独占纹理单元，输入绝不指向输出附件。
        bind(program, "SceneColor", 0, clear);
        bind(program, "DistantDepth", 2, clear);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, output);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, color, 0);
        bind(program, "SceneDepth", 1, clear);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        float[] baseline = new float[WIDTH * 4];
        GL11.glReadPixels(0, HEIGHT / 2, WIDTH, 1, GL11.GL_RGBA, GL11.GL_FLOAT, baseline);
        bind(program, "SceneDepth", 1, blocked);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        float[] actual = new float[WIDTH * 4];
        GL11.glReadPixels(0, HEIGHT / 2, WIDTH, 1, GL11.GL_RGBA, GL11.GL_FLOAT, actual);
        boolean[] result = new boolean[WIDTH];
        for (int x = 0; x < WIDTH; x++) {
            // 轮廓邻接像素的法线会改变，排除遮挡条本体两边各两个像素，只分析接收平面。
            if (x >= STRIP_LEFT - 2 && x < STRIP_RIGHT + 2) { continue; }
            require(Float.isFinite(actual[x * 4]), "Non-finite GPU output");
            result[x] = baseline[x * 4] > 0.01F && actual[x * 4] < baseline[x * 4] * 0.01F;
        }
        return result;
    }

    /** 几何参考：偏移后的受光点到灯的线段只与一张正对相机的有限宽平面求交。 */
    private static boolean[] reference() {
        boolean[] result = new boolean[WIDTH];
        double t = (RECEIVER - 0.08 - BLOCKER) / (RECEIVER - 0.08 - LIGHT_DISTANCE);
        double left = (2.0 * STRIP_LEFT / WIDTH - 1) * BLOCKER;
        double right = (2.0 * STRIP_RIGHT / WIDTH - 1) * BLOCKER;
        for (int x = 0; x < WIDTH; x++) {
            double surfaceX = ((x + 0.5) / WIDTH * 2 - 1) * RECEIVER;
            double at = surfaceX * (1 - t) + LIGHT_X * t;
            result[x] = at >= left && at < right;
        }
        return result;
    }

    /** 使用生产的最近点深度采样，避免双线性过滤生成不存在的遮挡表面。 */
    private static int depth(boolean blocked) {
        float[] values = new float[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                float distance = blocked && x >= STRIP_LEFT && x < STRIP_RIGHT ? BLOCKER : RECEIVER;
                values[y * WIDTH + x] = ((-A * distance + B) / distance) * 0.5F + 0.5F;
            }
        }
        // 调用显存纹理构造，不在磁盘生成任何资源文件。
        return texture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, values);
    }

    /** 创建显存纹理，所有输入与深度均用最近点采样。 */
    private static int texture(int internal, int format, float[] values) {
        int texture = GL11.glGenTextures();
        TEXTURES.add(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, WIDTH, HEIGHT, 0, format, GL11.GL_FLOAT, values);
        return texture;
    }

    /** 绑定完整输入；不改变磁盘资源或外部游戏状态。 */
    private static void bind(int program, String name, int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, name), unit);
    }

    /** 编译实际顶点程序和生产/内存对照片元程序，失败即停止。 */
    private static int program(String vertex, String fragment) {
        int program = GL20.glCreateProgram();
        PROGRAMS.add(program);
        // 调用统一编译，所有对照都复用生产顶点程序和生产合成路径。
        int vs = compile(GL20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, fragment);
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glLinkProgram(program);
        require(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0, GL20.glGetProgramInfoLog(program));
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return program;
    }

    /** 编译显卡程序，返回日志作为失败依据。 */
    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        require(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != 0, GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    /** 输出连续阴影的像素区间，便于直接看到同一遮挡条生成了几条影子。 */
    private static String spans(boolean[] values) {
        List<String> result = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= values.length; i++) {
            if (i < values.length && values[i]) { if (start < 0) { start = i; } }
            else if (start >= 0) { result.add(start + ".." + (i - 1)); start = -1; }
        }
        return result.toString();
    }

    /** 统计断开的影子数量，不把阴影宽度变化误报成修复成功。 */
    private static int countSpans(boolean[] values) {
        int count = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] && (i == 0 || !values[i - 1])) { count++; }
        }
        return count;
    }

    /** 与直接几何求交逐像素比较，同时计算漏影和错误暗区。 */
    private static int mismatch(boolean[] actual, boolean[] reference) {
        int count = 0;
        for (int i = 0; i < actual.length; i++) { if (actual[i] != reference[i]) { count++; } }
        return count;
    }

    /** 始终启用的诊断断言，不依赖 Java 的 -ea 开关。 */
    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
