package org.ywzj.rvp.client.light;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.ywzj.rvp.weapon.visual.RVP_RocketTrailParameters;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/** 隐藏显卡窗口中运行正式体积烟 GLSL，回读遮挡/透明度和四视图，不启动游戏或改写美术资产。 */
public final class RocketTrailVolumeGpuRegression {
    /** 数值验证分辨率；偶数尺寸也要能覆盖中心附近的细烟。 */
    private static final int SIZE = 64;
    /** 测试透视远面，单位格，包含 4096 格远程尾迹。 */
    private static final float FAR = 10000;
    /** 标准透视深度的 Z 系数。 */
    private static final float A = -(FAR + 0.1F) / (FAR - 0.1F);
    /** 标准透视深度的平移系数。 */
    private static final float B = -0.2F * FAR / (FAR - 0.1F);
    /** 原场景颜色和非一 Alpha，检验预乘混合。 */
    private static final float[] BACKGROUND = {0.1F, 0.12F, 0.16F, 0.37F};
    /** 本次验证自己的纹理编号，不包含任何外部纹理。 */
    private static final List<Integer> TEXTURES = new ArrayList<>();
    /** 本次验证自己的离屏目标编号。 */
    private static final List<Integer> TARGETS = new ArrayList<>();
    /** 全屏三角形顶点数组。 */
    private static int vao;
    /** 全屏三角形顶点缓冲。 */
    private static int vbo;
    /** 实际从生产资源编译的程序。 */
    private static int shader;
    /** 带 Minecraft 类路径的入口可追加实际 Java 烟段选择回归，独立 GLSL 入口仍可单独运行。 */
    static Runnable afterVolumeChecks;

