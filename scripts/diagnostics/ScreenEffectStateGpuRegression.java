package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.server.packs.resources.ResourceManager;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.server.packs.PackResources;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry;
import org.ywzj.rvp.client.render.RVP_GlStateSync;

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
            for (String test : List.of("DH", "lighting", "pressure", "pressure pixels", "vehicle overload", "blend history")) {
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
                        // 调用本体真实过载 Shader，验证高度变化激活后与三个 RVP 作用域的连续交接。
                        case "vehicle overload" -> testVehicleOverload(Path.of(args[0]));
                        // 调用实际作用域，验证反向状态组合、嵌套、异常退出和首次绘制前的 null 历史。
                        case "blend history" -> testBlendHistoryContracts();
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
                // 模拟原版 RenderType：先启用透明混合，再应用未自带 blend 配置的默认 Shader。
                // 自绘作用域不能把 BlendMode 的全局历史改成“透明 Shader”，否则默认 Shader 会关闭混合。
                new BlendMode().apply();
                RVP_GlStateSync.restoreBlend(true, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ZERO, GL11.GL_ONE, GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                RenderSystem.enableBlend();
                shader.safeGetUniform("OutputMode").set(2);
                shader.apply();
                try {
                    // 零盏灯的自发光通道输出全透明黑；正确透明混合必须完整保留背后的世界。
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                    GL11.glReadPixels(8, 8, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                    System.out.println("Next transparent world pass=" + java.util.Arrays.toString(pixel)
                            + "; blend=" + GL11.glIsEnabled(GL11.GL_BLEND));
                    for (int channel = 0; channel < 4; channel++) {
                        require(Math.abs(pixel[channel] - SKY[channel]) < .002F,
                                "restoring external blend changed Shader BlendMode history and blackened the next world pass");
                    }
                } finally { shader.clear(); }
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
            if (Files.isRegularFile(file)) { return Optional.of(new Resource(pack, () -> Files.newInputStream(file))); }
            java.net.URL bundled = ScreenEffectStateGpuRegression.class.getResource(
                    "/assets/" + location.getNamespace() + "/" + location.getPath());
            return bundled == null ? Optional.empty() : Optional.of(new Resource(pack, bundled::openStream));
        };
    }

    /** 重放“天空/DH 的 Alpha 为零→RVP→本体过载”的真实程序链，近景 Alpha=1 用于对照。 */
    private static void testVehicleOverload(Path root) throws Exception {
        int sourceColor = texture(false); int depth = texture(true); int outputColor = texture(false);
        int source = GL30.glGenFramebuffers(); int output = GL30.glGenFramebuffers();
        int triangle = GL30.glGenVertexArrays(); int positions = GL15.glGenBuffers();
        RVP_ScreenFrameResources frame = new RVP_ScreenFrameResources();
        float[] pattern = new float[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int at = (y * SIZE + x) * 4;
                pattern[at] = .125F; pattern[at + 1] = .25F; pattern[at + 2] = .5F;
                pattern[at + 3] = x < SIZE / 2 ? 0F : 1F;
            }
        }
        RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE0); RVP_GlStateSync.bindTexture2D(sourceColor);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_FLOAT, pattern);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, source);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sourceColor, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        // 调用真实场景捕获，颜色和深度从同一来源配对。
        require(frame.captureDepth(source, depth, SIZE, SIZE), "overload scene depth capture");
        try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
            require(frame.captureColor(source, SIZE, SIZE), "overload scene color capture");
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, output);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outputColor, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL30.glBindVertexArray(triangle); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positions);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {0,0,0, SIZE*2,0,0, 0,SIZE*2,0}, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        try (ShaderInstance light = new ShaderInstance(resources(root),
                    ResourceLocation.fromNamespaceAndPath("ywzj_rvp", "screen_light"), DefaultVertexFormat.POSITION);
             EffectInstance overload = new EffectInstance(manager(root), "ywzj_vehicle:overload")) {
            light.setSampler("SceneColor", frame.color()); light.setSampler("SceneDepth", frame.depth());
            light.setSampler("DistantDepth", frame.depth()); light.safeGetUniform("NearEmptyDepth").set(1F);
            light.safeGetUniform("InverseProjection").set(new Matrix4f().perspective((float)Math.PI/2,1,.1F,100).invert());
            light.safeGetUniform("OutputMode").set(0); light.safeGetUniform("LightCount").set(0);
            overload.setSampler("DiffuseSampler", () -> sourceColor);
            overload.safeGetUniform("ProjMat").set(new Matrix4f().setOrtho(0, SIZE, 0, SIZE, -1, 1));
            overload.safeGetUniform("OutSize").set((float)SIZE, (float)SIZE);
            overload.safeGetUniform("Progress").set(0F); overload.safeGetUniform("Type").set(0);
            java.util.List<String> failures = new java.util.ArrayList<>();
            for (String route : List.of("control", "lighting", "pressure", "DH")) {
                // 重现无特效时的调用历史：透明后处理已经 apply 过，而世界渲染通过 RenderSystem 关闭混合。
                new BlendMode().apply(); overload.apply(); overload.clear(); RVP_GlStateSync.blend(false);
                if (!route.equals("control")) {
                    AutoCloseable saved = route.equals("lighting") ? new RVP_ScreenFrameResources.State()
                            : scope(route.equals("DH") ? "org.ywzj.rvp.client.compat.distanthorizons.RVP_DhGlStateScope"
                            : "org.ywzj.rvp.client.visual.shockwave.RVP_PressureWavePass$State");
                    try (saved) {
                        if (route.equals("lighting")) {
                            // 调用生产光照绘制；零盏灯只重写原背景，排除光照数值影响。
                            frame.bind(source, SIZE, SIZE); frame.draw(light);
                        } else {
                            // 冲击波/DH 同样使用默认不透明 Shader，已有专项覆盖其实际几何和颜色输出。
                            light.apply(); RVP_GlStateSync.blend(false); light.clear();
                        }
                    }
                }
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, output);
                GL11.glViewport(0,0,SIZE,SIZE); RVP_GlStateSync.scissor(false); RVP_GlStateSync.depthTest(false);
                RVP_GlStateSync.colorMask(true,true,true,true); RVP_GlStateSync.cull(false);
                GL11.glClearColor(0,0,0,0); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                GL30.glBindVertexArray(triangle);
                overload.apply();
                GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                float[] sky = new float[4]; float[] near = new float[4];
                GL11.glReadPixels(3,8,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,sky);
                GL11.glReadPixels(12,8,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,near);
                System.out.println("Actual overload after " + route + ": sky=" + java.util.Arrays.toString(sky)
                        + " near=" + java.util.Arrays.toString(near) + " blend=" + GL11.glIsEnabled(GL11.GL_BLEND));
                for (int c=0;c<3;c++) {
                    if (Math.abs(sky[c]-SKY[c])>.002F || Math.abs(near[c]-SKY[c])>.002F) { failures.add(route); break; }
                }
                overload.clear();
            }
            require(failures.isEmpty(), "RVP changed overload blend history; zero-alpha sky/DH black after " + failures);
            System.out.println("PASS actual vehicle overload preserves zero-alpha sky/DH and opaque near scene after all RVP scopes");
        } finally {
            frame.release(); GL30.glDeleteFramebuffers(source); GL30.glDeleteFramebuffers(output);
            GL15.glDeleteBuffers(positions); GL30.glDeleteVertexArrays(triangle);
            RenderSystem.deleteTexture(sourceColor); RenderSystem.deleteTexture(depth); RenderSystem.deleteTexture(outputColor);
        }
    }

    /** 为本体 EffectInstance 提供真实只读资源，读取 mod jar 和 Minecraft 的原始 blit 程序。 */
    private static ResourceManager manager(Path root) {
        ResourceProvider provider = resources(root);
        return new ResourceManager() {
            @Override public Optional<Resource> getResource(ResourceLocation location) { return provider.getResource(location); }
            @Override public Set<String> getNamespaces() { return Set.of("minecraft", "ywzj_vehicle", "ywzj_rvp"); }
            @Override public List<Resource> getResourceStack(ResourceLocation location) { return getResource(location).stream().toList(); }
            @Override public Map<ResourceLocation,Resource> listResources(String path, Predicate<ResourceLocation> filter) { return Map.of(); }
            @Override public Map<ResourceLocation,List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) { return Map.of(); }
            @Override public Stream<PackResources> listPacks() { return Stream.empty(); }
        };
    }

    /** 同时覆盖“历史不透明但实际混合开启”，防止修远景黑屏时重引入上轮默认黑屏。 */
    private static void testBlendHistoryContracts() throws Exception {
        Field history = BlendMode.class.getDeclaredField("lastApplied"); history.setAccessible(true);
        Object original = history.get(null);
        try {
            for (String type : List.of("org.ywzj.rvp.client.light.RVP_ScreenFrameResources$State",
                    "org.ywzj.rvp.client.visual.shockwave.RVP_PressureWavePass$State",
                    "org.ywzj.rvp.client.compat.distanthorizons.RVP_DhGlStateScope")) {
                new BlendMode().apply(); Object before = history.get(null); RVP_GlStateSync.blend(true);
                try (AutoCloseable ignored = scope(type)) {
                    new BlendMode(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL14.GL_FUNC_ADD).apply();
                }
                require(history.get(null) == before, "scope fabricated/replaced shader blend history: " + type);
                new BlendMode().apply();
                require(GL11.glIsEnabled(GL11.GL_BLEND), "next default shader disabled transparent world rendering: " + type);
            }
            history.set(null, null);
            try {
                try (AutoCloseable outer = scope("org.ywzj.rvp.client.light.RVP_ScreenFrameResources$State")) {
                    new BlendMode().apply(); Object middle = history.get(null);
                    try (AutoCloseable inner = scope("org.ywzj.rvp.client.visual.shockwave.RVP_PressureWavePass$State")) {
                        new BlendMode(GL11.GL_ONE, GL11.GL_ONE, GL14.GL_MAX).apply();
                    }
                    require(history.get(null) == middle, "nested scope did not restore its own caller history");
                    throw new IllegalArgumentException("expected diagnostic exception");
                }
            } catch (IllegalArgumentException expected) { /* 异常退出也必须走生产 close。 */ }
            require(history.get(null) == null, "exceptional outer scope did not preserve initial null history");
            System.out.println("PASS exact caller blend history, opposite GL state, nesting, exceptions and null startup state");
        } finally { history.set(null, original); }
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
