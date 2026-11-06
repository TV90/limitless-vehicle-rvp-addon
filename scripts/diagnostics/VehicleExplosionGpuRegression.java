package org.ywzj.rvp.client.light;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionSettings;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionSettings.Parameter;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/** 隐藏显卡窗口编译正式大爆炸资源并回读像素；不启动游戏、不使用或改写载具资产。 */
public final class VehicleExplosionGpuRegression {
    /** 数值回归的实际像素尺寸。 */
    private static final int SIZE = 96;
    /** 透视矩阵近远面对应的Z系数。 */
    private static final float A = -(1000F + 0.1F) / (1000F - 0.1F);
    /** 透视矩阵近远面对应的平移系数。 */
    private static final float B = -200F / (1000F - 0.1F);
    /** 非透明场景颜色；Alpha故意不等于1，验证合成保留它。 */
    private static final float[] BACKGROUND = {0.14F, 0.18F, 0.24F, 0.37F};
    /** 当前诊断自己的纹理，退出时全部回收。 */
    private static final List<Integer> TEXTURES = new ArrayList<>();
    /** 当前诊断自己的帧缓冲，退出时全部回收。 */
    private static final List<Integer> TARGETS = new ArrayList<>();
    /** 正式GLSL编译出的显卡程序。 */
    private static int program;
    /** 屏幕三角形的顶点数组。 */
    private static int vao;
    /** 屏幕三角形的顶点缓冲。 */
    private static int vbo;
    /** 数值回归输出目标。 */
    private static int output;
    /** 数值回归场景背景。 */
    private static int background;
    /** 数值回归天空深度。 */
    private static int sky;
    /** 当前只读背景纹理，先复制到输出，确保丢弃片元处也保留真实场景。 */
    private static int sceneColor;
    /** 用于复制背景的自有只读帧缓冲。 */
    private static int sceneReadTarget;
    /** 需要真实已编译程序的附加诊断；普通爆炸回归不安装回调。 */
    public static Runnable afterProductionChecks;

