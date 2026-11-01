package org.ywzj.rvp.client.light;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.light.RVP_BloomParameters;
import org.ywzj.rvp.light.RVP_TransientLightTimeline;
import org.ywzj.rvp.light.RVP_HaloDecayCurve;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 隐藏窗口回读生产 GLSL 的真实像素；不启动游戏、不创建或改写任何美术资源。 */
public final class ScreenLightGpuRegression {
    /** 测试纹理宽高，覆盖连续深度邻域。 */
    private static final int SIZE = 16;
    /** 原画面颜色和非 1 的 Alpha，用于检测错误覆盖。 */
    private static final float[] BACKGROUND = {0.10F, 0.12F, 0.16F, 0.37F};
    /** 本次上下文创建的纹理，只释放自有 GPU 对象。 */
    private static final List<Integer> TEXTURES = new ArrayList<>();
    /** 本次上下文创建的离屏目标。 */
    private static final List<Integer> TARGETS = new ArrayList<>();
    /** FOV 90°、近面 0.1、远面 100 的深度系数。 */
    private static final float A = -100.1F / 99.9F;
    /** 投影矩阵的透视深度偏移。 */
    private static final float B = -20.0F / 99.9F;
    /** 独立全屏顶点数组。 */
    private static int vao;
    /** 全屏三角形顶点缓冲。 */
    private static int vbo;

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) { throw new IllegalStateException("GLFW init failed"); }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "RVP light GPU regression", 0, 0);
        if (window == 0) { throw new IllegalStateException("Hidden GL 3.2 context failed"); }
        int light = 0;
        int bloom = 0;
        int plume = 0;
        int smoke = 0;
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GPU=" + GL11.glGetString(GL11.GL_RENDERER));
            Path shaders = Path.of(args[0]).resolve("src/main/resources/assets/ywzj_rvp/shaders/core");
            // 编译真实生产文件，不能用测试内的简化 Shader 替代。
            light = program(shaders, "screen_light_occlusion");
            bloom = program(shaders, "screen_light_bloom_multiscale");
            System.out.println("PASS both production GLSL programs compile and link");
            setupTriangle();
            testSurfaceLight(light);
            testHemisphereLight(light);
            testDistantAndShaderDepth(light);
            testBorrowedPackedDepthCopy();
            testEngineEmission(light, bloom);
            testBloom(light, bloom);
            // 调用 RVP 新喷流程序专项回读，旧圆形喷口测试继续保留用于防止误删旧实现。
            plume = program(shaders, "rocket_plume_ranges");
            testRocketPlume(Path.of(args[0]), plume, bloom);
            testWorldEngineHalo(Path.of(args[0]), plume);
            // 调用新版多尺度和独立广域亮度专项回读，验证实际输出而非仅检查参数数值。
            testMultiscaleBloom(Path.of(args[0]), bloom, plume);
            testIndependentPlumeGlow(plume);
            testSeparatedLightHalo(light);
            testTransientLightTimeline(light, bloom);
            testHaloDecayCurves(light);
            testExplosionPeakHalo(light);
            testExtendedEngineSurface(light);
            smoke = program(shaders, "smoke_light_occlusion");
            testSmokeLighting(smoke);
            testMuzzleLighting(Path.of(args[0]), smoke);
            // 调用生产环境适应像素回归；使用独立程序状态，不污染上述历史照明与遮挡基线。
            testAmbientLighting(shaders);
            if (GL11.glGetError() != GL11.GL_NO_ERROR) { throw new AssertionError("OpenGL error at completion"); }
            System.out.println("PASS all RVP screen light GPU regressions; OpenGL error=0");
        } finally {
            GL20.glUseProgram(0);
            for (int fbo : TARGETS) { GL30.glDeleteFramebuffers(fbo); }
            for (int texture : TEXTURES) { GL11.glDeleteTextures(texture); }
            if (light != 0) { GL20.glDeleteProgram(light); }
            if (bloom != 0) { GL20.glDeleteProgram(bloom); }
            if (plume != 0) { GL20.glDeleteProgram(plume); }
            if (smoke != 0) { GL20.glDeleteProgram(smoke); }
            if (vbo != 0) { GL15.glDeleteBuffers(vbo); }
            if (vao != 0) { GL30.glDeleteVertexArrays(vao); }
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    /**
     * 使用真实 Minecraft 写掩码回调复现尾焰自遮挡；由带 Minecraft 类路径的诊断入口调用。
     * 不加载/改写资源贴图，测试薄片仅模拟实际尾焰在喷口前方留下的深度。
     */
    static void testMotorFlameDepthCapture(Path root, Runnable oldMask, Runnable fixedMask, Runnable fixedDepthTest)
            throws Exception {
        Path shaders = root.resolve("src/main/resources/assets/ywzj_rvp/shaders/core");
        int light = program(shaders, "screen_light_occlusion");
        int flame = GL20.glCreateProgram();
        int vertex = compile(GL20.GL_VERTEX_SHADER,
                "#version 150\nin vec3 Position; void main(){gl_Position=vec4(Position,1.0);}");
        int fragment = compile(GL20.GL_FRAGMENT_SHADER,
                "#version 150\nuniform float FlameDepth; out vec4 fragColor;"
                        + "void main(){gl_FragDepth=FlameDepth;fragColor=vec4(1.0,0.48,0.12,0.4);}");
        GL20.glAttachShader(flame, vertex); GL20.glAttachShader(flame, fragment);
        GL20.glBindAttribLocation(flame, 0, "Position"); GL20.glLinkProgram(flame);
        require(GL20.glGetProgrami(flame, GL20.GL_LINK_STATUS) != 0, GL20.glGetProgramInfoLog(flame));
        GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment);
        setupTriangle();
        try {
            int[] scene = target(SIZE, SIZE);
            int sceneDepth = depth(SIZE, SIZE, 0, false);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, sceneDepth, 0);
            int[] contribution = target(SIZE, SIZE);
            // 未计动画时，实际首片尾焰约在喷口后 40.4/16*0.3=0.76 格；从弹尾观察时它更靠近相机。
            float flameDepth = ((-A * 2.24F + B) / 2.24F) * 0.5F + 0.5F;
            for (int pass = 0; pass < 3; pass++) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, scene[0]);
                GL11.glViewport(0, 0, SIZE, SIZE);
                GL11.glDepthMask(true); GL11.glClearDepth(pass == 2 ? depthValue(1) : 1);
                GL11.glClearColor(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], BACKGROUND[3]);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                GL20.glUseProgram(flame);
                GL20.glUniform1f(GL20.glGetUniformLocation(flame, "FlameDepth"), flameDepth);
                // 执行 Minecraft 的真实深度状态，与生产渲染类型使用相同的状态片段。
                fixedDepthTest.run();
                (pass == 0 ? oldMask : fixedMask).run();
                require(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) == (pass == 0), "production flame depth mask mismatch");
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                float[] actualDepth = new float[1];
                GL11.glReadPixels(8, 8, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, actualDepth);
                near(pass == 0 ? flameDepth : pass == 2 ? depthValue(1) : 1, actualDepth[0], "captured flame depth");
                if (pass == 2) { background(pixel(scene[0], 8, 8), "wall still hides translucent flame"); }
                GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
                GL20.glUseProgram(light);
                uniforms(light);
                sampler(light, "SceneColor", 0, scene[1]);
                sampler(light, "SceneDepth", 1, sceneDepth);
                sampler(light, "DistantDepth", 2, sceneDepth);
                // 调用生产发动机预设，喷口位于相机前方 3 格，和旧空天空测试一致。
                engineLights(light, RVP_LightParameters.engine(), 1, 1);
                GL20.glUniform1i(GL20.glGetUniformLocation(light, "OutputMode"), 1);
                render(contribution, SIZE, SIZE);
                float[] hdr = pixel(contribution[0], 8, 8);
                if (pass == 1) {
                    require(hdr[0] >= 59990 && hdr[3] > 1000, "no-depth flame did not restore core and bloom input");
                    System.out.println("PASS production no-depth flame preserves sky depth=" + actualDepth[0]
                            + "; core=" + hdr[0] + "; bloomEnergy=" + hdr[3]);
                } else {
                    zero(hdr, pass == 0 ? "legacy flame reproduces missing core" : "wall continues to block core");
                    System.out.println(pass == 0
                            ? "REPRODUCED legacy flame writes foreground depth=" + actualDepth[0] + "; engine core=0"
                            : "PASS real foreground wall still hides flame and engine core after no-depth fix");
                }
            }
        } finally { GL20.glUseProgram(0); GL20.glDeleteProgram(light); GL20.glDeleteProgram(flame); }
    }

    /** 把观察空间正距离换成与测试生产程序配对的标准透视深度。 */
    private static float depthValue(float distance) { return ((-A * distance + B) / distance) * 0.5F + 0.5F; }

    /**
     * 复现 Oculus 在采集前画烟造成的表面假阴影；深度状态与快照均由真实生产 Java 回调提供。
     * 薄片只在显存中模拟烟团，不创建或改写任何贴图资源。
     */
    static void testExplosionSmokeDepthCapture(Path root, Runnable smokeDepth,
            java.util.function.IntBinaryOperator captureDepth) throws Exception {
        Path shaders = root.resolve("src/main/resources/assets/ywzj_rvp/shaders/core");
        int light = program(shaders, "screen_light_occlusion");
        // 调用保留的旧资源复现“烟深度＋无界检查”的历史假影；正式验证仍使用上面的新程序。
        int originalLight = program(shaders, "screen_light_halo_timeline");
        int smoke = program(shaders, "smoke_light_occlusion");
        int baseSmoke = GL20.glCreateProgram();
        int vertex = compile(GL20.GL_VERTEX_SHADER,
                "#version 150\nin vec3 Position; void main(){gl_Position=vec4(Position,1.0);}");
        int fragment = compile(GL20.GL_FRAGMENT_SHADER,
                "#version 150\nuniform float SmokeDepth; out vec4 fragColor;"
                        + "void main(){gl_FragDepth=SmokeDepth;fragColor=vec4(0.3,0.3,0.3,0.6);}");
        GL20.glAttachShader(baseSmoke, vertex); GL20.glAttachShader(baseSmoke, fragment);
        GL20.glBindAttribLocation(baseSmoke, 0, "Position"); GL20.glLinkProgram(baseSmoke);
        require(GL20.glGetProgrami(baseSmoke, GL20.GL_LINK_STATUS) != 0, GL20.glGetProgramInfoLog(baseSmoke));
        GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment);
        setupTriangle();
        try {
            int[] scene = target(SIZE, SIZE);
            int sceneDepth = depth(SIZE, SIZE, 5, false);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, sceneDepth, 0);
            int[] output = target(SIZE, SIZE);
            float[] baseline = null;
            for (int pass = 0; pass < 5; pass++) {
                boolean legacy = pass == 1, late = pass == 3, wall = pass == 4;
                GL30.glBindVertexArray(vao);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, scene[0]);
                GL11.glViewport(0, 0, SIZE, SIZE); GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_BLEND); GL11.glDepthMask(true);
                GL11.glClearDepth(depthValue(wall ? 1 : 5));
                GL11.glClearColor(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], BACKGROUND[3]);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                int snapshot = late ? captureDepth.applyAsInt(scene[0], sceneDepth) : 0;
                if (pass != 0) {
                    GL20.glUseProgram(baseSmoke);
                    GL20.glUniform1f(GL20.glGetUniformLocation(baseSmoke, "SmokeDepth"), depthValue(3));
                    GL11.glDepthFunc(GL11.GL_LEQUAL);
                    if (legacy) { GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(true); }
                    else {
                        // 调用生产烟雾规则；外层刻意制造缓存与真实 GL 相反，不能只验证 Java 的预期值。
                        smokeDepth.run();
                        require(GL11.glIsEnabled(GL11.GL_DEPTH_TEST), "production smoke lost foreground depth test");
                        require(!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), "production smoke still writes occlusion depth");
                    }
                    GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(6, 0, 4, SIZE);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3); GL11.glDisable(GL11.GL_SCISSOR_TEST);
                    if (wall) { background(pixel(scene[0], 8, 8), "real foreground wall must still hide smoke"); }
                }
                if (!late) { snapshot = captureDepth.applyAsInt(scene[0], sceneDepth); }
                GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false);
                int selectedLight = legacy ? originalLight : light;
                GL20.glUseProgram(selectedLight); uniforms(selectedLight);
                sampler(selectedLight, "SceneColor", 0, scene[1]); sampler(selectedLight, "SceneDepth", 1, snapshot);
                sampler(selectedLight, "DistantDepth", 2, snapshot);
                // 调用真实爆炸预设，灯在烟后、表面在五格处，回读烟轮廓外的周围方块/实体像素。
                RVP_LightParameters source = RVP_LightParameters.explosion(4);
                lights(selectedLight, 1, 0, 0, -4, source.radius(), source.intensity(), source.surfaceWeight(), 0);
                GL20.glUniform4f(GL20.glGetUniformLocation(selectedLight, "LightColorIntensity0"),
                        source.red(), source.green(), source.blue(), source.intensity());
                GL20.glUniform1i(GL20.glGetUniformLocation(selectedLight, "OutputMode"), 1);
                render(output, SIZE, SIZE);
                float[] surface = pixel(output[0], 12, 8);
                if (pass == 0) {
                    baseline = surface;
                    require(surface[0] > 0.05F, "reference surrounding surface did not receive explosion light");
                } else if (legacy) {
                    zero(surface, "legacy early smoke must reproduce shadow outside its own silhouette");
                    System.out.println("REPRODUCED early depth-writing explosion smoke casts false surface shadow");
                    // 调用修复程序处理同一污染快照；烟在光路线段之外，不能再把它无限延伸成假遮挡。
                    GL20.glUseProgram(light); uniforms(light);
                    sampler(light, "SceneColor", 0, scene[1]); sampler(light, "SceneDepth", 1, snapshot);
                    sampler(light, "DistantDepth", 2, snapshot);
                    lights(light, 1, 0, 0, -4, source.radius(), source.intensity(), source.surfaceWeight(), 0);
                    GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightColorIntensity0"),
                            source.red(), source.green(), source.blue(), source.intensity());
                    GL20.glUniform1i(GL20.glGetUniformLocation(light, "OutputMode"), 1);
                    render(output, SIZE, SIZE);
                    near(baseline[0], pixel(output[0], 12, 8)[0], "bounded path retained old infinite smoke shadow");
                    System.out.println("PASS repaired bounded path rejects false smoke shadow beyond the light segment");
                } else if (wall) {
                    zero(surface, "real wall must still block explosion illumination");
                    System.out.println("PASS real foreground wall still hides smoke and blocks light");
                } else {
                    for (int channel = 0; channel < 4; channel++) {
                        near(baseline[channel], surface[channel], "smoke changed surrounding surface illumination");
                    }
                    System.out.println("PASS " + (late ? "late" : "early")
                            + " no-depth explosion smoke preserves surrounding surface light=" + surface[0]);
                    if (pass == 2) {
                        // 调用真实烟雾程序，确认同一份干净快照可让烟接受其后方爆炸光，而不是只消除影子。
                        testSmokeLighting(smoke, snapshot);
                    }
                }
            }
        } finally {
            GL20.glUseProgram(0); GL20.glDeleteProgram(baseSmoke); GL20.glDeleteProgram(light); GL20.glDeleteProgram(originalLight); GL20.glDeleteProgram(smoke);
            for (int fbo : TARGETS) { GL30.glDeleteFramebuffers(fbo); }
            for (int texture : TEXTURES) { GL11.glDeleteTextures(texture); }
            TARGETS.clear(); TEXTURES.clear();
            GL15.glDeleteBuffers(vbo); GL30.glDeleteVertexArrays(vao); vbo = 0; vao = 0;
        }
    }

    /** 用真实生产程序验证 DH 独立投影、不同尺寸、正反深度、近景遮挡与光影手持物。 */
    private static void testDistantAndShaderDepth(int shader) {
        int scene = color(SIZE, SIZE, BACKGROUND);
        int sky = depth(SIZE, SIZE, 0, false);
        int nearWall = depth(SIZE, SIZE, 5, false);
        int farTerrain = projectedDepth(SIZE / 2, SIZE / 2, 40, 7.5F, 512, false, false);
        int[] output = target(SIZE, SIZE);
        GL20.glUseProgram(shader);
        uniforms(shader);
        sampler(shader, "SceneColor", 0, scene);
        sampler(shader, "SceneDepth", 1, sky);
        sampler(shader, "DistantDepth", 2, farTerrain);
        distantProjection(shader, 7.5F, 512, false, 1);
        lights(shader, 1, 0, 0, -38, 8, 1, 1, 0);
        render(output, SIZE, SIZE);
        float[] forward = pixel(output[0], 8, 8);
        require(forward[0] > BACKGROUND[0] + 0.08F, "DH-only surface did not receive light");
        near(BACKGROUND[3], forward[3], "DH surface alpha");
        System.out.println("PASS DH-only surface receives light with independent 7.5/512 projection and half-size depth");

        sampler(shader, "SceneDepth", 1, nearWall);
        render(output, SIZE, SIZE);
        background(pixel(output[0], 8, 8), "near wall must hide DH lit terrain");
        int farNear = depth(SIZE, SIZE, 40, false);
        int closerDistant = projectedDepth(SIZE, SIZE, 5, 0.5F, 512, false, false);
        sampler(shader, "SceneDepth", 1, farNear);
        sampler(shader, "DistantDepth", 2, closerDistant);
        distantProjection(shader, 0.5F, 512, false, 1);
        lights(shader, 1, 0, 0, -3, 5, 1, 1, 0);
        render(output, SIZE, SIZE);
        require(pixel(output[0], 8, 8)[0] > BACKGROUND[0] + 0.1F, "DH/near sources compared raw depth rather than world distance");
        System.out.println("PASS near wall occludes DH and nearer DH surface wins by reconstructed distance");

        sampler(shader, "SceneDepth", 1, sky);
        int reverseTerrain = projectedDepth(SIZE / 2, SIZE / 2, 40, 7.5F, 512, true, false);
        sampler(shader, "DistantDepth", 2, reverseTerrain);
        distantProjection(shader, 7.5F, 512, true, 1);
        lights(shader, 1, 0, 0, -38, 8, 1, 1, 0);
        render(output, SIZE, SIZE);
        near(forward[0], pixel(output[0], 8, 8)[0], "reverse-Z DH contribution");
        int reverseSky = projectedDepth(SIZE, SIZE, 0, 7.5F, 512, true, false);
        sampler(shader, "DistantDepth", 2, reverseSky);
        render(output, SIZE, SIZE);
        background(pixel(output[0], 8, 8), "reverse-Z empty DH sky");
        sampler(shader, "DistantDepth", 2, farTerrain);
        distantProjection(shader, 7.5F, 512, false, 2);
        render(output, SIZE, SIZE);
        near(forward[0], pixel(output[0], 8, 8)[0], "DH FOV remapping");
        background(pixel(output[0], 14, 8), "outside narrower DH frustum");
        System.out.println("PASS DH reverse-Z, empty-depth detection, independent FOV and frustum remapping");

        distantProjection(shader, 7.5F, 512, false, 1);
        lights(shader, 1, 20, 0, -38, 32, 1, 1, 0);
        int plain = projectedDepth(SIZE, SIZE, 40, 7.5F, 512, false, false);
        sampler(shader, "DistantDepth", 2, plain);
        render(output, SIZE, SIZE);
        require(pixel(output[0], 8, 8)[0] > BACKGROUND[0], "unoccluded DH path missing");
        // 旧 z=-20 遮挡条不在 z=-40 到 z=-38 的光路上；改用确实相交的 z=-39 表面。
        float[] dhCrossing = new float[SIZE * SIZE];
        float dhA = -(512F + 7.5F) / (512F - 7.5F), dhB = -2 * 512F * 7.5F / (512F - 7.5F);
        for (int y = 0; y < SIZE; y++) { for (int x = 0; x < SIZE; x++) {
            float distance = x >= 10 ? 39 : 40;
            dhCrossing[y * SIZE + x] = ((-dhA * distance + dhB) / distance) * .5F + .5F;
        } }
        int blocked = texture(SIZE, SIZE, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, dhCrossing);
        sampler(shader, "DistantDepth", 2, blocked);
        render(output, SIZE, SIZE);
        background(pixel(output[0], 8, 8), "DH foreground occluder");
        System.out.println("PASS DH depth participates in light-to-surface occlusion");

        float[] handPixels = new float[SIZE * SIZE];
        java.util.Arrays.fill(handPixels, 0.55F);
        int hand = texture(SIZE, SIZE, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, handPixels);
        sampler(shader, "SceneDepth", 1, hand);
        sampler(shader, "DistantDepth", 2, plain);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "HandDepthLimit"), (1F + 0.125F) / 2F);
        lights(shader, 1, 0, 0, -3, 48, 1, 1, 1);
        render(output, SIZE, SIZE);
        background(pixel(output[0], 8, 8), "shader-rendered hand is not world geometry");
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "HandDepthLimit"), 0);
        sampler(shader, "SceneDepth", 1, sky);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "HasDistantDepth"), 0);
        lights(shader, 1, 0, 0, -38, 8, 1, 1, 0);
        render(output, SIZE, SIZE);
        background(pixel(output[0], 8, 8), "missing-current-frame DH must not sample old texture");
        System.out.println("PASS shader hand mask, final-color alpha preservation and missing-frame DH isolation");
    }

    /** 配置 DH 独立投影及深度方向，不使用原版矩阵的 near/far。 */
    private static void distantProjection(int shader, float nearPlane, float farPlane, boolean reverse, float scale) {
        float a = -(farPlane + nearPlane) / (farPlane - nearPlane);
        float b = -2 * farPlane * nearPlane / (farPlane - nearPlane);
        if (reverse) { a = -a; b = -b; }
        matrix(shader, "DistantProjection", new float[] {scale,0,0,0, 0,scale,0,0, 0,0,a,-1, 0,0,b,0});
        matrix(shader, "InverseDistantProjection", new float[] {1/scale,0,0,0, 0,1/scale,0,0, 0,0,0,1/b, 0,0,-1,a/b});
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "HasDistantDepth"), 1);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "DistantEmptyDepth"), reverse ? 0 : 1);
    }

    /** 生成指定 near/far 与正反方向的远景深度，可叠加前景遮挡条。 */
    private static int projectedDepth(int width, int height, float distance, float nearPlane, float farPlane,
            boolean reverse, boolean occluder) {
        float a = -(farPlane + nearPlane) / (farPlane - nearPlane);
        float b = -2 * farPlane * nearPlane / (farPlane - nearPlane);
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float at = occluder && x >= 10 ? 20 : distance;
                float value = at == 0 ? 1 : ((-a * at + b) / at) * 0.5F + 0.5F;
                pixels[y * width + x] = reverse ? 1 - value : value;
            }
        }
        return texture(width, height, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, pixels);
    }

    /** 验证近景常用的深度/模板混合格式可以只读复制到浮点深度，不破坏源附件。 */
    private static void testBorrowedPackedDepthCopy() {
        int source = GL11.glGenTextures(); TEXTURES.add(source);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
        int[] packed = new int[SIZE * SIZE];
        java.util.Arrays.fill(packed, (0xbfffff << 8) | 0x55);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH24_STENCIL8, SIZE, SIZE, 0,
                GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, packed);
        int read = GL30.glGenFramebuffers(); TARGETS.add(read);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, read);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, source, 0);
        GL11.glDrawBuffer(GL11.GL_NONE); GL11.glReadBuffer(GL11.GL_NONE);
        require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE, "packed-depth read FBO incomplete");
        int copy = projectedDepth(SIZE, SIZE, 0, 0.1F, 100, false, false);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, copy);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, SIZE, SIZE);
        float[] copied = new float[SIZE * SIZE];
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, copied);
        near(0.75F, copied[0], "packed-depth to float copy");
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "packed-depth copy produced GL error");
        System.out.println("PASS read-only DEPTH24_STENCIL8 to DEPTH32F copy used by shader scene capture");
    }

    /** 检验地面/墙面受光、半径外、背面墙、可见遮挡条、天空、八灯过曝及可选空气散射。 */
    private static void testSurfaceLight(int shader) {
        int scene = color(SIZE, SIZE, BACKGROUND);
        int depth = depth(SIZE, SIZE, 5, false);
        int[] output = target(SIZE, SIZE);
        GL20.glUseProgram(shader);
        sampler(shader, "SceneColor", 0, scene);
        sampler(shader, "SceneDepth", 1, depth);
        uniforms(shader);
        lights(shader, 1, 0, 0, -3, 5, 1, 1, 0);
        render(output, SIZE, SIZE);
        float[] lit = pixel(output[0], SIZE / 2, SIZE / 2);
        require(lit[0] > BACKGROUND[0] + 0.1F && lit[1] > BACKGROUND[1], "visible surface did not receive light");
        near(BACKGROUND[3], lit[3], "surface alpha");
        System.out.println("PASS visible surface receives colored light; scene alpha preserved");

        lights(shader, 1, 20, 0, -3, 1, 1, 1, 0);
        render(output, SIZE, SIZE);
        background(pixel(output[0], SIZE / 2, SIZE / 2), "outside radius");
        lights(shader, 1, 0, 0, -7, 5, 1, 1, 0);
        render(output, SIZE, SIZE);
        background(pixel(output[0], SIZE / 2, SIZE / 2), "light behind wall");
        System.out.println("PASS radius attenuation and wall back-face rejection");

        int occluded = depth(SIZE, SIZE, 5, true);
        sampler(shader, "SceneDepth", 1, occluded);
        lights(shader, 1, 2, 0, -3, 5, 1, 1, 0);
        render(output, SIZE, SIZE);
        // 旧条带在 z=-2，受光点到 z=-3 的灯不会经过它；不能继续将这类假影验收为正确。
        require(pixel(output[0], 8, 8)[0] > BACKGROUND[0], "geometry beyond the light cast a false shadow");
        float[] crossingDepth = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) { for (int x = 0; x < SIZE; x++) {
            crossingDepth[y * SIZE + x] = depthValue(x >= 10 ? 4 : 5);
        } }
        sampler(shader, "SceneDepth", 1, texture(SIZE, SIZE, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, crossingDepth));
        render(output, SIZE, SIZE);
        background(pixel(output[0], 8, 8), "physical light segment crosses z=-4 blocker");
        System.out.println("PASS real light-segment occlusion; geometry beyond the light does not cast false shadows");

        int sky = depth(SIZE, SIZE, 0, false);
        sampler(shader, "SceneDepth", 1, sky);
        lights(shader, 1, 0, 0, -3, 5, 1, 1, 0);
        render(output, SIZE, SIZE);
        background(pixel(output[0], SIZE / 2, SIZE / 2), "sky is not a surface");
        sampler(shader, "SceneDepth", 1, depth);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "LightCount"), 0);
        render(output, SIZE, SIZE);
        background(pixel(output[0], SIZE / 2, SIZE / 2), "zero lights");

        lights(shader, 8, 0, 0, -3, 48, 4, 1, 0);
        render(output, SIZE, SIZE);
        float[] many = pixel(output[0], SIZE / 2, SIZE / 2);
        require(many[0] < 0.99F && many[1] < 0.99F && many[2] < 0.99F, "eight lights caused unbounded whiteout");
        near(BACKGROUND[3], many[3], "eight light alpha");
        System.out.println("PASS empty sky, zero lights, and bounded eight-light exposure");

        // 天空散射：只有没有深度的天空像素按视线与爆心方向的夹角染色，照亮半径与强度都留空以隔离该通道。
        // 显式设定 90° 标准视锥，四角才有可预测的视线方向差异可供对照。
        matrix(shader, "Projection", new float[] {1,0,0,0, 0,1,0,0, 0,0,A,-1, 0,0,B,0});
        matrix(shader, "InverseProjection", new float[] {1,0,0,0, 0,1,0,0, 0,0,0,1/B, 0,0,-1,A/B});
        sampler(shader, "SceneDepth", 1, sky);
        sampler(shader, "DistantDepth", 2, sky);
        lights(shader, 1, 0, 0, -50, 0, 0, 0, 0);
        for (int index = 0; index < 8; index++) {
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightSkyColorIntensity" + index), 1, 0.7F, 0.2F, 0);
            GL20.glUniform1f(GL20.glGetUniformLocation(shader, "LightSkySpread" + index), 1);
        }
        render(output, SIZE, SIZE);
        background(pixel(output[0], SIZE / 2, SIZE / 2), "zero sky strength");
        // 0.8强度、整片宽度：正对爆心的屏幕中心接近满值，画面四角也必须有底光。
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightSkyColorIntensity0"), 1, 0.7F, 0.2F, 0.8F);
        render(output, SIZE, SIZE);
        float skyCenter = pixel(output[0], SIZE / 2, SIZE / 2)[0];
        // 目标只有16×16，(8,8)就是屏幕中心；四角取(0,0)才有偏离爆心的视线方向。
        float skyCornerWide = pixel(output[0], 0, 0)[0];
        require(Math.abs(skyCenter - composite(BACKGROUND[0], 0.8F)) < 0.02F,
                "sky scatter peak expected=" + composite(BACKGROUND[0], 0.8F) + " actual=" + skyCenter);
        require(skyCornerWide > BACKGROUND[0] + 0.02F, "wide sky scatter must tint the whole sky, not only the blast direction");
        // 收拢到0宽度：同一角落明显变暗，中心保持最亮，形成向爆心收缩的观感。
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "LightSkySpread0"), 0);
        render(output, SIZE, SIZE);
        float skyCornerNarrow = pixel(output[0], 0, 0)[0];
        require(Math.abs(pixel(output[0], SIZE / 2, SIZE / 2)[0] - skyCenter) < 0.01F,
                "contraction must keep the blast direction bright");
        require(skyCornerNarrow < skyCornerWide - 0.02F,
                "contraction must darken the sky away from the blast; wide=" + skyCornerWide + " narrow=" + skyCornerNarrow);
        require(skyCornerNarrow > BACKGROUND[0], "contraction must keep the ambient sky floor");
        // 表面像素不受天空项影响：墙面在天空强度拉满时仍保持原画面。
        int skyWall = depth(SIZE, SIZE, 5, false);
        sampler(shader, "SceneDepth", 1, skyWall);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "LightSkySpread0"), 1);
        render(output, SIZE, SIZE);
        background(pixel(output[0], SIZE / 2, SIZE / 2), "sky scatter must not touch surfaces");
        for (int index = 0; index < 8; index++) {
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightSkyColorIntensity" + index), 0, 0, 0, 0);
            GL20.glUniform1f(GL20.glGetUniformLocation(shader, "LightSkySpread" + index), 0);
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "LightCount"), 0);
        System.out.println("PASS sky scatter tints the whole sky, contracts toward the blast and never touches surfaces");

        sampler(shader, "SceneDepth", 1, sky);
        lights(shader, 1, 0, 0, -3, 2, 1, 0, 1);
        render(output, SIZE, SIZE);
        require(pixel(output[0], SIZE / 2, SIZE / 2)[0] > BACKGROUND[0], "optional volume missing in visible air");
        int frontWall = depth(SIZE, SIZE, 1, false);
        sampler(shader, "SceneDepth", 1, frontWall);
        render(output, SIZE, SIZE);
        background(pixel(output[0], SIZE / 2, SIZE / 2), "volume behind foreground wall");
        System.out.println("PASS optional volume integrates visible ray segment and respects foreground wall");
    }

    /** 执行真实半分辨率提取、两次模糊和最终合成，检查光晕没有改变透明度。 */
    /** 直接回读生产材质，确认半球轴只裁剪背面，清零后普通全向灯完全恢复。 */
    private static void testHemisphereLight(int shader) {
        int scene = color(SIZE, SIZE, BACKGROUND);
        int sceneDepth = depth(SIZE, SIZE, 5, false);
        int[] output = target(SIZE, SIZE);
        GL20.glUseProgram(shader);
        sampler(shader, "SceneColor", 0, scene);
        sampler(shader, "SceneDepth", 1, sceneDepth);
        uniforms(shader);
        lights(shader, 1, 0, 0, -3, 5, 1, 1, 0);
        int axis = GL20.glGetUniformLocation(shader, "LightHemisphereAxis0");
        require(axis >= 0, "production hemisphere uniform missing");
        GL20.glUniform4f(axis, 0, 0, -1, 1);
        render(output, SIZE, SIZE);
        float[] front = pixel(output[0], 8, 8);
        require(front[0] > BACKGROUND[0] + 0.1F, "front hemisphere did not receive light");
        GL20.glUniform4f(axis, 0, 0, 1, 1);
        render(output, SIZE, SIZE);
        background(pixel(output[0], 8, 8), "back hemisphere must stay unlit");
        GL20.glUniform4f(axis, 0, 0, 0, 0);
        render(output, SIZE, SIZE);
        near(front[0], pixel(output[0], 8, 8)[0], "zero axis restores omnidirectional light");
        System.out.println("PASS front/back hemisphere and unchanged omnidirectional light");
    }

    private static void testBloom(int light, int bloom) {
        int scene = color(SIZE, SIZE, BACKGROUND);
        int depth = depth(SIZE, SIZE, 5, false);
        int[] contribution = target(SIZE, SIZE);
        int[] halfA = target(SIZE / 2, SIZE / 2);
        int[] halfB = target(SIZE / 2, SIZE / 2);
        int[] result = target(SIZE, SIZE);
        GL20.glUseProgram(light);
        sampler(light, "SceneColor", 0, scene);
        sampler(light, "SceneDepth", 1, depth);
        uniforms(light);
        lights(light, 1, 0, 0, -3, 5, 4, 1, 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(light, "OutputMode"), 1);
        render(contribution, SIZE, SIZE);
        require(pixel(contribution[0], 8, 8)[0] > 1, "floating point light contribution was clipped");
        GL20.glUseProgram(bloom);
        sampler(bloom, "InputTexture", 0, contribution[1]);
        sampler(bloom, "SceneColor", 1, scene);
        sampler(bloom, "BloomTexture", 2, contribution[1]);
        GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 0);
        render(halfA, SIZE / 2, SIZE / 2);
        require(pixel(halfA[0], 4, 4)[0] > 0, "bloom extraction lost bright area");
        GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 1);
        GL20.glUniform2f(GL20.glGetUniformLocation(bloom, "Direction"), 2F / SIZE, 0);
        sampler(bloom, "InputTexture", 0, halfA[1]);
        render(halfB, SIZE / 2, SIZE / 2);
        GL20.glUniform2f(GL20.glGetUniformLocation(bloom, "Direction"), 0, 2F / SIZE);
        sampler(bloom, "InputTexture", 0, halfB[1]);
        render(halfA, SIZE / 2, SIZE / 2);
        require(pixel(halfA[0], 4, 4)[0] > 0, "bloom blur lost energy");
        GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 2);
        sampler(bloom, "InputTexture", 0, contribution[1]);
        sampler(bloom, "BloomTexture", 2, halfA[1]);
        render(result, SIZE, SIZE);
        float[] combined = pixel(result[0], 8, 8);
        require(combined[0] > BACKGROUND[0] && combined[0] <= 1.0005F, "bloom composite is missing or exceeds display range");
        near(BACKGROUND[3], combined[3], "bloom alpha");
        System.out.println("PASS HDR contribution + half-size extraction + horizontal/vertical blur + final composite");

        // 显式关闭 Bloom 的光仍产生基础受光，但不允许它的亮部进入光晕。
        GL20.glUseProgram(light);
        sampler(light, "SceneColor", 0, scene);
        sampler(light, "SceneDepth", 1, depth);
        GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightShape0"), 1, 0, 0, 0);
        render(contribution, SIZE, SIZE);
        GL20.glUseProgram(bloom);
        sampler(bloom, "InputTexture", 0, contribution[1]);
        GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 0);
        render(halfA, SIZE / 2, SIZE / 2);
        near(0, pixel(halfA[0], 4, 4)[0], "disabled bloom extraction");
        System.out.println("PASS per-light Bloom disable preserves light and suppresses glow");
    }

    /** 实际生产程序验证环境混合、单调过渡、各受光层与自发光主体的边界。 */
    private static void testAmbientLighting(Path shaders) throws Exception {
        int light = program(shaders, "screen_light_occlusion");
        int smoke = program(shaders, "smoke_light_occlusion");
        int plume = program(shaders, "rocket_plume_ranges");
        int smokeVao = 0, smokeVbo = 0;
        try {
            int[] output = target(SIZE, SIZE);
            int sky = depth(SIZE, SIZE, 0, false), surface = depth(SIZE, SIZE, 5, false);
            GL30.glBindVertexArray(vao);
            GL20.glUseProgram(light); uniforms(light);
            sampler(light, "SceneDepth", 1, surface); sampler(light, "DistantDepth", 2, sky);
            lights(light, 1, 0, 0, -3, 5, 1, 1, 0);
            GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightShape0"), 1, 0, 1, 2);
            // 每行依次为游戏亮度、画面亮度、游戏权重、保留比例、曲线指数、期望倍率。
            float[][] cases = {
                    {0, 0, .7F, .1F, 1, 1}, {.2F, .2F, .7F, .1F, 1, 1},
                    {1, 1, .7F, .1F, 1, .1F}, {.5F, .5F, .7F, .1F, 1, .55F},
                    {.5F, .5F, .7F, .1F, 2, .775F}, {.5F, .5F, .7F, .1F, .5F, .363604F},
                    {-1, .5F, .9F, .1F, 1, .55F}, {1, 0, 0, .1F, 1, 1},
                    {1, 0, 1, .1F, 1, .1F}, {0, 1, .7F, .1F, 1, .933333F},
                    {1, 1, .7F, 1, 1, 1}
            };
            for (float[] item : cases) {
                float baseline = ambientPixel(light, output, 3, false, item[0], item[1], item[2], item[3], item[4])[0];
                float actual = ambientPixel(light, output, 3, true, item[0], item[1], item[2], item[3], item[4])[0];
                require(baseline > .01F, "ambient halo reference must be visible");
                near(item[5], actual / baseline, "production ambient mixture/curve/fallback");
            }
            // 中心为黑、周围四点为0.625，五点均值必须是0.5，不能只看中心像素。
            float kernelReference = ambientPixel(light, output, 3, false, -1, 0, 0, .1F, 1)[0];
            float[] neighborhood = new float[SIZE * SIZE * 4];
            for (int[] point : new int[][] {{6,8}, {10,8}, {8,6}, {8,10}}) {
                for (int channel = 0; channel < 3; channel++) { neighborhood[(point[1] * SIZE + point[0]) * 4 + channel] = .625F; }
            }
            sampler(light, "SceneColor", 0, texture(SIZE, SIZE, GL30.GL_RGBA16F, GL11.GL_RGBA, neighborhood));
            GL20.glUniform4f(GL20.glGetUniformLocation(light, "AmbientSettings"), 1, 0, .1F, 1);
            render(output, SIZE, SIZE);
            near(.55F, pixel(output[0], 8, 8)[0] / kernelReference, "five-point scene brightness average");
            float previous = Float.POSITIVE_INFINITY;
            for (int step = 0; step <= 100; step++) {
                float actual = ambientPixel(light, output, 3, true, step / 100F, .5F, 1, .1F, 1)[0];
                require(actual <= previous + .0001F, "ambient response is not monotonic");
                if (step > 0) { require(previous - actual < .03F, "ambient transition jumped"); }
                previous = actual;
            }
            // 八灯每个槽位各自提供明暗值，不能因为共享画面采样而混用来源数据。
            lights(light, 8, 0, 0, -3, 5, 1, 1, 0);
            for (int index = 0; index < 8; index++) {
                GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightColorIntensity" + index), 1, .7F, .2F, 0);
                GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightShape" + index), 1, 0, 1, 2);
                GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightHaloColorIntensity" + index), 1, .7F, .2F, 0);
            }
            for (int active = 0; active < 8; active++) {
                GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightHaloColorIntensity" + active), 1, .7F, .2F, 1);
                float baseline = ambientPixel(light, output, 3, false, 1, 0, 1, .1F, 1)[0];
                GL20.glUniform1f(GL20.glGetUniformLocation(light, "LightAmbient" + active), 1);
                // 本次不再走会重写第0槽的辅助入口，直接绘制检查当前活动槽。
                GL20.glUniform4f(GL20.glGetUniformLocation(light, "AmbientSettings"), 1, 1, .1F, 1);
                render(output, SIZE, SIZE);
                near(.1F, pixel(output[0], 8, 8)[0] / baseline, "ambient world value lost in light slot " + active);
                GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightHaloColorIntensity" + active), 1, .7F, .2F, 0);
            }
            // 表面和天空只对其输入乘一次倍率，保留0.1而非重复压成0.01。
            lights(light, 1, 0, 0, -3, 5, 1, 1, 0);
            float raw = ambientPixel(light, output, 1, false, 1, 1, .7F, .1F, 1)[0];
            require(raw > .01F, "ambient surface reference must be lit");
            near(.1F, ambientPixel(light, output, 1, true, 1, 1, .7F, .1F, 1)[0] / raw,
                    "surface intensity must adapt exactly once");
            sampler(light, "SceneDepth", 1, sky);
            GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightSkyColorIntensity0"), 1, .7F, .2F, 1);
            GL20.glUniform1f(GL20.glGetUniformLocation(light, "LightSkySpread0"), 1);
            raw = ambientPixel(light, output, 1, false, 1, 1, .7F, .1F, 1)[0];
            near(.1F, ambientPixel(light, output, 1, true, 1, 1, .7F, .1F, 1)[0] / raw, "sky intensity adaptation");
            // 核心和贴身光团单独开关，两路都要在基础贡献及独立显示模式保持原亮度。
            GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightSkyColorIntensity0"), 0, 0, 0, 0);
            // 基础模式会跳过强度为0的灯，故保持灯有效，只把表面和空气散射权重清零。
            GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightColorIntensity0"), 1, .7F, .2F, 1);
            GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightShape0"), 0, 0, 0, 0);
            for (int component = 0; component < 2; component++) {
                GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightEmission0"), .8F, 2, component == 0 ? 4 : 0, component == 1 ? 2 : 0);
                for (int mode : new int[] {1, 2}) {
                    float[] before = ambientPixel(light, output, mode, false, 1, 1, .7F, .1F, 1);
                    float[] after = ambientPixel(light, output, mode, true, 1, 1, .7F, .1F, 1);
                    require(before[0] > .01F, "emissive reference disappeared");
                    for (int channel = 0; channel < 4; channel++) { near(before[channel], after[channel], "core/close glow changed"); }
                }
            }
            System.out.println("PASS ambient production pixels: dark=100%, bright=10%, 70/30 mix, missing-world fallback, curves, continuous monotonic response, eight source slots, surface/halo/sky, unchanged core and close glow");

            // 粒子烟使用真实Position/UV/Color输入；大三角形位于相机前3格。
            smokeVao = GL30.glGenVertexArrays(); smokeVbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(smokeVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, smokeVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-3,-3,-3,0,0, 9,-3,-3,2,0, -3,9,-3,0,2}, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 20, 0);
            GL20.glEnableVertexAttribArray(1); GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 12);
            GL20.glDisableVertexAttribArray(2); GL20.glVertexAttrib4f(2, 1, 1, 1, 1);
            GL20.glUseProgram(smoke); uniforms(smoke);
            matrix(smoke, "SmokeView", new float[] {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1});
            sampler(smoke, "SceneDepth", 1, sky); sampler(smoke, "DistantDepth", 2, sky);
            sampler(smoke, "Sampler0", 3, color(SIZE, SIZE, new float[] {1,1,1,.5F}));
            lights(smoke, 1, 0, 0, -2, 5, .5F, 1, 0);
            float[] smokeOff = ambientPixel(smoke, output, 0, false, 1, 1, .7F, .1F, 1);
            float[] smokeOn = ambientPixel(smoke, output, 0, true, 1, 1, .7F, .1F, 1);
            require(smokeOff[0] > .01F, "ambient smoke reference must be lit");
            near(.5F * (1 - (float) Math.pow(1 - smokeOff[0] / .5F, .1)), smokeOn[0], "smoke input adapted before display response");
            near(0, smokeOn[3], "ambient smoke alpha");
            System.out.println("PASS ambient particle-smoke input reduced once; transparent output preserved");

            GL30.glBindVertexArray(vao);
            GL20.glUseProgram(plume); uniforms(plume);
            sampler(plume, "SceneDepth", 1, sky); sampler(plume, "DistantDepth", 2, sky);
            sampler(plume, "PlumeVisibility", 3, color(1, 1, new float[] {1,0,0,1}));
            plumeGeometry(plume, -.5F, 0, -3, 1, 0, 0, 2, .7F);
            GL20.glUniform4f(GL20.glGetUniformLocation(plume, "PlumeEmission"), 60000, 5, .2F, 12);
            GL20.glUniform1f(GL20.glGetUniformLocation(plume, "PlumeHaloIntensity"), .4F);
            GL20.glUniform1i(GL20.glGetUniformLocation(plume, "HaloEnabled"), 1);
            for (int mode : new int[] {0, 1, 2, 3, 4}) {
                float[] before = ambientPixel(plume, output, mode, false, 1, 1, .7F, .1F, 1);
                float[] after = ambientPixel(plume, output, mode, true, 1, 1, .7F, .1F, 1);
                require(before[0] > .0001F, "ambient plume reference must be visible in mode " + mode);
                if (mode == 1) { near(.1F, after[0] / before[0], "plume bloom input reduced once"); }
                else if (mode == 4) { near(1 - (float) Math.pow(1 - before[0], .1), after[0], "world halo intensity adapted before display"); }
                else {
                    for (int channel = 0; channel < 4; channel++) { near(before[channel], after[channel], "plume body/visibility changed"); }
                }
            }
            System.out.println("PASS ambient engine broad halo and bloom adapt; plume radiance, displayed flame and visibility remain unchanged");
        } finally {
            GL20.glUseProgram(0); GL30.glBindVertexArray(vao);
            if (smokeVbo != 0) { GL15.glDeleteBuffers(smokeVbo); }
            if (smokeVao != 0) { GL30.glDeleteVertexArrays(smokeVao); }
            GL20.glDeleteProgram(light); GL20.glDeleteProgram(smoke); GL20.glDeleteProgram(plume);
        }
    }

    /** 对同一生产通道切换环境适应；背景使用独立只读纹理，回读仅发生在诊断中。 */
    private static float[] ambientPixel(int shader, int[] target, int mode, boolean enabled,
            float world, float scene, float weight, float minimum, float curve) {
        GL20.glUseProgram(shader);
        sampler(shader, "SceneColor", 0, color(SIZE, SIZE, new float[] {scene, scene, scene, .37F}));
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "AmbientSettings"), enabled ? 1 : 0, weight, minimum, curve);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "LightAmbient0"), world);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeAmbient"), world);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), mode);
        // 调用实际生产程序和浮点回读，不在测试中替换光照算法。
        render(target, SIZE, SIZE);
        return pixel(target[0], 8, 8);
    }

    /** 生产程序需要的固定矩阵与采样分辨率。 */
    private static void uniforms(int program) {
        // 调用新增整数几何采样器的有效空输入；旧算法回归模式保持0，不读取空几何。
        ScreenLightShaderSources.bindEmptyGeometry(program);
        float[] projection = {1,0,0,0, 0,1,0,0, 0,0,A,-1, 0,0,B,0};
        float[] inverse = {1,0,0,0, 0,1,0,0, 0,0,0,1/B, 0,0,-1,A/B};
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "Projection"), false, projection);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "InverseProjection"), false, inverse);
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "ScreenSize"), SIZE, SIZE);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "OutputMode"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "DistantDepth"), 2);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "HasDistantDepth"), 0);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "NearEmptyDepth"), 1);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "DistantEmptyDepth"), 1);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "HandDepthLimit"), 0);
        // 原生 GLSL 编译不读取描述 JSON，显式还原喷焰资源的默认光晕范围。
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "PlumeHaloRadius"), 20);
        for (int index = 0; index < 8; index++) {
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "LightEmission" + index), 0, 0, 0, 0);
        }
        float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        matrix(program, "DistantToView", identity);
        matrix(program, "ViewToDistant", identity);
    }

    /** 上传列主序矩阵，与 ShaderInstance 的矩阵约定一致。 */
    private static void matrix(int shader, String name, float[] values) {
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(shader, name), false, values);
    }

    /** 使用真实 Java 发动机预设，验证天空高亮、橙色光晕、遮挡和最大八灯 HDR 数值。 */
    private static void testEngineEmission(int light, int bloom) {
        int scene = color(SIZE, SIZE, BACKGROUND);
        int sky = depth(SIZE, SIZE, 0, false);
        int[] hdr = target(SIZE, SIZE);
        int[] display = target(SIZE, SIZE);
        int[] halfA = target(SIZE / 2, SIZE / 2);
        int[] halfB = target(SIZE / 2, SIZE / 2);
        GL20.glUseProgram(light);
        uniforms(light);
        sampler(light, "SceneColor", 0, scene);
        sampler(light, "SceneDepth", 1, sky);
        sampler(light, "DistantDepth", 2, sky);
        // 调用生产代码的预设与发光生成，不能在测试里复制一套较亮的假参数。
        RVP_LightParameters engine = RVP_LightParameters.engine();
        engineLights(light, engine, 1, 1);
        GL20.glUniform1i(GL20.glGetUniformLocation(light, "OutputMode"), 1);
        render(hdr, SIZE, SIZE);
        float[] first = pixel(hdr[0], 8, 8);
        require(first[0] >= 59990 && first[0] <= 60001, "real default engine did not reach its 60000 safe HDR ceiling in empty sky");
        require(first[0] > first[1] && first[1] > first[2] && first[3] > 1000, "orange HDR energy or bloom eligibility lost");
        System.out.println("PASS real engine preset reaches 60000 safe HDR ceiling in empty sky with volumetric scattering disabled");

        GL20.glUniform1i(GL20.glGetUniformLocation(light, "OutputMode"), 0);
        render(display, SIZE, SIZE);
        float[] beforeBloom = pixel(display[0], 1, 8);
        // 调用生产 Shader 的提取、两次加宽模糊和最终合成，检查低分辨率下也能稳定提取核心。
        GL20.glUseProgram(bloom);
        sampler(bloom, "InputTexture", 0, hdr[1]);
        sampler(bloom, "SceneColor", 1, scene);
        sampler(bloom, "BloomTexture", 2, hdr[1]);
        GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 0);
        render(halfA, SIZE / 2, SIZE / 2);
        require(pixel(halfA[0], 4, 4)[0] > 1, "engine core was not extracted into bloom");
        GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 1);
        GL20.glUniform2f(GL20.glGetUniformLocation(bloom, "Direction"), 4F / SIZE, 0);
        sampler(bloom, "InputTexture", 0, halfA[1]);
        render(halfB, SIZE / 2, SIZE / 2);
        GL20.glUniform2f(GL20.glGetUniformLocation(bloom, "Direction"), 0, 4F / SIZE);
        sampler(bloom, "InputTexture", 0, halfB[1]);
        render(halfA, SIZE / 2, SIZE / 2);
        GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 2);
        sampler(bloom, "InputTexture", 0, hdr[1]);
        sampler(bloom, "BloomTexture", 2, halfA[1]);
        render(display, SIZE, SIZE);
        float[] halo = pixel(display[0], 1, 8);
        require(halo[0] > beforeBloom[0] + 0.02F && halo[0] > halo[1] && halo[1] > halo[2], "strong orange halo missing or washed out white");
        near(BACKGROUND[3], halo[3], "engine bloom alpha");
        System.out.println("PASS engine bright extraction and widened blur produce strong orange halo while preserving scene alpha");

        GL20.glUseProgram(light);
        sampler(light, "SceneColor", 0, scene);
        sampler(light, "SceneDepth", 1, sky);
        sampler(light, "DistantDepth", 2, sky);
        GL20.glUniform1i(GL20.glGetUniformLocation(light, "OutputMode"), 0);
        render(display, SIZE, SIZE);
        emissionOverlay(light, display);
        float[] whiteCore = pixel(display[0], 8, 8);
        require(whiteCore[0] > 0.999F && whiteCore[1] > 0.999F && whiteCore[2] > 0.999F, "engine core still limited by legacy 0.9 exposure ceiling");
        near(BACKGROUND[3], whiteCore[3], "peak core alpha");
        System.out.println("PASS independent engine core reaches full display white even with Bloom disabled; alpha preserved");

        // 极远喷口落在四个像素之间时仍应有稳定覆盖，不能因为低于一个像素而丢失自发光。
        GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightPositionRadius0"), 0, 0, -1000, engine.radius());
        GL20.glUniform1i(GL20.glGetUniformLocation(light, "OutputMode"), 1);
        render(hdr, SIZE, SIZE);
        require(pixel(hdr[0], 8, 8)[0] > 10000, "distant subpixel nozzle disappeared between samples");
        engineLights(light, engine, 1, 1);
        System.out.println("PASS 1000-block nozzle retains bright subpixel coverage beyond the normal scene far plane");

        int frontWall = depth(SIZE, SIZE, 1, false);
        sampler(light, "SceneDepth", 1, frontWall);
        GL20.glUniform1i(GL20.glGetUniformLocation(light, "OutputMode"), 1);
        render(hdr, SIZE, SIZE);
        zero(pixel(hdr[0], 8, 8), "engine behind near wall");
        zero(pixel(hdr[0], 1, 8), "engine halo leaking around hidden source");
        int distantWall = projectedDepth(SIZE, SIZE, 2, 0.5F, 512, false, false);
        sampler(light, "SceneDepth", 1, sky);
        sampler(light, "DistantDepth", 2, distantWall);
        distantProjection(light, 0.5F, 512, false, 1);
        render(hdr, SIZE, SIZE);
        zero(pixel(hdr[0], 8, 8), "engine behind DH wall");
        int reverseWall = projectedDepth(SIZE, SIZE, 2, 0.5F, 512, true, false);
        sampler(light, "DistantDepth", 2, reverseWall);
        distantProjection(light, 0.5F, 512, true, 1);
        render(hdr, SIZE, SIZE);
        zero(pixel(hdr[0], 8, 8), "engine behind reverse-Z DH wall");
        System.out.println("PASS hidden nozzle core and halo are rejected by both near and DH foreground depth");

        GL20.glUniform1i(GL20.glGetUniformLocation(light, "HasDistantDepth"), 0);
        sampler(light, "DistantDepth", 2, sky);
        engineLights(light, engine, 8, 2);
        render(hdr, SIZE, SIZE);
        float[] maximum = pixel(hdr[0], 8, 8);
        require(maximum[0] >= 50000 && maximum[0] <= 60001, "eight HDR engines exceeded finite texture budget or were dimmed unnecessarily");
        // 调用真实二脉冲包络，喷口 HDR 不能绕过真实推进阶段。
        engineLights(light, RVP_LightParameters.enginePhase(engine, true, 100), 1, 1);
        render(hdr, SIZE, SIZE);
        require(pixel(hdr[0], 8, 8)[0] < first[0], "second pulse core did not decrease");
        engineLights(light, engine, 1, 0);
        render(hdr, SIZE, SIZE);
        zero(pixel(hdr[0], 8, 8), "global zero intensity");
        System.out.println("PASS eight engines at maximum global intensity remain finite; second pulse and global-off propagate to source glow");

        float[] handDepth = new float[SIZE * SIZE];
        java.util.Arrays.fill(handDepth, 0.55F);
        int hand = texture(SIZE, SIZE, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, handDepth);
        sampler(light, "SceneDepth", 1, hand);
        GL20.glUniform1f(GL20.glGetUniformLocation(light, "HandDepthLimit"), 0.5625F);
        engineLights(light, engine, 1, 1);
        GL20.glUniform1i(GL20.glGetUniformLocation(light, "OutputMode"), 0);
        render(display, SIZE, SIZE);
        emissionOverlay(light, display);
        background(pixel(display[0], 8, 8), "emissive overlay altered shader-rendered hand");
        GL20.glUniform1f(GL20.glGetUniformLocation(light, "HandDepthLimit"), 0);
        sampler(light, "SceneDepth", 1, sky);
        GL20.glUniform1i(GL20.glGetUniformLocation(light, "OutputMode"), 1);
        System.out.println("PASS shader hand mask remains intact during independent high-brightness overlay");

        engineLights(light, engine, 1, 1);
        GL20.glUniform4f(GL20.glGetUniformLocation(light, "LightShape0"), engine.surfaceWeight(), 0, 0, 0);
        render(hdr, SIZE, SIZE);
        GL20.glUseProgram(bloom);
        sampler(bloom, "InputTexture", 0, hdr[1]);
        GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 0);
        render(halfA, SIZE / 2, SIZE / 2);
        zero(pixel(halfA[0], 4, 4), "per-engine disabled bloom");
        System.out.println("PASS per-engine Bloom disable suppresses extracted halo without removing emissive core");
        int weakEdge = color(SIZE, SIZE, new float[] {0.2F, 0.096F, 0.024F, 0.112912F});
        sampler(bloom, "InputTexture", 0, weakEdge);
        render(halfA, SIZE / 2, SIZE / 2);
        require(pixel(halfA[0], 4, 4)[0] > 0.01F, "new soft threshold rejected weak orange edge below the old 0.55 threshold");
        System.out.println("PASS lowered soft bloom threshold extracts weak orange edges that the former 0.55 threshold discarded");
    }

    /** 新程序喷流实际像素：形状、缩放、近景/DH/手持物遮挡、亚像素和橙色光晕。 */
    private static void testRocketPlume(Path root, int plume, int bloom) throws Exception {
        int size = 256;
        int scene = color(size, size, BACKGROUND);
        int sky = depth(size, size, 0, false);
        int[] hdr = target(size, size);
        int[] display = target(size, size);
        int[] halfA = target(size / 2, size / 2);
        int[] halfB = target(size / 2, size / 2);
        GL20.glUseProgram(plume); uniforms(plume);
        GL20.glUniform2f(GL20.glGetUniformLocation(plume, "ScreenSize"), size, size);
        sampler(plume, "SceneDepth", 1, sky); sampler(plume, "DistantDepth", 2, sky);
        GL20.glUniform4f(GL20.glGetUniformLocation(plume, "PlumeEmission"), 60000, 16, 0.2F, 12);
        GL20.glUniform1f(GL20.glGetUniformLocation(plume, "Time"), 0.7F);
        plumeGeometry(plume, -1.5F, 0, -6, 1, 0, 0, 3, 0.45F);
        GL20.glUniform1i(GL20.glGetUniformLocation(plume, "OutputMode"), 0);
        render(hdr, size, size);
        float[] first = pixels(hdr[0], size, size);
        int[] bounds = brightBounds(first, size);
        require(bounds[2] - bounds[0] > 3 * (bounds[3] - bounds[1]), "new plume still looks like a round source");
        float peak = 0;
        for (int index = 0; index < first.length; index += 4) { peak = Math.max(peak, first[index]); }
        require(peak > 50000 && peak <= 60000, "new plume high-brightness core missing or overflowing");
        plumeGeometry(plume, -3, 0, -6, 1, 0, 0, 6, 0.9F);
        render(hdr, size, size);
        int[] doubled = brightBounds(pixels(hdr[0], size, size), size);
        require(doubled[2] - doubled[0] > (bounds[2] - bounds[0]) * 1.8F, "JSON scale did not double plume length");
        require(doubled[3] - doubled[1] > (bounds[3] - bounds[1]) * 1.7F, "JSON scale did not double plume width");
        System.out.println("PASS RVP plume is elongated and tapered; scale doubles length and width; HDR peak=" + peak);

        plumeGeometry(plume, -1.5F, 0, -6, 1, 0, 0, 3, 0.45F);
        int wall = depth(size, size, 4, false);
        sampler(plume, "SceneDepth", 1, wall); render(hdr, size, size);
        require(brightBounds(pixels(hdr[0], size, size), size)[2] < 0, "near wall did not hide plume");
        sampler(plume, "SceneDepth", 1, sky);
        for (boolean reverse : new boolean[] {false, true}) {
            int dh = projectedDepth(size / 2, size / 2, 4, 0.5F, 512, reverse, false);
            sampler(plume, "DistantDepth", 2, dh); distantProjection(plume, 0.5F, 512, reverse, 1);
            render(hdr, size, size);
            require(brightBounds(pixels(hdr[0], size, size), size)[2] < 0, "DH foreground wall did not hide plume");
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(plume, "HasDistantDepth"), 0);
        sampler(plume, "DistantDepth", 2, sky);
        float[] partialDepth = new float[size * size]; java.util.Arrays.fill(partialDepth, 1);
        for (int y = 0; y < size; y++) { for (int x = 0; x < 123; x++) { partialDepth[y * size + x] = depthValue(4); } }
        int partialWall = texture(size, size, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, partialDepth);
        sampler(plume, "SceneDepth", 1, partialWall); render(hdr, size, size);
        require(pixel(hdr[0], 110, 128)[0] == 0 && pixel(hdr[0], 140, 128)[0] > 1,
                "hidden nozzle incorrectly removed visible tail or plume leaked through wall");
        sampler(plume, "SceneDepth", 1, sky);
        plumeGeometry(plume, 0, 0, -4096, 0, 0, -1, 3, 0.45F);
        render(hdr, size, size);
        require(pixel(hdr[0], 128, 128)[0] > 1000, "distant subpixel plume disappeared beyond scene far plane");
        float[] handValues = new float[size * size]; java.util.Arrays.fill(handValues, 0.55F);
        int hand = texture(size, size, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, handValues);
        sampler(plume, "SceneDepth", 1, hand);
        GL20.glUniform1f(GL20.glGetUniformLocation(plume, "HandDepthLimit"), 0.5625F);
        render(hdr, size, size);
        require(brightBounds(pixels(hdr[0], size, size), size)[2] < 0, "shader hand mask missing for plume");
        GL20.glUniform1f(GL20.glGetUniformLocation(plume, "HandDepthLimit"), 0);
        sampler(plume, "SceneDepth", 1, sky);
        System.out.println("PASS plume near/DH normal+reverse depth, half-resolution DH, exposed tail, 4096-block coverage and shader hand mask");

        java.awt.image.BufferedImage preview = new java.awt.image.BufferedImage(size * 2, size * 2,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        String[] labels = {"SIDE / scale 0.3", "REAR / scale 0.3", "ANGLED / scale 0.3", "SIDE / scale 0.6"};
        for (int view = 0; view < 4; view++) {
            GL20.glUseProgram(plume);
            if (view == 0) { plumeGeometry(plume, -1.5F, 0, -6, 1, 0, 0, 3, 0.45F); }
            if (view == 1) { plumeGeometry(plume, 0, 0, -6, 0, 0, 1, 3, 0.45F); }
            if (view == 2) { plumeGeometry(plume, -1, 0, -6, 0.707107F, 0, 0.707107F, 3, 0.45F); }
            if (view == 3) { plumeGeometry(plume, -3, 0, -6, 1, 0, 0, 6, 0.9F); }
            GL20.glUniform1i(GL20.glGetUniformLocation(plume, "OutputMode"), 1);
            render(hdr, size, size);
            GL20.glUseProgram(bloom);
            sampler(bloom, "InputTexture", 0, hdr[1]); sampler(bloom, "SceneColor", 1, scene); sampler(bloom, "BloomTexture", 2, hdr[1]);
            GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 0); render(halfA, size / 2, size / 2);
            GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 1);
            GL20.glUniform2f(GL20.glGetUniformLocation(bloom, "Direction"), 2F / size, 0);
            sampler(bloom, "InputTexture", 0, halfA[1]); render(halfB, size / 2, size / 2);
            GL20.glUniform2f(GL20.glGetUniformLocation(bloom, "Direction"), 0, 2F / size);
            sampler(bloom, "InputTexture", 0, halfB[1]); render(halfA, size / 2, size / 2);
            GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 2);
            sampler(bloom, "InputTexture", 0, hdr[1]); sampler(bloom, "BloomTexture", 2, halfA[1]); render(display, size, size);
            GL20.glUseProgram(plume); GL20.glUniform1i(GL20.glGetUniformLocation(plume, "OutputMode"), 2);
            sampler(plume, "SceneDepth", 1, sky); sampler(plume, "DistantDepth", 2, sky);
            GL11.glEnable(GL11.GL_BLEND); GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL14.glBlendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
            render(display, size, size); GL11.glDisable(GL11.GL_BLEND);
            float[] image = pixels(display[0], size, size);
            boolean white = false; boolean orangeHalo = false;
            float[] source = pixels(hdr[0], size, size);
            for (int y = 0; y < size; y++) { for (int x = 0; x < size; x++) {
                int index = (y * size + x) * 4;
                near(BACKGROUND[3], image[index + 3], "plume preserves scene alpha");
                white |= image[index] > 0.99F && image[index + 1] > 0.99F && image[index + 2] > 0.99F;
                orangeHalo |= source[index] < 0.001F && image[index] > BACKGROUND[0] + 0.1F
                        && image[index] > image[index + 1] && image[index + 1] > image[index + 2];
                int red = Math.round(Math.min(1, Math.max(0, image[index])) * 255);
                int green = Math.round(Math.min(1, Math.max(0, image[index + 1])) * 255);
                int blue = Math.round(Math.min(1, Math.max(0, image[index + 2])) * 255);
                preview.setRGB(view % 2 * size + x, view / 2 * size + size - 1 - y, red << 16 | green << 8 | blue);
            } }
            require(white && orangeHalo, "plume white core or orange external bloom missing: " + labels[view]);
            java.awt.Graphics2D graphics = preview.createGraphics();
            graphics.setColor(java.awt.Color.WHITE); graphics.drawString(labels[view], view % 2 * size + 12, view / 2 * size + 22); graphics.dispose();
        }
        Path directory = root.resolve("build/rocket-plume-gpu-regression"); Files.createDirectories(directory);
        javax.imageio.ImageIO.write(preview, "png", directory.resolve("preview.png").toFile());
        System.out.println("PASS RVP side/rear/angled/scaled plume core and orange Bloom; Alpha preserved; preview=" + directory.resolve("preview.png"));
    }

    /** 上传和生产一致的观察空间喷口/方向/缩放尺寸。 */
    private static void plumeGeometry(int program, float x, float y, float z, float dx, float dy, float dz, float length, float radius) {
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "PlumeOriginLength"), x, y, z, length);
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "PlumeAxisRadius"), dx, dy, dz, radius);
    }

    /** 生产广域光晕两个模式：一像素可见度和按世界半径扩散；近景/DH/手持物均使用真实深度输入。 */
    private static void testWorldEngineHalo(Path root, int shader) throws Exception {
        int size = 256;
        int sky = depth(size, size, 0, false);
        int[] visible = target(1, 1), output = target(size, size);
        GL20.glUseProgram(shader); uniforms(shader);
        GL20.glUniform2f(GL20.glGetUniformLocation(shader, "ScreenSize"), size, size);
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "PlumeEmission"), 60000, 16, 0.2F, 12);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloIntensity"), 16);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "Time"), 0.7F);
        sampler(shader, "SceneDepth", 1, sky); sampler(shader, "DistantDepth", 2, sky);
        plumeGeometry(shader, 0, 0, -80, 1, 0, 0, 3, .45F);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloRadius"), 20);
        float fullVisibility = worldHaloDraw(shader, visible, output, sky, size);
        require(fullVisibility > .2F && fullVisibility <= 1, "one-pixel emitter visibility is missing or invalid");
        float[] inner = pixel(output[0], 144, 128);
        require(inner[0] > .1F && inner[0] > inner[1] && inner[1] > inner[2], "twenty-block orange halo did not extend beyond the thin plume");
        zero(pixel(output[0], 163, 128), "world halo escaped its twenty-block boundary");
        int[] nearBounds = brightBounds(pixels(output[0], size, size), size);
        plumeGeometry(shader, 0, 0, -160, 1, 0, 0, 3, .45F);
        worldHaloDraw(shader, visible, output, sky, size);
        int[] farBounds = brightBounds(pixels(output[0], size, size), size);
        require(nearBounds[2] - nearBounds[0] > 1.8F * (farBounds[2] - farBounds[0]), "world halo stayed at a fixed pixel radius instead of scaling with distance");
        plumeGeometry(shader, 0, 0, -80, 1, 0, 0, 3, .45F);
        matrix(shader, "Projection", new float[] {2,0,0,0, 0,2,0,0, 0,0,A,-1, 0,0,B,0});
        matrix(shader, "InverseProjection", new float[] {.5F,0,0,0, 0,.5F,0,0, 0,0,0,1/B, 0,0,-1,A/B});
        worldHaloDraw(shader, visible, output, sky, size);
        int[] zoomBounds = brightBounds(pixels(output[0], size, size), size);
        require(zoomBounds[2] - zoomBounds[0] > 1.8F * (nearBounds[2] - nearBounds[0]), "FOV zoom did not scale the world halo projection");
        matrix(shader, "Projection", new float[] {1,0,0,0, 0,1,0,0, 0,0,A,-1, 0,0,B,0});
        matrix(shader, "InverseProjection", new float[] {1,0,0,0, 0,1,0,0, 0,0,0,1/B, 0,0,-1,A/B});
        int[] halfOutput = target(size / 2, size / 2);
        GL20.glUniform2f(GL20.glGetUniformLocation(shader, "ScreenSize"), size / 2, size / 2);
        worldHaloDraw(shader, visible, halfOutput, sky, size / 2);
        int[] halfBounds = brightBounds(pixels(halfOutput[0], size / 2, size / 2), size / 2);
        require(Math.abs((nearBounds[2] - nearBounds[0]) - 2 * (halfBounds[2] - halfBounds[0])) <= 6,
                "resolution change altered world halo size instead of pixel coverage");
        GL20.glUniform2f(GL20.glGetUniformLocation(shader, "ScreenSize"), size, size);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloRadius"), 50);
        worldHaloDraw(shader, visible, output, sky, size);
        require(pixel(output[0], 168, 128)[0] > .1F, "three-hundred-mm fifty-block halo failed to expand");
        int[] fiftyBounds = brightBounds(pixels(output[0], size, size), size);
        require(fiftyBounds[2] - fiftyBounds[0] > 2.2F * (nearBounds[2] - nearBounds[0]), "caliber radius changed brightness without expanding the halo");

        // 500 格环境光与光晕不会被近景 far=100 的空天空裁掉。
        plumeGeometry(shader, 0, 0, -1000, 1, 0, 0, 3, .45F);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloRadius"), 500);
        worldHaloDraw(shader, visible, output, sky, size);
        float cap = pixel(output[0], 172, 128)[0];
        require(cap > .02F, "five-hundred-block halo disappeared beyond the near far plane");
        zero(pixel(output[0], 193, 128), "five-hundred-block halo exceeded its boundary");
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloRadius"), 8192);
        worldHaloDraw(shader, visible, output, sky, size);
        float expanded = pixel(output[0], 172, 128)[0];
        require(expanded > cap + .1F, "expanded halo radius still obeys the former 500-block cap");
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloRadius"), 10000);
        worldHaloDraw(shader, visible, output, sky, size);
        near(expanded, pixel(output[0], 172, 128)[0], "expanded shader radius cap failed");

        plumeGeometry(shader, 0, 0, -80, 1, 0, 0, 3, .45F);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloRadius"), 30);
        sampler(shader, "SceneDepth", 1, depth(size, size, 30, false));
        near(0, worldHaloDraw(shader, visible, output, sky, size), "solid foreground did not hide the actual emitter");
        zero(pixel(output[0], 128, 128), "large halo leaked around a fully hidden source");
        sampler(shader, "SceneDepth", 1, sky);
        for (boolean reverse : new boolean[] {false, true}) {
            sampler(shader, "DistantDepth", 2, projectedDepth(size / 2, size / 2, 30, 7.5F, 4096, reverse, false));
            distantProjection(shader, 7.5F, 4096, reverse, 1);
            near(0, worldHaloDraw(shader, visible, output, sky, size), "DH foreground did not hide the actual emitter");
            zero(pixel(output[0], 144, 128), "wide halo leaked around a DH-occluded source");
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "HasDistantDepth"), 0);
        sampler(shader, "DistantDepth", 2, sky);

        // 半边墙遮住喷口、另一半露出尾段：只有可见的真实喷流样点可提供光晕能量。
        float[] partial = new float[size * size];
        float wall = ((-A * 30 + B) / 30) * .5F + .5F;
        for (int y = 0; y < size; y++) { for (int x = 0; x < size; x++) { partial[y * size + x] = x < size / 2 ? wall : 1; } }
        sampler(shader, "SceneDepth", 1, texture(size, size, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, partial));
        plumeGeometry(shader, -3, 0, -80, 1, 0, 0, 6, .45F);
        float exposed = worldHaloDraw(shader, visible, output, sky, size);
        require(exposed > .01F && exposed < fullVisibility, "visible tail must retain a weaker halo when nozzle is hidden");
        sampler(shader, "SceneDepth", 1, sky);
        plumeGeometry(shader, 0, 0, -80, 1, 0, 0, 3, .45F);

        float[] hand = new float[size * size];
        for (int y = 0; y < size; y++) { for (int x = 0; x < size; x++) { hand[y * size + x] = x < size / 2 - 4 ? .55F : 1; } }
        sampler(shader, "SceneDepth", 1, texture(size, size, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, hand));
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "HandDepthLimit"), .5625F);
        require(worldHaloDraw(shader, visible, output, sky, size) > .2F, "unmasked source was incorrectly hidden by an adjacent hand");
        zero(pixel(output[0], 112, 128), "world halo painted over shader hand mask");
        require(pixel(output[0], 144, 128)[0] > .1F, "hand mask incorrectly removed halo outside its silhouette");
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "HandDepthLimit"), 0);
        sampler(shader, "SceneDepth", 1, sky);

        // 真正硬件混合保持原世界 Alpha；预览也使用同一可见度 + 广域 + 高亮核心顺序。
        worldHaloDraw(shader, visible, output, sky, size);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, output[0]);
        GL11.glClearColor(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], BACKGROUND[3]); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND); GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
        render(output, size, size);
        near(BACKGROUND[3], pixel(output[0], 144, 128)[3], "world halo changed scene alpha");
        GL11.glDisable(GL11.GL_BLEND);

        var preview = new java.awt.image.BufferedImage(size * 2, size * 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        float[][] cases = {{20,80}, {30,80}, {50,80}, {500,1000}};
        for (int index = 0; index < cases.length; index++) {
            // 对齐一个真实像素中心展示细核心，避免四宫格参考图恰好都落在两行像素之间。
            plumeGeometry(shader, 0, cases[index][1] / size, -cases[index][1], 1, 0, 0, 3, .45F);
            GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloRadius"), cases[index][0]);
            worldHaloDraw(shader, visible, output, sky, size);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, output[0]);
            GL11.glClearColor(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], BACKGROUND[3]); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glEnable(GL11.GL_BLEND); render(output, size, size);
            GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 2); render(output, size, size);
            GL11.glDisable(GL11.GL_BLEND);
            float[] image = pixels(output[0], size, size);
            for (int y = 0; y < size; y++) { for (int x = 0; x < size; x++) {
                int at = (y * size + x) * 4;
                near(BACKGROUND[3], image[at + 3], "world halo preview alpha");
                int red = Math.round(Math.min(1, image[at]) * 255), green = Math.round(Math.min(1, image[at + 1]) * 255), blue = Math.round(Math.min(1, image[at + 2]) * 255);
                preview.setRGB(index % 2 * size + x, index / 2 * size + size - 1 - y, red << 16 | green << 8 | blue);
            } }
            var graphics = preview.createGraphics(); graphics.setColor(java.awt.Color.WHITE);
            graphics.drawString("R=" + cases[index][0] + " blocks / distance=" + cases[index][1], index % 2 * size + 8, index / 2 * size + 20); graphics.dispose();
        }
        Path directory = root.resolve("build/engine-world-halo-gpu-regression"); Files.createDirectories(directory);
        javax.imageio.ImageIO.write(preview, "png", directory.resolve("preview.png").toFile());
        System.out.println("PASS world halo radii 20/30/50/500, distance/FOV/resolution scaling, near/DH normal+reverse source occlusion, exposed tail, hand mask and scene Alpha");
    }

    /** 与生产一致的一像素先写后读；半径与场景输入均来自调用者当前上传值。 */
    private static float worldHaloDraw(int shader, int[] visibility, int[] output, int safeTexture, int size) {
        sampler(shader, "PlumeVisibility", 0, safeTexture);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 3); render(visibility, 1, 1);
        float result = pixel(visibility[0], 0, 0)[0];
        sampler(shader, "PlumeVisibility", 0, visibility[1]);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 4); render(output, size, size);
        return result;
    }

    /** 在真实高亮喷流上检查远近层、能量、透明度、极小窗口和高亮上限，并输出对照图。 */
    private static void testMultiscaleBloom(Path root, int bloom, int plume) throws Exception {
        int size = 256;
        int sky = depth(size, size, 0, false);
        int scene = color(size, size, new float[] {.025F, .035F, .055F, .42F});
        int[] hdr = target(size, size), display = target(size, size);
        int[][][] levels = bloomTargets(size, size);
        GL20.glUseProgram(plume); uniforms(plume);
        GL20.glUniform2f(GL20.glGetUniformLocation(plume, "ScreenSize"), size, size);
        GL20.glUniform4f(GL20.glGetUniformLocation(plume, "PlumeEmission"), 60000, 16, .2F, 12);
        GL20.glUniform1f(GL20.glGetUniformLocation(plume, "Time"), .7F);
        sampler(plume, "SceneDepth", 1, sky); sampler(plume, "DistantDepth", 2, sky);
        plumeGeometry(plume, 0, -2.5F, -8, 0, 1, 0, 5, .35F);
        GL20.glUniform1i(GL20.glGetUniformLocation(plume, "OutputMode"), 1); render(hdr, size, size);

        // 关闭一组后读原始光晕能量，独立测试不会被最终颜色饱和掩盖。
        multiscaleBloom(bloom, hdr[1], scene, levels, size, size, 1, 0);
        float[] nearOnly = pixels(levels[0][1][0], size / 2, size / 2);
        multiscaleBloom(bloom, hdr[1], scene, levels, size, size, 0, 1);
        float[] farOnly = pixels(levels[0][1][0], size / 2, size / 2);
        multiscaleBloom(bloom, hdr[1], scene, levels, size, size, 1, 1);
        float[] together = pixels(levels[0][1][0], size / 2, size / 2);
        for (int at = 0; at < together.length; at += 4) {
            require(Math.abs(together[at] - nearOnly[at] - farOnly[at]) < .025F,
                    "near/far energy was redistributed when one group was disabled");
        }
        int edge = ((size / 4) * (size / 2) + size / 4 + 16) * 4;
        require(farOnly[edge] > nearOnly[edge] + .0001F, "far bloom did not extend beyond the near glow");
        for (int y : new int[] {52, 64, 76}) {
            require(together[(y * (size / 2) + 67) * 4] > .01F, "glow failed to follow the whole luminous plume");
        }
        multiscaleBloom(bloom, hdr[1], scene, levels, size, size, 0, 0);
        zero(pixel(levels[0][1][0], size / 4, size / 4), "both bloom groups disabled");

        // 常量图四层叠加仍保留一次提取的能量，避免“多四层就亮四倍”。
        int uniform = color(size, size, new float[] {8, 4, 1, 8});
        multiscaleBloom(bloom, uniform, scene, levels, size, size, 1, 1);
        float extracted = pixel(levels[0][0][0], 20, 20)[0];
        float combined = pixel(levels[0][1][0], 20, 20)[0];
        require(Math.abs(extracted - combined) < .04F, "four layers multiplied constant-field bloom energy");
        // 世界颜色即使是纯白也不会成为光晕来源；不允许进入 Bloom 的光保持零扩散。
        int brightWorld = color(size, size, new float[] {1, 1, 1, .42F});
        int ineligible = color(size, size, new float[] {60000, 30000, 10000, 0});
        multiscaleBloom(bloom, ineligible, brightWorld, levels, size, size, 1, 1);
        zero(pixel(levels[0][1][0], 20, 20), "world or ineligible light leaked into bloom");
        // 手动最大增强也不能把中间半精度纹理写成无穷大。
        int maximum = color(size, size, new float[] {60000, 60000, 60000, 60000});
        multiscaleBloom(bloom, maximum, scene, levels, size, size, 256, 256);
        float[] safe = pixel(levels[0][1][0], 20, 20);
        require(safe[0] > 50000 && safe[0] <= 60000, "manual boost escaped the HDR safety limit");
        for (int[] dimensions : new int[][] {{257, 129}, {1, 1}}) {
            int[][][] tiny = bloomTargets(dimensions[0], dimensions[1]);
            multiscaleBloom(bloom, uniform, scene, tiny, dimensions[0], dimensions[1], 1, 1);
            require(pixel(tiny[0][1][0], 0, 0)[0] > 0, "odd or one-pixel bloom lost all energy");
        }

        // 对照旧单尺度、近层、远层、合成；仅写测试图片，不触碰资源源文件。
        var preview = new java.awt.image.BufferedImage(size * 2, size * 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        int previousBloom = program(root.resolve("src/main/resources/assets/ywzj_rvp/shaders/core"), "screen_light_bloom");
        try {
            String[] labels = {"Previous single scale", "Near layers only", "Far layers only", "New four-scale bloom"};
            for (int panel = 0; panel < labels.length; panel++) {
                int selected = bloom;
                if (panel == 0) {
                    selected = previousBloom; GL20.glUseProgram(selected);
                    sampler(selected, "InputTexture", 0, hdr[1]); sampler(selected, "SceneColor", 1, scene);
                    sampler(selected, "BloomTexture", 2, hdr[1]);
                    GL20.glUniform1i(GL20.glGetUniformLocation(selected, "Mode"), 0); render(levels[0][0], size / 2, size / 2);
                    GL20.glUniform1i(GL20.glGetUniformLocation(selected, "Mode"), 1);
                    sampler(selected, "InputTexture", 0, levels[0][0][1]);
                    GL20.glUniform2f(GL20.glGetUniformLocation(selected, "Direction"), 2F / size, 0);
                    render(levels[0][1], size / 2, size / 2);
                    sampler(selected, "InputTexture", 0, levels[0][1][1]);
                    GL20.glUniform2f(GL20.glGetUniformLocation(selected, "Direction"), 0, 2F / size);
                    render(levels[0][0], size / 2, size / 2);
                } else {
                    multiscaleBloom(bloom, hdr[1], scene, levels, size, size, panel == 2 ? 0 : 1, panel == 1 ? 0 : 1);
                }
                GL20.glUseProgram(selected);
                sampler(selected, "InputTexture", 0, hdr[1]); sampler(selected, "SceneColor", 1, scene);
                sampler(selected, "BloomTexture", 2, levels[0][panel == 0 ? 0 : 1][1]);
                GL20.glUniform1i(GL20.glGetUniformLocation(selected, "Mode"), 2); render(display, size, size);
                GL20.glUseProgram(plume);
                // 光晕已复用纹理单元；切回喷焰时必须恢复真实深度，不能把场景颜色误当近景遮挡。
                sampler(plume, "SceneDepth", 1, sky); sampler(plume, "DistantDepth", 2, sky);
                GL20.glUniform1i(GL20.glGetUniformLocation(plume, "OutputMode"), 2);
                GL11.glEnable(GL11.GL_BLEND); GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                GL14.glBlendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
                render(display, size, size); GL11.glDisable(GL11.GL_BLEND);
                float[] image = pixels(display[0], size, size);
                boolean white = false;
                for (int y = 0; y < size; y++) { for (int x = 0; x < size; x++) {
                    int at = (y * size + x) * 4;
                    near(.42F, image[at + 3], "multiscale display alpha");
                    white |= image[at] > .99F && image[at + 1] > .99F && image[at + 2] > .99F;
                    int red = Math.round(Math.min(1, image[at]) * 255), green = Math.round(Math.min(1, image[at + 1]) * 255);
                    int blue = Math.round(Math.min(1, image[at + 2]) * 255);
                    preview.setRGB(panel % 2 * size + x, panel / 2 * size + size - 1 - y, red << 16 | green << 8 | blue);
                } }
                require(white, "glow tuning removed the independent white-hot core");
                var graphics = preview.createGraphics(); graphics.setColor(java.awt.Color.WHITE);
                graphics.drawString(labels[panel], panel % 2 * size + 8, panel / 2 * size + 20); graphics.dispose();
            }
        } finally { GL20.glDeleteProgram(previousBloom); }
        Path directory = root.resolve("build/multiscale-glow-gpu-regression"); Files.createDirectories(directory);
        javax.imageio.ImageIO.write(preview, "png", directory.resolve("preview.png").toFile());
        System.out.println("PASS four-scale bloom: whole plume, independent near/far, conserved default energy, eligibility, odd/1px windows, safe HDR, white core, Alpha; preview=" + directory.resolve("preview.png"));
    }

    /** 与生产相同的四层独立纹理，所有中间结果都允许在显卡上回读。 */
    private static int[][][] bloomTargets(int width, int height) {
        int[][][] result = new int[RVP_BloomParameters.LEVELS][2][];
        for (int level = 0; level < result.length; level++) {
            for (int index = 0; index < 2; index++) {
                // 调用生产尺寸规则，覆盖非整除窗口和一像素层。
                result[level][index] = target(RVP_BloomParameters.size(width, level), RVP_BloomParameters.size(height, level));
            }
        }
        return result;
    }

    /** 执行正式 GLSL 的四层缩小/柔化/回升，逐轮检查采样器与写入附件不存在反馈。 */
    private static void multiscaleBloom(int shader, int source, int scene, int[][][] levels,
            int width, int height, float nearStrength, float farStrength) {
        GL20.glUseProgram(shader);
        for (int level = 0; level < levels.length; level++) {
            int w = RVP_BloomParameters.size(width, level), h = RVP_BloomParameters.size(height, level);
            bloomStage(shader, level == 0 ? 0 : 3, level == 0 ? source : levels[level - 1][0][1],
                    scene, scene, levels[level][0], w, h, 0, 0, 0, 0);
            bloomStage(shader, 1, levels[level][0][1], scene, scene, levels[level][1], w, h, 1F / w, 0, 0, 0);
            bloomStage(shader, 1, levels[level][1][1], scene, scene, levels[level][0], w, h, 0, 1F / h, 0, 0);
        }
        for (int level = levels.length - 1; level >= 0; level--) {
            boolean hasCoarser = level + 1 < levels.length;
            bloomStage(shader, 4, levels[level][0][1], scene, hasCoarser ? levels[level + 1][1][1] : scene,
                    levels[level][1], RVP_BloomParameters.size(width, level), RVP_BloomParameters.size(height, level),
                    0, 0, RVP_BloomParameters.weight(level, nearStrength, farStrength), hasCoarser ? 1 : 0);
        }
    }

    /** 一轮真实程序绘制，保证未使用的采样器也不绑定当前写入纹理。 */
    private static void bloomStage(int shader, int mode, int input, int scene, int coarser, int[] output,
            int width, int height, float dx, float dy, float weight, float coarseWeight) {
        require(input != output[1] && scene != output[1] && coarser != output[1], "bloom texture feedback");
        sampler(shader, "InputTexture", 0, input); sampler(shader, "SceneColor", 1, scene);
        sampler(shader, "BloomTexture", 2, coarser);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "Mode"), mode);
        GL20.glUniform2f(GL20.glGetUniformLocation(shader, "Direction"), dx, dy);
        GL20.glUniform2f(GL20.glGetUniformLocation(shader, "LayerWeights"), weight, coarseWeight);
        render(output, width, height);
    }

    /** 独立调外围橙焰/广域橙晕，不得互改实际显示；部分遮挡测试另由世界光晕回归覆盖。 */
    private static void testIndependentPlumeGlow(int shader) {
        int size = 128;
        int sky = depth(size, size, 0, false);
        int[] visible = target(1, 1), output = target(size, size), emission = target(size, size);
        GL20.glUseProgram(shader); uniforms(shader);
        GL20.glUniform2f(GL20.glGetUniformLocation(shader, "ScreenSize"), size, size);
        sampler(shader, "SceneDepth", 1, sky); sampler(shader, "DistantDepth", 2, sky);
        plumeGeometry(shader, 0, 0, -8, 1, 0, 0, 3, .45F);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloRadius"), 20);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloIntensity"), 4);
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "PlumeEmission"), 60000, 16, .2F, 12);
        float coverage = worldHaloDraw(shader, visible, output, sky, size);
        float[] reference = pixels(output[0], size, size);
        for (float outer : new float[] {0, 256}) {
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "PlumeEmission"), 60000, outer, .2F, 12);
            near(coverage, worldHaloDraw(shader, visible, output, sky, size), "outer intensity changed visible coverage");
            float[] actual = pixels(output[0], size, size);
            for (int at = 0; at < actual.length; at++) { near(reference[at], actual[at], "outer intensity changed world halo"); }
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 0); render(emission, size, size);
        float[] before = pixels(emission[0], size, size);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloIntensity"), 0);
        require(worldHaloDraw(shader, visible, output, sky, size) > 0, "zero world halo erased plume coverage");
        zero(pixel(output[0], 64, 64), "disabled independent world halo");
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 0); render(emission, size, size);
        float[] after = pixels(emission[0], size, size);
        for (int at = 0; at < after.length; at++) { near(before[at], after[at], "world halo intensity changed plume"); }
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloRadius"), 0);
        GL20.glUniform1f(GL20.glGetUniformLocation(shader, "PlumeHaloIntensity"), 4);
        worldHaloDraw(shader, visible, output, sky, size);
        zero(pixel(output[0], 64, 64), "zero halo radius retained world halo");
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 1); render(emission, size, size);
        zero(pixel(emission[0], 64, 64), "zero halo radius retained bloom input");
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 0); render(emission, size, size);
        float[] coreOnly = pixels(emission[0], size, size);
        for (int at = 0; at < coreOnly.length; at++) { near(before[at], coreOnly[at], "zero halo radius removed emissive plume"); }
        System.out.println("PASS outer flame, world halo and visible source coverage are independent; zero world halo preserves emissive plume");
    }

    /** 用爆炸/炮口正式颜色验证照亮范围和光晕来源互不影响，支持纯光晕、纯照亮及近景/DH遮挡。 */
    private static void testSeparatedLightHalo(int shader) {
        int size = 128;
        int sky = depth(size, size, 0, false), surface = depth(size, size, 5, false);
        int scene = color(size, size, BACKGROUND);
        int[] lit = target(size, size), glow = target(size, size);
        GL20.glUseProgram(shader); uniforms(shader);
        GL20.glUniform2f(GL20.glGetUniformLocation(shader, "ScreenSize"), size, size);
        sampler(shader, "SceneColor", 0, scene); sampler(shader, "DistantDepth", 2, sky);
        for (RVP_LightParameters preset : new RVP_LightParameters[] {
                RVP_LightParameters.explosion(4), RVP_LightParameters.muzzle(125, false)}) {
            sampler(shader, "SceneDepth", 1, surface);
            lights(shader, 1, 0, 0, -3, 4, preset.intensity(), preset.surfaceWeight(), 0);
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightColorIntensity0"),
                    preset.red(), preset.green(), preset.blue(), preset.intensity());
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightHaloColorIntensity0"),
                    preset.red(), preset.green(), preset.blue(), preset.intensity());
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightShape0"), preset.surfaceWeight(), 0, 1, 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 1); render(lit, size, size);
            float[] originalLight = pixels(lit[0], size, size);
            require(pixel(lit[0], 64, 64)[0] > 0, "reference surface light is missing");
            GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 3); render(glow, size, size);
            float[] originalGlow = pixels(glow[0], size, size);
            int[] narrow = brightBounds(originalGlow, size);
            for (float radius : new float[] {0, 8, 8192}) {
                GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightPositionRadius0"), 0, 0, -3, radius);
                render(glow, size, size);
                float[] actual = pixels(glow[0], size, size);
                for (int at = 0; at < actual.length; at++) { near(originalGlow[at], actual[at], "lighting radius changed halo source"); }
            }
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightPositionRadius0"), 0, 0, -3, 4);
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightShape0"), preset.surfaceWeight(), 0, 1, 3);
            GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 1); render(lit, size, size);
            float[] actualLight = pixels(lit[0], size, size);
            for (int at = 0; at < actualLight.length; at++) { near(originalLight[at], actualLight[at], "halo radius changed surface lighting"); }
            GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 3); render(glow, size, size);
            int[] wide = brightBounds(pixels(glow[0], size, size), size);
            require(wide[2] - wide[0] > 2 * (narrow[2] - narrow[0]), "independent halo radius failed to expand");
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightShape0"), preset.surfaceWeight(), 0, 1, 0);
            render(glow, size, size); zero(pixel(glow[0], 64, 64), "zero halo radius");
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightShape0"), preset.surfaceWeight(), 0, 1, 3);
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightPositionRadius0"), 0, 0, -3, 0);
            sampler(shader, "SceneDepth", 1, sky); render(glow, size, size);
            require(pixel(glow[0], 64, 64)[0] > 0, "halo-only source disappeared against empty sky");
            sampler(shader, "SceneDepth", 1, depth(size, size, 2, false)); render(glow, size, size);
            zero(pixel(glow[0], 64, 64), "solid wall failed to hide halo source");
            sampler(shader, "SceneDepth", 1, sky);
            for (boolean reverse : new boolean[] {false, true}) {
                sampler(shader, "DistantDepth", 2, projectedDepth(size / 2, size / 2, 2, .1F, 100, reverse, false));
                distantProjection(shader, .1F, 100, reverse, 1); render(glow, size, size);
                zero(pixel(glow[0], 64, 64), "DH foreground failed to hide independent halo");
                GL20.glUniform1i(GL20.glGetUniformLocation(shader, "HasDistantDepth"), 0);
            }
        }
        // 放宽到最大环境亮度/半径后，八灯输出仍必须留在半精度安全范围。
        sampler(shader, "SceneDepth", 1, sky); sampler(shader, "DistantDepth", 2, sky);
        lights(shader, 8, 0, 0, -3, 8192, 524288, 16, 0);
        for (int index = 0; index < 8; index++) {
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightShape" + index), 16, 0, 1, 8192);
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 3); render(glow, size, size);
        float[] maximum = pixel(glow[0], 64, 64);
        require(maximum[0] >= 50000 && maximum[0] <= 60000, "expanded eight-light halo range overflowed HDR");
        System.out.println("PASS explosion/muzzle independent lighting and halo radii, halo-only sky, zero range, normal/reverse DH walls and expanded eight-light HDR bounds");
    }

    /** 回读独立爆炸广域亮度及整盏光源的平滑时间线；照亮与光晕必须一起结束。 */
    private static void testTransientLightTimeline(int shader, int bloom) {
        int size = 64;
        int sky = depth(size, size, 0, false), surface = depth(size, size, 5, false);
        int scene = color(size, size, BACKGROUND);
        int[] lit = target(size, size), glow = target(size, size), display = target(size, size);
        int[][][] levels = bloomTargets(size, size);
        GL20.glUseProgram(shader); uniforms(shader);
        GL20.glUniform2f(GL20.glGetUniformLocation(shader, "ScreenSize"), size, size);
        sampler(shader, "SceneColor", 0, scene); sampler(shader, "SceneDepth", 1, surface);
        sampler(shader, "DistantDepth", 2, sky);
        lights(shader, 1, 0, 0, -3, 60, 25, .85F, 0);
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightShape0"), .85F, 0, 1, 12);
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightHaloColorIntensity0"), 1, .82F, .48F, 16);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 1); render(lit, size, size);
        float referenceLight = pixel(lit[0], size / 2, size / 2)[0];
        for (float intensity : new float[] {0, 1, 16, 60000}) {
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightHaloColorIntensity0"), 1, .82F, .48F, intensity);
            GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 1); render(lit, size, size);
            near(referenceLight, pixel(lit[0], size / 2, size / 2)[0], "wide halo intensity changed surface lighting");
            GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 3); render(glow, size, size);
            if (intensity == 0) { zero(pixel(glow[0], size / 2, size / 2), "zero explosion wide halo intensity"); }
            else { require(pixel(glow[0], size / 2, size / 2)[0] > 0, "independent wide halo brightness has no effect"); }
        }
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightHaloColorIntensity0"), 1, .82F, .48F, 16);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 3); render(glow, size, size);
        float referenceGlow = pixel(glow[0], size / 2, size / 2)[0];
        for (float intensity : new float[] {0, 25, 320}) {
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightColorIntensity0"), 1, .82F, .48F, intensity);
            render(glow, size, size);
            near(referenceGlow, pixel(glow[0], size / 2, size / 2)[0], "surface brightness changed explosion wide halo");
        }
        for (float scale : new float[] {.5F, 1, 2}) {
            // 调用正式时间线，逐点读真实GLSL及四层合成，不能只比较参数元数据。
            float duration = RVP_TransientLightTimeline.durationTicks(32, scale);
            float previousLight = Float.MAX_VALUE, previousGlow = Float.MAX_VALUE;
            for (float fraction : new float[] {0, .5F, .8F, .95F, .99F, .999F, 1}) {
                float fade = RVP_TransientLightTimeline.fade(duration * fraction, 32, .3F, scale);
                GL20.glUseProgram(shader);
                // 多尺度合成会复用采样单元，切回光源时恢复真实场景和深度输入。
                sampler(shader, "SceneColor", 0, scene); sampler(shader, "SceneDepth", 1, surface);
                sampler(shader, "DistantDepth", 2, sky);
                GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightColorIntensity0"), 1, .82F, .48F, 25 * fade);
                GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightHaloColorIntensity0"), 1, .82F, .48F, 16 * fade);
                GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 1); render(lit, size, size);
                float lightValue = pixel(lit[0], size / 2, size / 2)[0];
                GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 3); render(glow, size, size);
                float glowValue = pixel(glow[0], size / 2, size / 2)[0];
                require(lightValue <= previousLight + .002F && glowValue <= previousGlow + .002F,
                        "light or halo brightened again during its fade");
                previousLight = lightValue; previousGlow = glowValue;
                multiscaleBloom(bloom, glow[1], scene, levels, size, size, 1, 1);
                GL20.glUseProgram(bloom); sampler(bloom, "InputTexture", 0, lit[1]);
                sampler(bloom, "SceneColor", 1, scene); sampler(bloom, "BloomTexture", 2, levels[0][1][1]);
                GL20.glUniform1i(GL20.glGetUniformLocation(bloom, "Mode"), 2); render(display, size, size);
                float[] result = pixel(display[0], size / 2, size / 2);
                near(BACKGROUND[3], result[3], "timeline changed scene alpha");
                if (fraction == .999F) {
                    require(result[0] - BACKGROUND[0] < .01F, "last visible frame retained a brightness cliff");
                }
                if (fraction == 1) { background(result, "whole light did not end at its adjusted lifetime"); }
            }
        }
        System.out.println("PASS independent explosion wide halo gain 0..60000, brightness isolation, whole-source 0.5/1/2 decay timing, smooth final composite and Alpha");
    }

    /** 回读新增曲线送入生产光晕程序后的亮度；切换光晕曲线不能串改环境照亮。 */
    private static void testHaloDecayCurves(int shader) {
        int size = 64;
        int sky = depth(size, size, 0, false), surface = depth(size, size, 5, false);
        int scene = color(size, size, BACKGROUND);
        int[] lit = target(size, size), glow = target(size, size);
        GL20.glUseProgram(shader); uniforms(shader);
        GL20.glUniform2f(GL20.glGetUniformLocation(shader, "ScreenSize"), size, size);
        sampler(shader, "SceneColor", 0, scene); sampler(shader, "SceneDepth", 1, surface);
        sampler(shader, "DistantDepth", 2, sky);
        lights(shader, 1, 0, 0, -3, 60, 25, .85F, 0);
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightShape0"), .85F, 0, 1, 12);
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightHaloColorIntensity0"), 1, .82F, .48F, 16);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 3); render(glow, size, size);
        float fullHalo = pixel(glow[0], size / 2, size / 2)[0];
        require(fullHalo > 0, "halo curve reference is invisible");
        for (float scale : new float[] {.5F, 1, 2}) {
            // 调用正式寿命规则，验证同一归一化曲线在加速和减速后仍保持相同形状。
            float duration = RVP_TransientLightTimeline.durationTicks(32, scale);
            for (float progress : new float[] {0, .05F, .1F, .25F, .5F, .8F, .9F, .99F, 1}) {
                float original = RVP_TransientLightTimeline.fade(duration * progress, 32, .3F, scale);
                GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightColorIntensity0"), 1, .82F, .48F, 25 * original);
                GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 1); render(lit, size, size);
                float environment = pixel(lit[0], size / 2, size / 2)[0];
                for (RVP_HaloDecayCurve curve : new RVP_HaloDecayCurve[] {RVP_HaloDecayCurve.LINEAR, RVP_HaloDecayCurve.FLASH_HOLD}) {
                    // 调用生产曲线函数后上传真实光晕槽位；低亮保持段不能再乘环境光的原包络。
                    float fade = curve.fade(progress, original, .1F, .15F, .8F);
                    GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightHaloColorIntensity0"), 1, .82F, .48F, 16 * fade);
                    GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 3); render(glow, size, size);
                    float actual = pixel(glow[0], size / 2, size / 2)[0] / fullHalo;
                    float expected = curve == RVP_HaloDecayCurve.LINEAR ? 1 - progress : fade;
                    if (curve == RVP_HaloDecayCurve.FLASH_HOLD && progress >= .1F && progress <= .8F) { expected = .15F; }
                    near(expected, actual, "GPU halo curve " + curve + " progress=" + progress);
                    GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 1); render(lit, size, size);
                    near(environment, pixel(lit[0], size / 2, size / 2)[0], "halo curve changed environment lighting");
                }
            }
        }
        System.out.println("PASS linear/flash-hold halo GPU brightness, constant low plateau, exact zero endpoint, 0.5/1/2 lifetime scales and environment isolation");
    }

    /** 天空闪光的实际像素：保持3刻后平滑淡出，空间上中心最亮、向外单调变暗。 */
    private static void testExplosionPeakHalo(int shader) {
        int size = 65, center = size / 2;
        int sky = depth(size, size, 0, false), scene = color(size, size, BACKGROUND);
        int[] glow = target(size, size);
        GL20.glUseProgram(shader); uniforms(shader);
        GL20.glUniform2f(GL20.glGetUniformLocation(shader, "ScreenSize"), size, size);
        sampler(shader, "SceneColor", 0, scene); sampler(shader, "SceneDepth", 1, sky);
        sampler(shader, "DistantDepth", 2, sky);
        lights(shader, 1, 0, 0, -3, 60, 25, .85F, 0);
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightShape0"), .85F, 0, 1, 2);
        GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightHaloColorIntensity0"), 1, .82F, .48F, 16);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 3); render(glow, size, size);
        float peak = pixel(glow[0], center, center)[0];
        near(16, peak, "sky halo center must equal configured peak");
        float previous = peak;
        for (int offset = 1; offset <= center; offset++) {
            float right = pixel(glow[0], center + offset, center)[0];
            float left = pixel(glow[0], center - offset, center)[0];
            require(right <= previous + .002F, "sky halo brightened toward its rim");
            near(right / peak, left / peak, "sky halo lost radial symmetry");
            previous = right;
        }
        near(0, previous, "sky halo rim must reach zero");
        for (double age : new double[] {0, 1, 2.99, 3, 3.5, 17.5, 31.9, 32}) {
            // 调用生产实际刻函数，保持和淡出分开计时；天空没有表面，依然应显示独立光晕。
            float fade = RVP_HaloDecayCurve.peakHoldFade(age, 3, 29);
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightHaloColorIntensity0"), 1, .82F, .48F, 16 * fade);
            render(glow, size, size);
            float actual = pixel(glow[0], center, center)[0] / peak;
            near(fade, actual, "three-tick sky flash time sampling");
            if (age <= 3) { near(1, actual, "sky flash lost brightness before three ticks"); }
            if (age == 17.5) { near(.5F, actual, "sky flash fade midpoint"); }
            if (age == 32) { near(0, actual, "sky flash end"); }
        }
        System.out.println("PASS explosion sky halo keeps its peak for 3 ticks then fades smoothly; radial center is brightest, symmetric rim reaches zero");
    }

    /** 真正 500 格照明：距发动机 450 格的 DH 表面仍受光，窄口径范围不能冒充同样远距照明。 */
    private static void testExtendedEngineSurface(int shader) {
        GL20.glUseProgram(shader); uniforms(shader);
        int sky = depth(SIZE, SIZE, 0, false);
        int[] output = target(SIZE, SIZE);
        sampler(shader, "SceneColor", 0, color(SIZE, SIZE, BACKGROUND)); sampler(shader, "SceneDepth", 1, sky);
        sampler(shader, "DistantDepth", 2, projectedDepth(SIZE / 2, SIZE / 2, 460, 7.5F, 4096, false, false));
        distantProjection(shader, 7.5F, 4096, false, 1);
        RVP_LightParameters large = RVP_LightParameters.engine(4800);
        lights(shader, 1, 0, 0, -10, large.radius(), large.intensity(), large.surfaceWeight(), 0);
        render(output, SIZE, SIZE);
        require(pixel(output[0], 8, 8)[0] > BACKGROUND[0] + .02F, "engine did not illuminate a DH surface 450 blocks from source");
        RVP_LightParameters small = RVP_LightParameters.engine(100);
        lights(shader, 1, 0, 0, -10, small.radius(), small.intensity(), small.surfaceWeight(), 0);
        render(output, SIZE, SIZE); background(pixel(output[0], 8, 8), "narrow caliber engine escaped its thirty-block range");
        System.out.println("PASS actual 500-block engine preset lights a DH surface at 450 blocks; caliber range and P5-off semantics preserved");
    }

    /** 整幅浮点回读检查无穷值；诊断 PNG 由实际生产 Shader 输出生成。 */
    private static float[] pixels(int framebuffer, int width, int height) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
        float[] result = new float[width * height * 4];
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_FLOAT, result);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "plume pixel readback error");
        for (float value : result) { require(Float.isFinite(value), "non-finite plume pixel"); }
        return result;
    }

    /** 有效喷流亮部包围框，空图返回 right=-1。 */
    private static int[] brightBounds(float[] image, int size) {
        int[] bounds = {size, size, -1, -1};
        for (int y = 0; y < size; y++) { for (int x = 0; x < size; x++) {
            if (image[(y * size + x) * 4] <= 0.1F) { continue; }
            bounds[0] = Math.min(bounds[0], x); bounds[1] = Math.min(bounds[1], y);
            bounds[2] = Math.max(bounds[2], x); bounds[3] = Math.max(bounds[3], y);
        } }
        return bounds;
    }

    /** 真实烟雾材质程序回读，覆盖三类光、天空、Alpha、方块/实体/DH 遮挡和八灯上限。 */
    private static void testSmokeLighting(int smoke) {
        // 调用共用材质回归；普通入口没有生产帧资源快照，专项诊断额外传入实际采集结果。
        testSmokeLighting(smoke, 0);
    }

    /** 可额外使用生产帧资源保存的干净深度，验证提前画出的烟仍可接受后方爆炸光。 */
    private static void testSmokeLighting(int smoke, int opaqueSnapshot) {
        int smokeVao = GL30.glGenVertexArrays();
        int smokeVbo = GL15.glGenBuffers();
        try {
            GL30.glBindVertexArray(smokeVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, smokeVbo);
            // Position + UV0，顶点 Color 用常量；画面整片烟位于相机前 3 格，背景可为纯天空。
            float[] quad = {-3,-3,-3,0,0, 3,-3,-3,1,0, 3,3,-3,1,1, -3,-3,-3,0,0, 3,3,-3,1,1, -3,3,-3,0,1};
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 20, 0);
            GL20.glEnableVertexAttribArray(1); GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 12);
            GL20.glDisableVertexAttribArray(2); GL20.glVertexAttrib4f(2, 0.1F, 0.1F, 0.1F, 1);
            int[] output = target(SIZE, SIZE);
            int sky = depth(SIZE, SIZE, 0, false);
            int texture = color(SIZE, SIZE, new float[] {1, 1, 1, 0.5F});
            GL20.glUseProgram(smoke); uniforms(smoke);
            matrix(smoke, "SmokeView", new float[] {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});
            sampler(smoke, "Sampler0", 0, texture); sampler(smoke, "SceneDepth", 1, sky); sampler(smoke, "DistantDepth", 2, sky);
            RVP_LightParameters[] sources = {RVP_LightParameters.explosion(4), RVP_LightParameters.muzzle(7.62F, false),
                    RVP_LightParameters.muzzle(125, false), RVP_LightParameters.muzzle(130, true), RVP_LightParameters.engine()};
            for (RVP_LightParameters source : sources) {
                smokeLight(smoke, source, 1);
                smokeDraw(output);
                float[] lit = pixel(output[0], 8, 8);
                require(lit[0] > 0.05F && lit[0] > lit[2], "smoke not lit against empty sky");
                near(0, lit[3], "smoke layer must not replace scene alpha");
            }
            System.out.println("PASS smoke texture is lit against sky by production explosion, cannon muzzle and rocket engine presets");
            if (opaqueSnapshot != 0) {
                sampler(smoke, "SceneDepth", 1, opaqueSnapshot);
                smokeLight(smoke, RVP_LightParameters.explosion(4), 1);
                GL20.glUniform4f(GL20.glGetUniformLocation(smoke, "LightPositionRadius0"),
                        0, 0, -4, RVP_LightParameters.explosion(4).radius());
                smokeDraw(output);
                require(pixel(output[0], 8, 8)[0] > 0.05F, "early smoke snapshot prevented smoke illumination from behind");
                System.out.println("PASS early smoke receives rear explosion light using production opaque-depth snapshot");
            }
            int opaque = depth(SIZE, SIZE, 1, false);
            sampler(smoke, "SceneDepth", 1, opaque); smokeDraw(output);
            zero(pixel(output[0], 8, 8), "solid foreground must still hide smoke lighting");
            sampler(smoke, "SceneDepth", 1, sky);
            for (boolean reverse : new boolean[] {false, true}) {
                int dh = projectedDepth(SIZE / 2, SIZE / 2, 1, 0.5F, 512, reverse, false);
                sampler(smoke, "DistantDepth", 2, dh); distantProjection(smoke, 0.5F, 512, reverse, 1);
                smokeDraw(output); zero(pixel(output[0], 8, 8), "DH foreground must hide smoke light");
            }
            GL20.glUniform1i(GL20.glGetUniformLocation(smoke, "HasDistantDepth"), 0);
            sampler(smoke, "DistantDepth", 2, sky);
            float[] handDepth = new float[SIZE * SIZE]; java.util.Arrays.fill(handDepth, 0.55F);
            int hand = texture(SIZE, SIZE, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, handDepth);
            sampler(smoke, "SceneDepth", 1, hand);
            GL20.glUniform1f(GL20.glGetUniformLocation(smoke, "HandDepthLimit"), 0.5625F);
            smokeDraw(output); zero(pixel(output[0], 8, 8), "shader hand must not receive smoke overlay");
            GL20.glUniform1f(GL20.glGetUniformLocation(smoke, "HandDepthLimit"), 0);
            // 烟在墙前但光在墙后：烟面仍可见，来自墙后的光不能穿墙照到烟。
            int separatingWall = depth(SIZE, SIZE, 4, false);
            sampler(smoke, "SceneDepth", 1, separatingWall);
            GL20.glUniform4f(GL20.glGetUniformLocation(smoke, "LightPositionRadius0"), 0, 0, -6, 7);
            smokeDraw(output); zero(pixel(output[0], 8, 8), "wall between smoke and source must block illumination");
            sampler(smoke, "SceneDepth", 1, sky); smokeLight(smoke, RVP_LightParameters.engine(), 1);
            int clear = color(SIZE, SIZE, new float[] {1, 1, 1, 0});
            sampler(smoke, "Sampler0", 0, clear); smokeDraw(output);
            zero(pixel(output[0], 8, 8), "transparent smoke texture texel must not add a rectangle of light");
            sampler(smoke, "Sampler0", 0, texture); smokeDraw(output);
            float first = pixel(output[0], 8, 8)[0];
            int dense = color(SIZE, SIZE, new float[] {1, 1, 1, 1});
            sampler(smoke, "Sampler0", 0, dense); smokeDraw(output);
            near(first * 2, pixel(output[0], 8, 8)[0], "actual smoke opacity must scale lighting");
            smokeLight(smoke, RVP_LightParameters.engine(), 8); smokeDraw(output);
            float[] maximum = pixel(output[0], 8, 8);
            require(maximum[0] <= 1 && maximum[0] > first, "eight-light smoke output not bounded");
            GL20.glUniform1i(GL20.glGetUniformLocation(smoke, "LightCount"), 0); smokeDraw(output);
            zero(pixel(output[0], 8, 8), "no-light smoke must preserve normal appearance");
            smokeLight(smoke, RVP_LightParameters.engine(), 1);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, output[0]);
            GL11.glClearColor(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], BACKGROUND[3]);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glEnable(GL11.GL_BLEND); GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL14.glBlendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            float[] composite = pixel(output[0], 8, 8);
            require(composite[0] > BACKGROUND[0], "smoke lighting not added over scene");
            near(BACKGROUND[3], composite[3], "smoke additive overlay changed world alpha");
            GL11.glDisable(GL11.GL_BLEND);
            System.out.println("PASS smoke Alpha holes/density and scene alpha, zero/eight lights, solid/DH walls, hand mask and source-to-smoke occlusion");
        } finally {
            GL30.glBindVertexArray(vao); GL15.glDeleteBuffers(smokeVbo); GL30.glDeleteVertexArrays(smokeVao);
        }
    }

    /** 只读本体真实炮口图集，验证已经在观察空间的模型面片也能用同一生产程序受光。 */
    private static void testMuzzleLighting(Path root, int shader) throws Exception {
        Path file = root.getParent().resolve("ywzj_vehicle_fish/src/main/resources/assets/ywzj_vehicle/textures/bedrock/effect/muzzle_flash_1.png");
        if (!Files.isRegularFile(file)) {
            System.out.println("SKIP actual muzzle atlas: base source is unavailable; smoke/material GLSL regressions still run");
            return;
        }
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file.toFile());
        float[] rgba = new float[image.getWidth() * image.getHeight() * 4];
        for (int y = 0; y < image.getHeight(); y++) { for (int x = 0; x < image.getWidth(); x++) {
            int value = image.getRGB(x, y), at = (y * image.getWidth() + x) * 4;
            rgba[at] = ((value >>> 16) & 255) / 255F;
            rgba[at + 1] = ((value >>> 8) & 255) / 255F;
            rgba[at + 2] = (value & 255) / 255F;
            rgba[at + 3] = ((value >>> 24) & 255) / 255F;
        } }
        int atlas = texture(image.getWidth(), image.getHeight(), GL30.GL_RGBA16F, GL11.GL_RGBA, rgba);
        int modelVao = GL30.glGenVertexArrays(), modelVbo = GL15.glGenBuffers();
        try {
            GL30.glBindVertexArray(modelVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, modelVbo);
            // 采用真实图集右上区域；位置已经由模型/骨骼/相机变换到观察空间，不再乘相机旋转。
            float[] quad = {-3,-3,-3,.5F,0, 3,-3,-3,1,0, 3,3,-3,1,.5F,
                    -3,-3,-3,.5F,0, 3,3,-3,1,.5F, -3,3,-3,.5F,.5F};
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 20, 0);
            GL20.glEnableVertexAttribArray(1); GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 12);
            GL20.glDisableVertexAttribArray(2); GL20.glVertexAttrib4f(2, 1, 1, 1, 1);
            GL20.glUseProgram(shader); uniforms(shader);
            matrix(shader, "SmokeView", new float[] {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});
            int sky = depth(SIZE, SIZE, 0, false);
            sampler(shader, "Sampler0", 0, atlas); sampler(shader, "SceneDepth", 1, sky); sampler(shader, "DistantDepth", 2, sky);
            int[] output = target(SIZE, SIZE);
            for (RVP_LightParameters source : new RVP_LightParameters[] {
                    RVP_LightParameters.explosion(4), RVP_LightParameters.muzzle(7.62F, false),
                    RVP_LightParameters.muzzle(125, false), RVP_LightParameters.muzzle(130, true), RVP_LightParameters.engine()}) {
                smokeLight(shader, source, 1); smokeDraw(output);
                require(pixel(output[0], 8, 8)[0] > 0.05F, "actual muzzle texture did not receive light against sky");
                zero(pixel(output[0], 15, 15), "actual transparent muzzle atlas edge became a bright rectangle");
            }
            float full = pixel(output[0], 8, 8)[0];
            GL20.glVertexAttrib4f(2, 1, 1, 1, .25F); smokeDraw(output);
            near(full * .25F, pixel(output[0], 8, 8)[0], "muzzle animation alpha was not retained");
            GL20.glVertexAttrib4f(2, 1, 1, 1, 1);
            sampler(shader, "SceneDepth", 1, depth(SIZE, SIZE, 1, false)); smokeDraw(output);
            zero(pixel(output[0], 8, 8), "foreground solid must still hide muzzle illumination");
            sampler(shader, "SceneDepth", 1, sky);
            for (boolean reverse : new boolean[] {false, true}) {
                sampler(shader, "DistantDepth", 2, projectedDepth(SIZE / 2, SIZE / 2, 1, .5F, 512, reverse, false));
                distantProjection(shader, .5F, 512, reverse, 1); smokeDraw(output);
                zero(pixel(output[0], 8, 8), "DH foreground must still hide muzzle illumination");
            }
            GL20.glUniform1i(GL20.glGetUniformLocation(shader, "HasDistantDepth"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(shader, "LightCount"), 0); smokeDraw(output);
            zero(pixel(output[0], 8, 8), "disabled illumination must preserve the original muzzle draw");
            System.out.println("PASS actual muzzle atlas receives all light presets; model-space path, atlas holes/fade, solid and normal/reversed DH occlusion");
        } finally {
            GL30.glBindVertexArray(vao); GL15.glDeleteBuffers(modelVbo); GL30.glDeleteVertexArrays(modelVao);
        }
    }

    /** 烟雾同源参数和生产上传一致，不依赖 JSON 光配置。 */
    private static void smokeLight(int program, RVP_LightParameters source, int count) {
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "LightCount"), count);
        for (int index = 0; index < 8; index++) {
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "LightPositionRadius" + index), 0, 0, -2, source.radius());
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "LightColorIntensity" + index),
                    source.red(), source.green(), source.blue(), source.intensity() * source.surfaceWeight());
        }
    }

    /** 烟雾面片是六顶点三角形，原屏幕程序仍使用其自己的三顶点全屏三角形。 */
    private static void smokeDraw(int[] target) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target[0]); GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glDisable(GL11.GL_BLEND); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
    }

    /** 上传真实发动机的表面与自发光参数，位置对准中央像素，便于回读峰值。 */
    private static void engineLights(int shader, RVP_LightParameters engine, int count, float global) {
        // 调用实际源参数生成，阶段和年龄与生产 uniform 上传一致。
        RVP_LightParameters.EngineEmission emission = RVP_LightParameters.engineEmission(engine.radius(), engine.intensity());
        lights(shader, count, 3F / SIZE, 3F / SIZE, -3, engine.radius(), engine.intensity() * global,
                engine.surfaceWeight(), 0);
        for (int index = 0; index < 8; index++) {
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightColorIntensity" + index),
                    engine.red(), engine.green(), engine.blue(), engine.intensity() * global);
            GL20.glUniform4f(GL20.glGetUniformLocation(shader, "LightEmission" + index),
                    emission.coreRadius(), emission.haloRadius(), emission.coreIntensity() * global, emission.haloIntensity() * global);
        }
    }

    /** 和生产混合一致，源覆盖到显示峰值、背景 Alpha 不动，不额外复制颜色。 */
    private static void emissionOverlay(int shader, int[] target) {
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, "OutputMode"), 2);
        GL11.glEnable(GL11.GL_BLEND);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
        render(target, SIZE, SIZE);
        GL11.glDisable(GL11.GL_BLEND);
    }

    /** 贡献或提取目标必须严格没有颜色/光晕能量。 */
    private static void zero(float[] rgba, String reason) {
        for (float channel : rgba) { near(0, channel, reason); }
    }

    /** 填充固定八槽位，验证 Shader 在上限情况下确实执行所有光。 */
    private static void lights(int program, int count, float x, float y, float z, float radius,
            float intensity, float surface, float volume) {
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "LightCount"), count);
        for (int index = 0; index < 8; index++) {
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "LightPositionRadius" + index), x, y, z, radius);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "LightColorIntensity" + index), 1, 0.7F, 0.2F, intensity);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "LightHaloColorIntensity" + index), 1, 0.7F, 0.2F, intensity);
            GL20.glUniform4f(GL20.glGetUniformLocation(program, "LightShape" + index), surface, volume, 1, 0);
        }
    }

    /** 链接实际 GLSL，并固定与 Java 全屏 VAO 一致的 Position 位置。 */
    private static int program(Path directory, String name) throws Exception {
        // 调用生产包含展开，屏幕光和烟雾必须测试同一个公共遮挡核心。
        int vertex = compile(GL20.GL_VERTEX_SHADER, ScreenLightShaderSources.read(directory.resolve(name + ".vsh")));
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, ScreenLightShaderSources.read(directory.resolve(name + ".fsh")));
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, fragment);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 1, "UV0"); GL20.glBindAttribLocation(program, 2, "Color");
        GL20.glLinkProgram(program);
        require(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0, GL20.glGetProgramInfoLog(program));
        GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment);
        return program;
    }

    /** 编译失败必须中断，不能把无输出误报为安全降级。 */
    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        require(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != 0, GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    /** 自有全屏三角形，关闭颜色混合与深度写入。 */
    private static void setupTriangle() {
        vao = GL30.glGenVertexArrays(); vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1,-1,0, 3,-1,0, -1,3,0}, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false); GL11.glDisable(GL11.GL_BLEND);
    }

    /** 自有颜色目标。 */
    private static int[] target(int width, int height) {
        int texture = color(width, height, new float[] {0,0,0,0});
        int fbo = GL30.glGenFramebuffers(); TARGETS.add(fbo);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE, "test target incomplete");
        return new int[] {fbo, texture};
    }

    /** 颜色使用与生产一致的 RGBA16F，透明度不能被截成 1。 */
    private static int color(int width, int height, float[] rgba) {
        float[] pixels = new float[width * height * 4];
        for (int i = 0; i < pixels.length; i++) { pixels[i] = rgba[i % 4]; }
        return texture(width, height, GL30.GL_RGBA16F, GL11.GL_RGBA, pixels);
    }

    /** 构造真实透视深度；可选前景条用于检测沿光路的遮挡采样。 */
    private static int depth(int width, int height, float distance, boolean occluder) {
        float[] pixels = new float[width * height];
        float value = distance == 0 ? 1 : ((-A * distance + B) / distance) * 0.5F + 0.5F;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = occluder && x >= 10 ? ((-A * 2 + B) / 2) * 0.5F + 0.5F : value;
            }
        }
        return texture(width, height, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, pixels);
    }

    /** 不读取磁盘图像的纯显存测试纹理。 */
    private static int texture(int width, int height, int internal, int format, float[] pixels) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = GL11.glGenTextures(); TEXTURES.add(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        int filter = format == GL11.GL_DEPTH_COMPONENT ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL11.GL_FLOAT, pixels);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        return texture;
    }

    /** 绑定有效采样纹理。 */
    private static void sampler(int program, String name, int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, name), unit);
    }

    /** 运行实际生产片元程序。 */
    private static void render(int[] target, int width, int height) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target[0]);
        GL11.glViewport(0, 0, width, height); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    /** 回读浮点像素并检查每次操作后的真实 GL 错误。 */
    private static float[] pixel(int target, int x, int y) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target);
        float[] pixel = new float[4];
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error on pixel readback");
        for (float channel : pixel) { require(Float.isFinite(channel), "NaN/Infinity output"); }
        return pixel;
    }

    /** 未受光情形必须和原画面一致，包含 Alpha。 */
    private static void background(float[] pixel, String message) {
        for (int i = 0; i < 4; i++) { near(BACKGROUND[i], pixel[i], message + " channel=" + i); }
    }

    /** RGBA16F 的量化容差。 */
    private static void near(float expected, float actual, String message) {
        require(Math.abs(expected - actual) < 0.002F, message + " expected=" + expected + " actual=" + actual);
    }

    /** 与生产着色器完全相同的最终合成，用于核对天空散射的像素期望值。 */
    private static float composite(float background, float contribution) {
        return (float) (background + (1 - Math.exp(-contribution)) * (1 - background) * 0.9);
    }

    /** 不依赖 -ea 的失败断言。 */
    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
