package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry;

/** 用真实生产作用域及 ShaderInstance 检查 DH、光照与冲击波之间的状态交接，不启动游戏世界。 */
public final class ScreenEffectStateGpuRegression {
    /** 测试画面的边长，单位像素。 */
    private static final int SIZE = 16;
    /** 不受光的蓝色天空及非零透明度；错误混合会把它减成黑色。 */
    private static final float[] SKY = {0.125F, 0.25F, 0.5F, 0.5F};

    public static void main(String[] args) throws Exception {
        require(GLFW.glfwInit(), "GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "RVP screen effect state regression", 0, 0);
        require(window != 0, "hidden OpenGL context");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities(); RenderSystem.initRenderThread();
            System.out.println("GPU=" + GL11.glGetString(GL11.GL_RENDERER));
            int failures = 0;
            for (String test : List.of("DH", "lighting", "pressure", "pressure pixels")) {
                try {
                    switch (test) {
                        // 调用真实 DH 作用域，检查恢复后的下一次引擎请求。
                        case "DH" -> testDhCacheRestoration();
                        // 调用真实 Shader 和帧资源，检查缓存失配下的像素与深度。
                        case "lighting" -> testLightingDraw(Path.of(args[0]));
                        // 调用真实冲击波作用域，检查混合方程与引擎缓存。
                        case "pressure" -> testPressureWaveState();
                        // 调用完整生产冲击波，对抗残留减法混合与外部独立采样器。
                        case "pressure pixels" -> testPressureWaveDraw(Path.of(args[0]));
                    }
                } catch (Throwable failure) {
                    failures++;
                    System.out.println("FAIL " + test + ": " + failure);
                    failure.printStackTrace(System.out);
                }
            }
            require(failures == 0, failures + " screen effect state regressions failed");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error at completion");
            System.out.println("PASS all screen effect state regressions; OpenGL error=0");
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }

    /** 精确重放现有 DH 作用域中的引擎调用与原生恢复，随后再次申请相同状态。 */
    private static void testDhCacheRestoration() throws Exception {
        int previousTexture = texture(false);
        int insideTexture = texture(false);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0); RenderSystem.bindTexture(previousTexture);
        RenderSystem.activeTexture(GL13.GL_TEXTURE2);
        RenderSystem.depthMask(true); RenderSystem.enableDepthTest(); RenderSystem.enableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        try (AutoCloseable ignored = scope("org.ywzj.rvp.client.compat.distanthorizons.RVP_DhGlStateScope")) {
            RenderSystem.depthMask(false); RenderSystem.disableDepthTest(); RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.activeTexture(GL13.GL_TEXTURE1); RenderSystem.bindTexture(insideTexture);
        }
        require(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), "DH scope did not restore actual depth mask");
        // 引擎必须真的执行关闭；旧实现只恢复原生 GL，缓存仍是 false，从而跳过这次请求。
        RenderSystem.depthMask(false); RenderSystem.disableDepthTest(); RenderSystem.disableCull();
        RenderSystem.disableBlend();
        require(!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), "DH scope left stale depth-write cache");
        require(!GL11.glIsEnabled(GL11.GL_DEPTH_TEST), "DH scope left stale depth-test cache");
        require(!GL11.glIsEnabled(GL11.GL_CULL_FACE), "DH scope left stale cull cache");
        require(!GL11.glIsEnabled(GL11.GL_BLEND), "DH scope left stale blend cache");
        RenderSystem.activeTexture(GL13.GL_TEXTURE1); RenderSystem.bindTexture(insideTexture);
        require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE1, "DH scope left stale active-texture cache");
        require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == insideTexture, "DH scope left stale texture binding cache");
        RenderSystem.deleteTexture(previousTexture); RenderSystem.deleteTexture(insideTexture);
        System.out.println("PASS production DH scope restores actual state and subsequent engine requests");
    }

    /** 借用引擎原 Shader 加载器，只读取已存在资源；不生成、改写任何 Shader 或贴图。 */
    private static void testLightingDraw(Path root) throws Exception {
        int color = texture(false); int depth = texture(true);
        int world = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, world);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, SIZE, SIZE);
        RVP_ScreenFrameResources frame = new RVP_ScreenFrameResources();
        try (ShaderInstance shader = new ShaderInstance(resources(root),
                ResourceLocation.fromNamespaceAndPath("ywzj_rvp", "screen_light"), DefaultVertexFormat.POSITION)) {
            // 调用生产捕获与合成入口，零盏灯必须逐像素保留天空颜色和透明度。
            require(frame.captureDepth(world, depth, SIZE, SIZE), "production near-depth capture");
            try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
                require(frame.captureColor(world, SIZE, SIZE), "production scene-color capture");
                shader.setSampler("SceneColor", frame.color()); shader.setSampler("SceneDepth", frame.depth());
                shader.setSampler("DistantDepth", frame.depth());
                shader.safeGetUniform("InverseProjection").set(new Matrix4f().perspective((float) Math.PI / 2, 1, .1F, 100).invert());
                shader.safeGetUniform("NearEmptyDepth").set(1F);
                shader.safeGetUniform("LightCount").set(0); shader.safeGetUniform("OutputMode").set(0);
                // 模拟外部原生 GL 恢复后，显卡开着混合而引擎缓存仍记为关闭。
                new BlendMode().apply(); RenderSystem.disableBlend();
                RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
                GL20.glBlendEquationSeparate(GL14.GL_FUNC_REVERSE_SUBTRACT, GL14.GL_FUNC_ADD);
                GL11.glEnable(GL11.GL_BLEND);
                frame.bind(world, SIZE, SIZE); frame.draw(shader);
                float[] pixel = new float[4]; GL11.glReadPixels(8, 8, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                System.out.println("Sky pixel under inherited blend=" + java.util.Arrays.toString(pixel));
                require(!GL11.glIsEnabled(GL11.GL_BLEND), "opaque screen draw inherited externally enabled blend");
                for (int channel = 0; channel < 4; channel++) {
                    require(Math.abs(pixel[channel] - SKY[channel]) < .002F,
                            "zero-light sky changed: channel=" + channel + " value=" + pixel[channel]);
                }
                float[] capturedDepth = new float[1];
                GL11.glReadPixels(8, 8, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, capturedDepth);
                require(Math.abs(capturedDepth[0] - 1F) < .00001F, "screen draw overwrote world/sky depth");
            }
            System.out.println("PASS production opaque screen draw preserves sky RGB/alpha and world depth under stale blend cache");
        } finally {
            frame.release(); GL30.glDeleteFramebuffers(world);
            RenderSystem.deleteTexture(color); RenderSystem.deleteTexture(depth);
        }
    }

    /** 原冲击波未保存独立采样器、混合方程；空作用域也必须将原生恢复同步给引擎。 */
    private static void testPressureWaveState() throws Exception {
        RenderSystem.depthMask(true); RenderSystem.enableDepthTest(); RenderSystem.enableCull();
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_REVERSE_SUBTRACT, GL14.GL_FUNC_SUBTRACT);
        try (AutoCloseable ignored = scope("org.ywzj.rvp.client.visual.shockwave.RVP_PressureWavePass$State")) {
            RenderSystem.depthMask(false); RenderSystem.disableDepthTest(); RenderSystem.disableCull();
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
        }
        require(GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB) == GL14.GL_FUNC_REVERSE_SUBTRACT,
                "pressure wave leaked RGB blend equation");
        require(GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA) == GL14.GL_FUNC_SUBTRACT,
                "pressure wave leaked alpha blend equation");
        RenderSystem.depthMask(false); RenderSystem.disableDepthTest(); RenderSystem.disableCull();
        require(!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), "pressure wave left stale depth-write cache");
        require(!GL11.glIsEnabled(GL11.GL_DEPTH_TEST), "pressure wave left stale depth-test cache");
        require(!GL11.glIsEnabled(GL11.GL_CULL_FACE), "pressure wave left stale cull cache");
        System.out.println("PASS production pressure wave scope restores blend equations and engine state");
    }

    /** 完整冲击波在零雾量/零折射时必须保留背景，采样器与混合不能让天空变黑。 */
    private static void testPressureWaveDraw(Path root) throws Exception {
        int color = texture(false); int depth = texture(true);
        int world = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, world);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, SIZE, SIZE);
        Class<?> passType = Class.forName("org.ywzj.rvp.client.visual.shockwave.RVP_PressureWavePass");
        Constructor<?> passConstructor = passType.getDeclaredConstructor(); passConstructor.setAccessible(true);
        Object pass = passConstructor.newInstance();
        int[] samplers = {GL33.glGenSamplers(), GL33.glGenSamplers()};
        try (ShaderInstance shader = new ShaderInstance(resources(root),
                ResourceLocation.fromNamespaceAndPath("ywzj_rvp", "pressure_wave_mist_gradient"), DefaultVertexFormat.POSITION)) {
            Field shaderField = passType.getDeclaredField("shader"); shaderField.setAccessible(true); shaderField.set(pass, shader);
            // 颜色采样器要求 mipmap，但特效副本只有第零级；深度保持可读，避免误遮挡把波面全部丢弃。
            for (int unit = 0; unit < samplers.length; unit++) {
                GL33.glSamplerParameteri(samplers[unit], GL11.GL_TEXTURE_MIN_FILTER,
                        unit == 0 ? GL11.GL_NEAREST_MIPMAP_NEAREST : GL11.GL_NEAREST);
                GL33.glBindSampler(unit, samplers[unit]);
            }
            require(Boolean.TRUE.equals(invoke(pass, "captureDepth", new Class<?>[0])), "production pressure depth capture");
            Class<?> waveType = Class.forName(passType.getName() + "$Wave");
            Constructor<?> waveConstructor = waveType.getDeclaredConstructors()[0]; waveConstructor.setAccessible(true);
            Object wave = waveConstructor.newInstance(new Vector3f(0, 0, -6), 2F, 4F, 0F, 1F, 0F, 0F, 0F,
                    new RVP_PressureWaveGeometry.Bounds(-1, -1, 1, 1), new Vector3f());
            new BlendMode().apply(); RenderSystem.disableBlend();
            RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_REVERSE_SUBTRACT, GL14.GL_FUNC_ADD);
            GL11.glEnable(GL11.GL_BLEND);
            // 调用实际两阶段冲击波绘制，不能用测试内自写的全屏 Shader 替代。
            invoke(pass, "render", new Class<?>[] {Matrix4f.class, Matrix4f.class, List.class},
                    new Matrix4f().perspective((float) Math.PI / 2, 1, .1F, 100), new Matrix4f(), List.of(wave));
            float[] pixels = new float[SIZE * SIZE * 4];
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
            for (int i = 0; i < pixels.length; i++) {
                require(Math.abs(pixels[i] - SKY[i % 4]) < .002F, "pressure wave altered zero-strength sky at " + i + "=" + pixels[i]);
            }
            for (int unit = 0; unit < samplers.length; unit++) {
                require(GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, unit) == samplers[unit], "pressure sampler restore " + unit);
            }
            // 再画一层可见白雾，证明上一轮保留背景不是因为整个 pass 没画或全部 discard。
            require(Boolean.TRUE.equals(invoke(pass, "captureDepth", new Class<?>[0])), "second pressure depth capture");
            Object mist = waveConstructor.newInstance(new Vector3f(0, 0, -6), 2F, 4F, .2F, 1F, 0F, 0F, 0F,
                    new RVP_PressureWaveGeometry.Bounds(-1, -1, 1, 1), new Vector3f());
            invoke(pass, "render", new Class<?>[] {Matrix4f.class, Matrix4f.class, List.class},
                    new Matrix4f().perspective((float) Math.PI / 2, 1, .1F, 100), new Matrix4f(), List.of(mist));
            GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
            float brightest = 0;
            for (int i = 0; i < pixels.length; i += 4) { brightest = Math.max(brightest, pixels[i]); }
            require(brightest > SKY[0] + .01F, "pressure wave control did not draw visible mist");
            float[] worldDepth = new float[1];
            GL11.glReadPixels(8, 8, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, worldDepth);
            require(Math.abs(worldDepth[0] - 1F) < .00001F, "pressure wave overwrote world depth");
            System.out.println("PASS complete production pressure wave preserves sky under stale blend cache and external mipmap samplers");
        } finally {
            // 调用生产释放入口，仅释放本测试 pass 自己分配的显存。
            invoke(pass, "release", new Class<?>[0]);
            for (int unit = 0; unit < samplers.length; unit++) { GL33.glBindSampler(unit, 0); GL33.glDeleteSamplers(samplers[unit]); }
            GL30.glDeleteFramebuffers(world); RenderSystem.deleteTexture(color); RenderSystem.deleteTexture(depth);
        }
    }

    /** 为生产 Shader 加载器提供只读资源目录，不修改已有文件。 */
    private static ResourceProvider resources(Path root) {
        PathPackResources pack = new PathPackResources("rvp-screen-state-regression", root.resolve("src/main/resources"), false);
        return location -> {
            Path file = root.resolve("src/main/resources/assets").resolve(location.getNamespace()).resolve(location.getPath());
            return Files.isRegularFile(file) ? Optional.of(new Resource(pack, () -> Files.newInputStream(file))) : Optional.empty();
        };
    }

    /** 仅诊断程序反射调用生产私有入口，避免为测试扩大正式 API 或新增注入。 */
    private static Object invoke(Object target, String method, Class<?>[] parameters, Object... arguments) throws Exception {
        Method entry = target.getClass().getDeclaredMethod(method, parameters); entry.setAccessible(true);
        return entry.invoke(target, arguments);
    }

    /** 仅诊断程序反射进入生产私有作用域，不新增生产访问器或 Mixin。 */
    private static AutoCloseable scope(String name) throws Exception {
        Constructor<?> constructor = Class.forName(name).getDeclaredConstructor();
        constructor.setAccessible(true); return (AutoCloseable) constructor.newInstance();
    }

    /** 创建测试自有的天空颜色/空深度纹理；内存纹理不涉及美术资源文件。 */
    private static int texture(boolean depth) {
        int texture = GL11.glGenTextures();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0); RenderSystem.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        float[] data = new float[SIZE * SIZE * (depth ? 1 : 4)];
        for (int i = 0; i < data.length; i++) { data[i] = depth ? 1F : SKY[i % 4]; }
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, depth ? GL30.GL_DEPTH_COMPONENT32F : GL30.GL_RGBA16F,
                SIZE, SIZE, 0, depth ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA, GL11.GL_FLOAT, data);
        return texture;
    }

    /** 失败立即中止，不能把只打印日志当作验收。 */
    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