    public static void main(String[] args) throws Exception {
        require(GLFW.glfwInit(), "GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(64, 64, "RVP vehicle explosion regression", 0, 0);
        require(window != 0, "hidden OpenGL context");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            System.out.println("GPU=" + GL11.glGetString(GL11.GL_RENDERER));
            Path root = Path.of(args[0]);
            Path shaders = root.resolve("src/main/resources/assets/ywzj_rvp/shaders/core");
            int vertex = compile(GL20.GL_VERTEX_SHADER, Files.readString(shaders.resolve("vehicle_explosion.vsh")));
            int fragment = compile(GL20.GL_FRAGMENT_SHADER, Files.readString(shaders.resolve("vehicle_explosion.fsh")));
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, fragment);
            GL20.glBindAttribLocation(program, 0, "Position"); GL20.glLinkProgram(program);
            require(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0, GL20.glGetProgramInfoLog(program));
            GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment); GL20.glUseProgram(program);
            vao = GL30.glGenVertexArrays(); vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1,-1,0, 3,-1,0, -1,3,0}, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_BLEND);
            output = target(SIZE, SIZE); background = color(SIZE, SIZE, false); sky = depth(0, false);
            sceneReadTarget = GL30.glGenFramebuffers(); TARGETS.add(sceneReadTarget);
            // 调用正式程序回归和阶段预览，禁止用测试简化火球代替生产算法。
            tests();
            // 调用可选性能计时回归，复用当前正式程序和隐藏GL上下文，不另外模拟火球。
            if (afterProductionChecks != null) { afterProductionChecks.run(); }
            preview(root);
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error at completion");
            System.out.println("PASS all production vehicle explosion GPU checks; OpenGL error=0");
        } finally {
            GL20.glUseProgram(0);
            for (int id : TARGETS) { GL30.glDeleteFramebuffers(id); }
            for (int id : TEXTURES) { GL11.glDeleteTextures(id); }
            if (program != 0) { GL20.glDeleteProgram(program); }
            if (vbo != 0) { GL15.glDeleteBuffers(vbo); }
            if (vao != 0) { GL30.glDeleteVertexArrays(vao); }
            GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
        }
    }

    /** 验证透视体积、近远景遮挡、反深度、手持物、冷却黑烟、热浪及确定性。 */
    private static void tests() {
        base(SIZE, SIZE, 0.28F);
        float[] fire = render(output, SIZE, SIZE);
        float[] center = pixel(fire, SIZE / 2, SIZE / 2);
        require(center[0] > 0.4F && center[0] > center[2], "bright warm fireball");
        near(BACKGROUND, pixel(fire, 1, 1), "outside volume unchanged");
        near(BACKGROUND[3], center[3], "scene alpha preserved");
        float[] again = render(output, SIZE, SIZE);
        near(fire, again, "fixed seed and age reproduce same pixels");
        System.out.println("PASS fireball, outside pixels, alpha and fixed-seed replay");

        sampler("SceneDepth", 1, depth(3, false));
        near(BACKGROUND, pixel(render(output, SIZE, SIZE), 48, 48), "foreground near wall blocks explosion");
        base(SIZE, SIZE, 0.28F);
        sampler("DistantDepth", 2, depth(3, true));
        matrix("DistantProjection", projection(true, 1.4F, 1));
        matrix("InverseDistantProjection", inverse(true, 1.4F, 1));
        i("HasDistantDepth", 1); f("DistantEmptyDepth", 0);
        near(BACKGROUND, pixel(render(output, SIZE, SIZE), 48, 48), "DH wall with different reversed projection blocks explosion");
        base(SIZE, SIZE, 0.28F);
        matrix("Projection", projection(true, 1, 1)); matrix("InverseProjection", inverse(true, 1, 1));
        sampler("SceneDepth", 1, depth(0, true)); f("NearEmptyDepth", 0);
        near(fire, render(output, SIZE, SIZE), "reversed near depth reproduces fire");
        sampler("SceneDepth", 1, depth(3, true));
        near(BACKGROUND, pixel(render(output, SIZE, SIZE), 48, 48), "reversed near wall blocks explosion");
        base(SIZE, SIZE, 0.28F);
        sampler("SceneDepth", 1, texture(SIZE, SIZE, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, filled(SIZE * SIZE, 0.3F)));
        f("HandDepthLimit", 0.5625F);
        near(BACKGROUND, pixel(render(output, SIZE, SIZE), 48, 48), "shader hand mask remains untouched");
        System.out.println("PASS near terrain, DH independent/reversed depth and hand mask");

        base(SIZE, SIZE, 3);
        float[] smoke = render(output, SIZE, SIZE);
        require(pixel(smoke, 48, 48)[0] < BACKGROUND[0], "cooled smoke absorbs background");
        v2("Smoke", 0, 0.075F); v4("Air", 0, 0.18F, 0, 0.8F);
        near(BACKGROUND, pixel(render(output, SIZE, SIZE), 48, 48), "zero smoke at cooled stage preserves background");
        base(SIZE, SIZE, 6);
        near(BACKGROUND, pixel(render(output, SIZE, SIZE), 48, 48), "expired effect writes nothing");
        base(SIZE, SIZE, 0.28F); v4("ExplosionCenter", 0, 0, -0.1F, 12);
        render(output, SIZE, SIZE);
        i("RaySteps", 16); render(output, SIZE, SIZE);
        i("RaySteps", 64); render(output, SIZE, SIZE);
        System.out.println("PASS smoke cooling, zero density, lifetime endpoint and camera inside at 16/64 steps");

        int checker = color(SIZE, SIZE, true);
        base(SIZE, SIZE, 0.35F); sampler("SceneColor", 0, checker);
        v4("Air", 0, 0.18F, 0, 0.8F);
        float[] unwarped = render(output, SIZE, SIZE);
        v4("Air", 0, 0.18F, 12, 0.8F);
        float[] warped = render(output, SIZE, SIZE);
        require(difference(unwarped, warped) > 0.1, "heat refraction visibly moves checker background");
        v4("Motion", 1.1F, 0.65F, 44, 0.52F);
        require(difference(warped, render(output, SIZE, SIZE)) > 0.1, "changing seed changes shape");
        System.out.println("PASS heat refraction and shape seed have real pixel effects");
    }

    /** 输出正式程序的六阶段接触表，供检查爆发、翻卷、冷却和余烟。 */
    private static void preview(Path root) throws Exception {
        int width = 420, height = 300;
        int scene = color(width, height, true);
        int target = target(width, height);
        float[] ages = {0.06F, 0.28F, 0.8F, 1.4F, 2.8F, 4.8F};
        BufferedImage sheet = new BufferedImage(width * 3, (height + 34) * 2, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        graphics.setFont(new Font("Microsoft YaHei", Font.PLAIN, 16));
        for (int panel = 0; panel < ages.length; panel++) {
            base(width, height, ages[panel]); sampler("SceneColor", 0, scene);
            v4("ExplosionCenter", 0, ages[panel] * 0.85F - 1.3F, -10, 12);
            float[] pixels = render(target, width, height);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int at = ((height - y - 1) * width + x) * 4;
                    int red = Math.round(Math.max(0, Math.min(1, pixels[at])) * 255);
                    int green = Math.round(Math.max(0, Math.min(1, pixels[at + 1])) * 255);
                    int blue = Math.round(Math.max(0, Math.min(1, pixels[at + 2])) * 255);
                    image.setRGB(x, y, (red << 16) | (green << 8) | blue);
                }
            }
            int x = panel % 3 * width, y = panel / 3 * (height + 34);
            graphics.setColor(new Color(15, 20, 29)); graphics.fillRect(x, y, width, height + 34);
            graphics.setColor(Color.WHITE); graphics.drawString("程序大爆炸  /  " + ages[panel] + " 秒", x + 14, y + 23);
            graphics.drawImage(image, x, y + 34, null);
        }
        graphics.dispose();
        Path path = root.resolve("build/vehicle-explosion-gpu-regression/preview.png");
        Files.createDirectories(path.getParent()); ImageIO.write(sheet, "PNG", path.toFile());
        System.out.println("PASS production shader stage preview=" + path);
    }

    /** 从唯一业务参数表上传起点，测试和F8不会各维护一份默认配色。 */
    private static void base(int width, int height, float age) {
        GL20.glUseProgram(program); GL11.glDisable(GL11.GL_BLEND);
        float aspect = (float) width / height;
        matrix("Projection", projection(false, 1, aspect)); matrix("InverseProjection", inverse(false, 1, aspect));
        matrix("DistantProjection", projection(false, 1, aspect)); matrix("InverseDistantProjection", inverse(false, 1, aspect));
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        matrix("ViewToWorld", identity); matrix("DistantToView", identity); matrix("ViewToDistant", identity);
        i("HasDistantDepth", 0); f("NearEmptyDepth", 1); f("DistantEmptyDepth", 1); f("HandDepthLimit", 0);
        sampler("SceneColor", 0, background); sampler("SceneDepth", 1, sky); sampler("DistantDepth", 2, sky);
        v2("ScreenSize", width, height); v4("ExplosionCenter", 0, 0, -12, 12);
        var s = RVP_VehicleExplosionSettings.snapshot();
        v4("Timeline", age, s.get(Parameter.DURATION), s.get(Parameter.EXPANSION), s.get(Parameter.FIRE_DURATION));
        v4("Shape", s.get(Parameter.RADIUS), s.get(Parameter.STRETCH), s.get(Parameter.BILLOW), s.get(Parameter.NOISE_SCALE));
        v4("Motion", s.get(Parameter.TURBULENCE), s.get(Parameter.SMOKE_EXPANSION), s.get(Parameter.SEED), s.get(Parameter.CORE));
        GL20.glUniform3f(location("FireColor"), s.get(Parameter.BRIGHTNESS), s.get(Parameter.FIRE_GREEN), s.get(Parameter.FIRE_BLUE));
        v2("Smoke", s.get(Parameter.SMOKE_DENSITY), s.get(Parameter.SMOKE_GREY));
        v4("Air", s.get(Parameter.FLASH), s.get(Parameter.FLASH_DURATION), s.get(Parameter.DISTORTION), s.get(Parameter.WAVE_DURATION));
        i("RaySteps", (int) s.get(Parameter.RAY_STEPS));
    }

    /** 真正绘制并读取所有像素；所有像素都必须有限。 */
    private static float[] render(int target, int width, int height) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneReadTarget);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sceneColor, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target); GL11.glViewport(0, 0, width, height);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        float[] pixels = new float[width * height * 4];
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        for (float value : pixels) { require(Float.isFinite(value), "finite fragment output"); }
        return pixels;
    }

    /** 数值回归的像素读取；预览使用自己的宽高，不调用此方法。 */
    private static float[] pixel(float[] image, int x, int y) { return Arrays.copyOfRange(image, (y * SIZE + x) * 4, (y * SIZE + x) * 4 + 4); }
    /** 两张画面的绝对差，检验热浪和种子确实改变实际像素。 */
    private static double difference(float[] a, float[] b) {
        double total = 0;
        for (int at = 0; at < a.length; at++) { total += Math.abs(a[at] - b[at]); }
        return total;
    }
    /** 生成有限深度；距离0表示天空，纹理尺寸与输出不必相同。 */
    private static int depth(float distance, boolean reverse) {
        float value = distance == 0 ? 1 : (-A + B / distance) * 0.5F + 0.5F;
        return texture(SIZE, SIZE, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, filled(SIZE * SIZE, reverse ? 1 - value : value));
    }
    /** 纯程序测试背景；格纹用于辨认空气折射，非模组美术素材。 */
    private static int color(int width, int height, boolean grid) {
        float[] pixels = new float[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = (y * width + x) * 4;
                float line = grid && (x % 24 < 2 || y % 24 < 2) ? 0.12F : 0;
                pixels[at] = BACKGROUND[0] + line; pixels[at + 1] = BACKGROUND[1] + line;
                pixels[at + 2] = BACKGROUND[2] + line; pixels[at + 3] = BACKGROUND[3];
            }
        }
        return texture(width, height, GL30.GL_RGBA16F, GL11.GL_RGBA, pixels);
    }
    /** 分配回读目标；不挂接外部深度或颜色。 */
    private static int target(int width, int height) {
        int texture = texture(width, height, GL30.GL_RGBA16F, GL11.GL_RGBA, new float[width * height * 4]);
        int target = GL30.glGenFramebuffers(); TARGETS.add(target);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE, "test framebuffer complete");
        return target;
    }
    /** 创建独立测试纹理，并恢复当前绑定，避免测试自身污染采样器。 */
    private static int texture(int width, int height, int internal, int format, float[] pixels) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int id = GL11.glGenTextures(); TEXTURES.add(id); GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL11.GL_FLOAT, pixels);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous); return id;
    }
    /** 支持独立宽高比和反深度的透视矩阵。 */
    private static float[] projection(boolean reverse, float scale, float aspect) {
        return new float[] {scale/aspect,0,0,0, 0,scale,0,0, 0,0,reverse ? -A : A,-1, 0,0,reverse ? -B : B,0};
    }
    /** 与透视输入严格配对的逆矩阵。 */
    private static float[] inverse(boolean reverse, float scale, float aspect) {
        float a = reverse ? -A : A, b = reverse ? -B : B;
        return new float[] {aspect/scale,0,0,0, 0,1/scale,0,0, 0,0,0,1/b, 0,0,-1,a/b};
    }
    /** 构造固定深度或颜色测试输入。 */
    private static float[] filled(int length, float value) { float[] result = new float[length]; Arrays.fill(result, value); return result; }
    /** 编译错误直接失败，不能把没有效果误当作正确遮挡。 */
    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type); GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        require(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != 0, GL20.glGetShaderInfoLog(shader)); return shader;
    }
    /** 接口缺失必须报错，防止所有传值悄悄无效。 */
    private static int location(String name) { int value = GL20.glGetUniformLocation(program, name); require(value >= 0, "active uniform " + name); return value; }
    /** 绑定自有颜色或深度输入。 */
    private static void sampler(String name, int unit, int texture) {
        if (name.equals("SceneColor")) { sceneColor = texture; }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture); i(name, unit);
    }
    /** 上传矩阵。 */
    private static void matrix(String name, float[] values) { GL20.glUniformMatrix4fv(location(name), false, values); }
    /** 上传整数或开关。 */
    private static void i(String name, int value) { GL20.glUniform1i(location(name), value); }
    /** 上传单个浮点值。 */
    private static void f(String name, float value) { GL20.glUniform1f(location(name), value); }
    /** 上传两个浮点值。 */
    private static void v2(String name, float x, float y) { GL20.glUniform2f(location(name), x, y); }
    /** 上传四个浮点值。 */
    private static void v4(String name, float x, float y, float z, float w) { GL20.glUniform4f(location(name), x, y, z, w); }
    /** 半精度目标的像素容差。 */
    private static void near(float expected, float actual, String message) { require(Math.abs(expected - actual) < 0.002, message + ": " + expected + " != " + actual); }
    /** 检查整张图或四通道像素。 */
    private static void near(float[] expected, float[] actual, String message) { require(expected.length == actual.length, message); for (int at = 0; at < expected.length; at++) { near(expected[at], actual[at], message); } }
    /** 任一失败立即终止，不能继续输出成功总结。 */
    private static void require(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