    public static void main(String[] args) throws Exception {
        require(GLFW.glfwInit(), "GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(64, 64, "RVP volume trail regression", 0, 0);
        require(window != 0, "hidden OpenGL context");
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            System.out.println("GPU=" + GL11.glGetString(GL11.GL_RENDERER));
            Path root = Path.of(args[0]);
            Path resources = root.resolve("src/main/resources/assets/ywzj_rvp/shaders/core");
            int vertex = compile(GL20.GL_VERTEX_SHADER, Files.readString(resources.resolve("rocket_trail_volume.vsh")));
            int fragment = compile(GL20.GL_FRAGMENT_SHADER, Files.readString(resources.resolve("rocket_trail_volume.fsh")));
            shader = GL20.glCreateProgram();
            GL20.glAttachShader(shader, vertex); GL20.glAttachShader(shader, fragment);
            GL20.glBindAttribLocation(shader, 0, "Position"); GL20.glLinkProgram(shader);
            require(GL20.glGetProgrami(shader, GL20.GL_LINK_STATUS) != 0, GL20.glGetProgramInfoLog(shader));
            GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment);
            vao = GL30.glGenVertexArrays(); vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1,-1,0, 3,-1,0, -1,3,0}, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false); GL11.glDisable(GL11.GL_CULL_FACE);
            // 调用正式程序的像素回读，不以测试内的简化绘图逻辑替代体积烟算法。
            tests();
            if (afterVolumeChecks != null) { afterVolumeChecks.run(); }
            preview(root);
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error at completion");
            System.out.println("PASS all production rocket trail volume GPU regressions; OpenGL error=0");
        } finally {
            GL20.glUseProgram(0);
            for (int target : TARGETS) { GL30.glDeleteFramebuffers(target); }
            for (int texture : TEXTURES) { GL11.glDeleteTextures(texture); }
            if (shader != 0) { GL20.glDeleteProgram(shader); }
            if (vbo != 0) { GL15.glDeleteBuffers(vbo); }
            if (vao != 0) { GL30.glDeleteVertexArrays(vao); }
            GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
        }
    }

    /** 检查真实像素的体积、前景裁剪、DH 独立矩阵、反深度、相机进入烟内和正确混合。 */
    private static void tests() {
        int output = target(SIZE, SIZE);
        int sky = depth(SIZE, 0, false);
        base(SIZE, SIZE, sky);
        clear(output, SIZE, SIZE, new float[4]); draw();
        float[] full = pixel(output, 32, 32);
        require(full[3] > 0.05F && full[3] < 1, "continuous smoke opacity");
        require(full[0] > 0 && full[0] <= full[3], "premultiplied non-emissive RGB");
        zero(pixel(output, 2, 2), "empty pixels outside volume");
        System.out.println("PASS continuous procedural smoke without image sampler; center alpha=" + full[3]);

        uniform2("TrailDensity", 0, 0); clear(output, SIZE, SIZE, BACKGROUND); draw();
        near(BACKGROUND, pixel(output, 32, 32), "zero density keeps background");
        base(SIZE, SIZE, depth(SIZE, 4, false)); clear(output, SIZE, SIZE, new float[4]); draw();
        zero(pixel(output, 32, 32), "foreground terrain/entity blocks smoke");
        base(SIZE, SIZE, depth(SIZE, 8, false)); clear(output, SIZE, SIZE, new float[4]); draw();
        float clipped = pixel(output, 32, 32)[3];
        require(clipped > 0 && clipped < full[3], "wall crossing the smoke clips only hidden depth");
        System.out.println("PASS foreground and intersecting opaque-depth clipping");

        for (boolean reverse : new boolean[] {false, true}) {
            base(SIZE, SIZE, sky);
            uniform1i("HasDistantDepth", 1);
            uniform1("DistantEmptyDepth", reverse ? 0 : 1);
            matrix("DistantProjection", projection(reverse, 0.8F));
            matrix("InverseDistantProjection", inverse(reverse, 0.8F));
            sampler("DistantDepth", 1, depth(32, 4, reverse));
            clear(output, SIZE, SIZE, new float[4]); draw();
            zero(pixel(output, 32, 32), "DH independent size/FOV/reverse depth foreground");
        }
        base(SIZE, SIZE, depth(SIZE, 4, true));
        matrix("Projection", projection(true, 1)); matrix("InverseProjection", inverse(true, 1));
        uniform1("NearEmptyDepth", 0);
        clear(output, SIZE, SIZE, new float[4]); draw(); zero(pixel(output, 32, 32), "reverse near depth");
        base(SIZE, SIZE, sky);
        sampler("SceneDepth", 0, flatDepth(SIZE, 0.01F)); uniform1("HandDepthLimit", 0.05F);
        clear(output, SIZE, SIZE, new float[4]); draw(); zero(pixel(output, 32, 32), "shader hand mask");
        System.out.println("PASS near/DH matrix pairing, half-size DH, reverse depth and hand mask");

        base(SIZE, SIZE, sky);
        uniform4("TrailStart", 0, 0, -1, 1.5F); uniform4("TrailEnd", 0, 0, 1, 1.5F);
        clear(output, SIZE, SIZE, new float[4]); draw();
        require(pixel(output, 32, 32)[3] > 0.05F, "camera inside smoke");
        base(SIZE, SIZE, sky);
        uniform4("TrailStart", 0, 0, -4, 0.8F); uniform4("TrailEnd", 0, 0, -36, 0.8F);
        clear(output, SIZE, SIZE, new float[4]); draw();
        require(pixel(output, 32, 32)[3] > 0.8F, "axially parallel smoke ray");
        System.out.println("PASS camera-inside volume and axially parallel ray intervals");

        // 调用正式体积程序，比较同一个球形冲刷烟在旧浓度和当前代码浓度下的真实透明度。
        base(SIZE, SIZE, sky);
        uniform4("TrailStart", 0, 0, -8, 1.2F); uniform4("TrailEnd", 0, 0, -8, 1.2F);
        uniform2("TrailDensity", 0.65F, 0.65F);
        clear(output, SIZE, SIZE, new float[4]); draw();
        float previousWashAlpha = pixel(output, 32, 32)[3];
        uniform2("TrailDensity", RVP_RocketTrailParameters.WASH_DENSITY, RVP_RocketTrailParameters.WASH_DENSITY);
        clear(output, SIZE, SIZE, new float[4]); draw();
        float currentWashAlpha = pixel(output, 32, 32)[3];
        require(currentWashAlpha > previousWashAlpha + 0.10F && currentWashAlpha < 1,
                "ground-wash opacity must increase without forcing a solid surface");
        System.out.println("PASS denser ground wash: alpha=" + previousWashAlpha + " -> " + currentWashAlpha);

        base(SIZE, SIZE, sky);
        uniform1i("LightCount", 1);
        uniform4("LightPositionRadius0", -1, 0, -7, 20); uniform4("LightColorIntensity0", 1, 0.18F, 0.02F, 12);
        clear(output, SIZE, SIZE, new float[4]); draw();
        float[] warm = pixel(output, 32, 32);
        require(warm[0] > full[0] + 0.05F && warm[0] > warm[2], "engine light warms actual volume");
        System.out.println("PASS warm engine illumination on the smoke volume");

        base(SIZE, SIZE, sky);
        clear(output, SIZE, SIZE, BACKGROUND); premultipliedBlend(); draw();
        float[] mixed = pixel(output, 32, 32);
        for (int channel = 0; channel < 3; channel++) {
            near(full[channel] + BACKGROUND[channel] * (1 - full[3]), mixed[channel], "smoke absorption and scattering");
        }
        near(BACKGROUND[3], mixed[3], "background alpha preservation");
        GL11.glDisable(GL11.GL_BLEND);
        clear(output, SIZE, SIZE, new float[4]); draw();
        near(full, pixel(output, 32, 32), "unchanged time is deterministic");
        // 调用相同生产程序，以绕观察轴旋转 180 度后的对应像素检查世界噪声不会粘在屏幕上。
        matrix("ViewToWorld", new float[] {-1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,0,0,1});
        uniform4("TrailStart", 2, 0, -8, 0.8F); uniform4("TrailEnd", -2, 0, -8, 0.8F);
        clear(output, SIZE, SIZE, new float[4]); draw();
        near(full, pixel(output, 31, 31), "world anchored noise after camera rotation");
        System.out.println("PASS premultiplied blending, preserved alpha, paused time and world-anchored noise");

        base(SIZE, SIZE, sky);
        uniform4("TrailStart", -256, 0, -4096, 0.8F); uniform4("TrailEnd", 256, 0, -4096, 0.8F);
        clear(output, SIZE, SIZE, new float[4]); draw();
        require(pixel(output, 32, 32)[3] > 0.00001F, "4096-block subpixel smoke coverage");
        sampler("SceneDepth", 0, depth(SIZE, 4000, false));
        clear(output, SIZE, SIZE, new float[4]); draw();
        zero(pixel(output, 32, 32), "subpixel filtering must not bring smoke in front of a physical wall");
        System.out.println("PASS 4096-block coverage without subpixel depth leaks");
    }

    /** 生成生产程序的四个视图供人工核对，PNG 仅写入 build 诊断目录，不作为游戏资源。 */
    private static void preview(Path root) throws Exception {
        int width = 640;
        int height = 260;
        int output = target(width, height);
        int sky = flatDepth(SIZE, 1);
        BufferedImage sheet = new BufferedImage(width * 2, (height + 36) * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = sheet.createGraphics();
        graphics.setColor(new Color(20, 25, 35)); graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        graphics.setFont(new Font("Microsoft YaHei", Font.PLAIN, 17));
        String[] labels = {"连续尾烟 · 无烟雾贴图", "弯曲路径 · 旧烟留在世界中", "贴地烟浪 · 独立体积烟团", "发动机暖光 · 烟自身位置受光"};
        for (int panel = 0; panel < 4; panel++) {
            base(width, height, sky);
            matrix("Projection", perspective(width / (float) height));
            matrix("InverseProjection", inversePerspective(width / (float) height));
            clear(output, width, height, new float[] {0.22F, 0.34F, 0.50F, 1});
            premultipliedBlend();
            if (panel == 0 || panel == 3) {
                uniform4("TrailStart", -16, 0, -16, 1.2F); uniform4("TrailEnd", 16, 0, -16, 0.6F);
                uniform4("TrailColor", 0.80F, 80, 0.3F, 0.7F);
                if (panel == 3) {
                    uniform1i("LightCount", 1);
                    uniform4("LightPositionRadius0", -8, 1, -14, 20);
                    uniform4("LightColorIntensity0", 1, 0.18F, 0.015F, 12);
                }
                draw();
            } else if (panel == 1) {
                for (int index = 5; index >= 0; index--) {
                    float x = -15 + index * 5;
                    float y = index < 3 ? -2 : (index - 3) * 2 - 2;
                    float nextY = index < 2 ? -2 : (index - 2) * 2 - 2;
                    uniform4("TrailStart", x, y, -17, 1.3F - index * 0.12F);
                    uniform4("TrailEnd", x + 5, nextY, -17, 1.18F - index * 0.12F);
                    uniform3("TrailNoiseOrigin", x * 0.7F, y * 0.7F, -17 * 0.7F);
                    uniform4("TrailColor", 0.75F, 90 - index * 10, 0.3F, 0.7F); draw();
                }
            } else {
                uniform2("TrailDensity", RVP_RocketTrailParameters.WASH_DENSITY, RVP_RocketTrailParameters.WASH_DENSITY);
                for (int index = 0; index < 7; index++) {
                    float x = (index - 3) * 3;
                    uniform4("TrailStart", x, -2 + (index % 2), -17, 1.7F);
                    uniform4("TrailEnd", x, -2 + (index % 2), -17, 1.7F);
                    uniform3("TrailNoiseOrigin", x * 0.7F, 0, -17 * 0.7F);
                    uniform4("TrailColor", 0.68F, 55 + index * 5, 0.3F, 0.7F); draw();
                }
            }
            float[] pixels = new float[width * height * 4];
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, output);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
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
            int x = panel % 2 * width;
            int y = panel / 2 * (height + 36);
            graphics.drawImage(image, x, y + 36, null);
            graphics.setColor(Color.WHITE); graphics.drawString(labels[panel], x + 14, y + 25);
        }
        graphics.dispose();
        Path outputPath = root.resolve("build/rocket-trail-volume-gpu-regression/preview.png");
        Files.createDirectories(outputPath.getParent()); ImageIO.write(sheet, "PNG", outputPath.toFile());
        System.out.println("PASS production GLSL four-view preview=" + outputPath);
    }

    /** 每项验证显式重新设置输入，不能继承上一项的 DH/光源/手持物状态。 */
    static void base(int width, int height, int nearDepth) {
        GL20.glUseProgram(shader); GL11.glDisable(GL11.GL_BLEND);
        matrix("Projection", projection(false, 1)); matrix("InverseProjection", inverse(false, 1));
        matrix("DistantProjection", projection(false, 1)); matrix("InverseDistantProjection", inverse(false, 1));
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        matrix("DistantToView", identity); matrix("ViewToDistant", identity); matrix("ViewToWorld", identity);
        uniform1i("HasDistantDepth", 0); uniform1("NearEmptyDepth", 1); uniform1("DistantEmptyDepth", 1);
        uniform1("HandDepthLimit", 0); uniform2("ScreenSize", width, height);
        uniform1i("RaySteps", 24); uniform1i("LightCount", 0);
        uniform4("TrailStart", -2, 0, -8, 0.8F); uniform4("TrailEnd", 2, 0, -8, 0.8F);
        uniform2("TrailDensity", 1, 1); uniform4("TrailColor", 0.65F, 20, 0.3F, 0.7F);
        uniform3("TrailNoiseOrigin", -1.4F, 0, -5.6F);
        sampler("SceneDepth", 0, nearDepth); sampler("DistantDepth", 1, nearDepth);
    }

    /** 视野和深度方向可分别变化的实际透视矩阵。 */
    private static float[] projection(boolean reverse, float scale) {
        return new float[] {scale,0,0,0, 0,scale,0,0, 0,0,reverse ? -A : A,-1, 0,0,reverse ? -B : B,0};
    }
    /** 与上面透视矩阵严格配对的逆矩阵。 */
    private static float[] inverse(boolean reverse, float scale) {
        float a = reverse ? -A : A;
        float b = reverse ? -B : B;
        return new float[] {1/scale,0,0,0, 0,1/scale,0,0, 0,0,0,1/b, 0,0,-1,a/b};
    }
    /** 预览的宽屏透视，避免把烟拉伸成宽扁面片。 */
    private static float[] perspective(float aspect) { float[] result = projection(false, 1); result[0] /= aspect; return result; }
    /** 宽屏透视的配对逆矩阵。 */
    private static float[] inversePerspective(float aspect) { float[] result = inverse(false, 1); result[0] *= aspect; return result; }
    /** 真实深度纹理；零距离表示天空，支持正向/反向两种约定。 */
    static int depth(int size, float distance, boolean reverse) {
        float value = distance == 0 ? 1 : ((-A * distance + B) / distance) * 0.5F + 0.5F;
        return flatDepth(size, reverse ? 1 - value : value);
    }
    /** 只生成显存深度数据，不创建任何磁盘贴图资源。 */
    private static int flatDepth(int size, float value) {
        float[] values = new float[size * size]; Arrays.fill(values, value);
        return texture(size, size, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, values);
    }
    /** 创建独立的 RGBA16F 输出目标。 */
    static int target(int width, int height) {
        int texture = texture(width, height, GL30.GL_RGBA16F, GL11.GL_RGBA, new float[width * height * 4]);
        int target = GL30.glGenFramebuffers(); TARGETS.add(target);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE, "test framebuffer");
        return target;
    }
    /** 分配本验证自有的颜色/深度纹理。 */
    private static int texture(int width, int height, int internal, int format, float[] data) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = GL11.glGenTextures(); TEXTURES.add(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL11.GL_FLOAT, data);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous); return texture;
    }
    /** 正式预乘混合的数值验证；生产状态入口另外由 ScreenLightFrameGpuRegression 验证。 */
    static void premultipliedBlend() {
        GL11.glEnable(GL11.GL_BLEND); GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
    }
    /** 清空本验证目标，保留输入纹理不变。 */
    static void clear(int target, int width, int height, float[] color) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target); GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(color[0], color[1], color[2], color[3]); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }
    /** 用已绑定的正式生产程序绘制。 */
    static void draw() { GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3); }
    /** 读取真实浮点像素，并拒绝 NaN/无穷输出。 */
    static float[] pixel(int target, int x, int y) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target);
        float[] result = new float[4]; GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, result);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL readback error");
        for (float value : result) { require(Float.isFinite(value), "non-finite volume pixel"); }
        return result;
    }
    /** 编译失败立即终止，不能把没有输出的坏程序当成安全降级。 */
    private static int compile(int type, String source) {
        int program = GL20.glCreateShader(type); GL20.glShaderSource(program, source); GL20.glCompileShader(program);
        require(GL20.glGetShaderi(program, GL20.GL_COMPILE_STATUS) != 0, GL20.glGetShaderInfoLog(program)); return program;
    }
    /** 绑定本验证的场景深度输入。 */
    private static void sampler(String name, int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture); uniform1i(name, unit);
    }
    /** 上传与生产接口同名的矩阵。 */
    private static void matrix(String name, float[] values) { GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(shader, name), false, values); }
    /** 上传整数参数。 */
    static void uniform1i(String name, int value) { GL20.glUniform1i(GL20.glGetUniformLocation(shader, name), value); }
    /** 上传单个浮点参数。 */
    private static void uniform1(String name, float value) { GL20.glUniform1f(GL20.glGetUniformLocation(shader, name), value); }
    /** 上传二分量参数。 */
    static void uniform2(String name, float x, float y) { GL20.glUniform2f(GL20.glGetUniformLocation(shader, name), x, y); }
    /** 上传三分量参数。 */
    static void uniform3(String name, float x, float y, float z) { GL20.glUniform3f(GL20.glGetUniformLocation(shader, name), x, y, z); }
    /** 上传四分量参数。 */
    static void uniform4(String name, float x, float y, float z, float w) { GL20.glUniform4f(GL20.glGetUniformLocation(shader, name), x, y, z, w); }
    /** 检查无烟或全遮挡时确实不写入颜色。 */
    private static void zero(float[] value, String message) { near(new float[4], value, message); }
    /** 检查四通道误差。 */
    private static void near(float[] expected, float[] actual, String message) {
        for (int index = 0; index < expected.length; index++) { near(expected[index], actual[index], message); }
    }
    /** RGBA16F 的有限量化误差。 */
    private static void near(float expected, float actual, String message) {
        require(Math.abs(expected - actual) < 0.002F, message + ": expected=" + expected + ", actual=" + actual);
    }
    /** 检查失败立即中断整个回归，不能跳过后继续宣称成功。 */
    private static void require(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
