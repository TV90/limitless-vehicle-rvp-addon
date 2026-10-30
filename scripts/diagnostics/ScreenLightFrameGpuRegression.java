package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 在真实 Minecraft 类路径、隐藏 GL 上下文中验证生产帧资源；不初始化游戏或访问玩家存档。 */
public final class ScreenLightFrameGpuRegression {
    /** 测试所有的纹理；不会删除任何磁盘文件。 */
    private static final List<Integer> TEXTURES = new ArrayList<>();
    /** 测试所有的 FBO，与 RVP 自有 FBO 分开释放。 */
    private static final List<Integer> FRAMEBUFFERS = new ArrayList<>();
    /** 源场景颜色，含非 1 的 Alpha。 */
    private static final float[] COLOR = {0.12F, 0.24F, 0.36F, 0.42F};
    /** 测试所有的独立采样器。 */
    private static final int[] SAMPLERS = new int[3];
    /** 模拟外部渲染器正在使用的三个纹理。 */
    private static final int[] EXTERNAL_TEXTURES = new int[3];
    /** 模拟光影的多个颜色输出。 */
    private static int externalDraw;
    /** 模拟单独绑定的读取目标。 */
    private static int externalRead;
    /** 模拟外部 VAO，验证生产层不会破坏后续绘制。 */
    private static int externalVao;
    /** 模拟外部顶点缓冲。 */
    private static int externalBuffer;

    public static void main(String[] args) {
        require(GLFW.glfwInit(), "GLFW initialization");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "RVP production frame regression", 0, 0);
        require(window != 0, "hidden GL context");
        RVP_ScreenFrameResources frame = new RVP_ScreenFrameResources();
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            System.out.println("GPU=" + GL11.glGetString(GL11.GL_RENDERER));
            int worldDepth = texture(16, 16, true, 0.75F);
            int worldColor = texture(16, 16, false, 0);
            int world = framebuffer(worldColor, worldDepth);
            int distant = texture(8, 8, true, 0.25F);
            prepareExternalState();
            require(frame.captureDepth(world, worldDepth, 16, 16), "near scene copy failed under external MRT");
            assertExternalState();
            near(0.75F, read(frame.depth(), 16, 16, true)[0], "production near-depth copy");
            require(frame.captureDistantDepth(distant), "DH depth copy failed");
            assertExternalState();
            near(0.25F, read(frame.distantDepth(), 8, 8, true)[0], "production DH-depth copy");
            try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
                require(frame.captureColor(world, 16, 16), "final color copy rejected Iris alias FBO or viewport");
            }
            assertExternalState();
            float[] copiedColor = read(frame.color(), 16, 16, false);
            for (int i = 0; i < 4; i++) { near(COLOR[i], copiedColor[i], "production final color/alpha"); }
            System.out.println("PASS production near/DH/final-color copies under external MRT and offset viewport");

            try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
                frame.bind(frame.lightTarget(), 16, 16);
                require(!GL11.glIsEnabled(GL11.GL_STENCIL_TEST), "stencil was not isolated");
                for (int i = 0; i < 3; i++) {
                    require(GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i) == 0, "shadow sampler leaked into lighting");
                }
                byte[] mask = mask();
                for (byte channel : mask) { require(channel != 0, "color write mask was not isolated"); }
                // 调用生产喷口混合入口，验证 HDR 高亮使用屏幕叠加且不写背景 Alpha。
                frame.beginEmissionBlend();
                require(GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB) == GL14.GL_FUNC_ADD, "source RGB equation");
                require(GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB) == GL11.GL_ONE_MINUS_DST_COLOR, "source screen blend");
                require(GL11.glGetInteger(GL14.GL_BLEND_DST_RGB) == GL11.GL_ONE, "destination screen blend");
                require(GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA) == GL11.GL_ZERO, "source alpha preservation");
                require(GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA) == GL11.GL_ONE, "destination alpha preservation");
            }
            assertExternalState();
            System.out.println("PASS production scope restores draw/read FBO, MRT outputs, viewport, VAO/VBO, textures, samplers, masks and source-blend equations");

            // 同一编号换尺寸，检查每帧重新挂接且深度副本会跟随重建，旧尺寸外也必须有效。
            try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0);
                RenderSystem.bindTexture(distant);
                float[] resized = new float[32 * 32]; Arrays.fill(resized, 0.6F);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, 32, 32, 0,
                        GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, resized);
            }
            require(frame.captureDistantDepth(distant), "same-ID DH resize copy failed");
            near(0.6F, read(frame.distantDepth(), 32, 32, true)[31 * 32 + 31], "DH copied beyond old dimensions");
            assertExternalState();
            require(!frame.captureColor(world, 16, 16), "consumed depth was reused");
            require(frame.captureDepth(world, worldDepth, 16, 16), "new near capture failed");
            require(!frame.captureColor(externalDraw, 16, 16), "rebuilt target consumed old depth");
            require(frame.captureDepth(world, worldDepth, 16, 16), "new near capture failed");
            frame.discard();
            require(!frame.captureColor(world, 16, 16), "discarded frame was reused");
            System.out.println("PASS production same-ID DH resize, single-use depth, target identity and discarded-frame checks");
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error at completion");
            System.out.println("PASS all production screen-frame GPU regressions; OpenGL error=0");
        } finally {
            frame.release();
            for (int sampler : SAMPLERS) { if (sampler != 0) { GL33.glDeleteSamplers(sampler); } }
            for (int framebuffer : FRAMEBUFFERS) { GL30.glDeleteFramebuffers(framebuffer); }
            for (int texture : TEXTURES) { GL11.glDeleteTextures(texture); }
            if (externalVao != 0) { GL30.glDeleteVertexArrays(externalVao); }
            if (externalBuffer != 0) { GL15.glDeleteBuffers(externalBuffer); }
            GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
        }
    }

    /** 模拟光影留下的 MRT、阴影采样器、模板和颜色写掩码。 */
    private static void prepareExternalState() {
        for (int i = 0; i < 3; i++) { EXTERNAL_TEXTURES[i] = texture(16, 16, false, 0); }
        externalDraw = framebuffer(EXTERNAL_TEXTURES[0], 0);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1,
                GL11.GL_TEXTURE_2D, EXTERNAL_TEXTURES[1], 0);
        GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT1, GL30.GL_COLOR_ATTACHMENT0});
        externalRead = framebuffer(EXTERNAL_TEXTURES[2], 0);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, externalDraw);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, externalRead);
        GL11.glViewport(3, 4, 9, 10);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        RenderSystem.colorMask(false, true, false, false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_REVERSE_SUBTRACT, GL14.GL_FUNC_SUBTRACT);
        externalVao = GL30.glGenVertexArrays(); externalBuffer = GL15.glGenBuffers();
        GL30.glBindVertexArray(externalVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, externalBuffer);
        for (int i = 0; i < 3; i++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i); RenderSystem.bindTexture(EXTERNAL_TEXTURES[i]);
            SAMPLERS[i] = GL33.glGenSamplers();
            GL33.glSamplerParameteri(SAMPLERS[i], GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
            GL33.glBindSampler(i, SAMPLERS[i]);
        }
        RenderSystem.activeTexture(GL13.GL_TEXTURE5);
    }

    /** 验证真实 GL 状态；不能只验证 Java 保存值，因为光影另外维护缓存。 */
    private static void assertExternalState() {
        require(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == externalDraw, "draw FBO restore");
        require(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) == externalRead, "read FBO restore");
        require(GL11.glGetInteger(GL20.GL_DRAW_BUFFER0) == GL30.GL_COLOR_ATTACHMENT1, "MRT output 0 restore");
        require(GL11.glGetInteger(GL20.GL_DRAW_BUFFER1) == GL30.GL_COLOR_ATTACHMENT0, "MRT output 1 restore");
        int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        require(Arrays.equals(viewport, new int[] {3,4,9,10}), "viewport restore");
        require(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) == externalVao, "VAO restore");
        require(GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) == externalBuffer, "VBO restore");
        require(GL11.glIsEnabled(GL11.GL_STENCIL_TEST), "stencil restore");
        require(Arrays.equals(mask(), new byte[] {0,1,0,0}), "color-mask restore");
        require(GL11.glIsEnabled(GL11.GL_BLEND), "blend-enable restore");
        require(GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB) == GL14.GL_FUNC_REVERSE_SUBTRACT, "RGB blend equation restore");
        require(GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA) == GL14.GL_FUNC_SUBTRACT, "alpha blend equation restore");
        require(GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB) == GL11.GL_SRC_ALPHA, "RGB source factor restore");
        require(GL11.glGetInteger(GL14.GL_BLEND_DST_RGB) == GL11.GL_ONE_MINUS_SRC_ALPHA, "RGB destination factor restore");
        require(GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA) == GL11.GL_ONE, "alpha source factor restore");
        require(GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA) == GL11.GL_ZERO, "alpha destination factor restore");
        require(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE5, "active texture restore");
        for (int i = 0; i < 3; i++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
            require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == EXTERNAL_TEXTURES[i], "texture restore " + i);
            require(GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i) == SAMPLERS[i], "sampler restore " + i);
        }
        RenderSystem.activeTexture(GL13.GL_TEXTURE5);
    }

    /** 读取真实颜色写掩码。 */
    private static byte[] mask() {
        java.nio.ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, buffer);
        return new byte[] {buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3)};
    }

    /** 纹理回读也通过生产状态作用域，不干扰待验证的外部绑定。 */
    private static float[] read(int texture, int width, int height, boolean depth) {
        try (RVP_ScreenFrameResources.State ignored = new RVP_ScreenFrameResources.State()) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0); RenderSystem.bindTexture(texture);
            float[] values = new float[width * height * (depth ? 1 : 4)];
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, depth ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA, GL11.GL_FLOAT, values);
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "production copy readback GL error");
            return values;
        }
    }

    /** 创建源纹理；全部是测试自己的 GPU 资源。 */
    private static int texture(int width, int height, boolean depth, float value) {
        int texture = GL11.glGenTextures(); TEXTURES.add(texture);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0); RenderSystem.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        float[] values = new float[width * height * (depth ? 1 : 4)];
        for (int i = 0; i < values.length; i++) { values[i] = depth ? value : COLOR[i % 4]; }
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, depth ? GL30.GL_DEPTH_COMPONENT32F : GL30.GL_RGBA16F,
                width, height, 0, depth ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA, GL11.GL_FLOAT, values);
        return texture;
    }

    /** 创建有明确所有权的主画面/外部别名目标。 */
    private static int framebuffer(int color, int depth) {
        int framebuffer = GL30.glGenFramebuffers(); FRAMEBUFFERS.add(framebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        if (depth != 0) { GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth, 0); }
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        require(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE, "test FBO completeness");
        return framebuffer;
    }

    /** 浮点纹理量化容差。 */
    private static void near(float expected, float actual, String reason) {
        require(Math.abs(expected - actual) < 0.002F, reason + " expected=" + expected + " actual=" + actual);
    }

    /** 明确失败，不能把没绘制误记为通过。 */
    private static void require(boolean condition, String reason) { if (!condition) { throw new AssertionError(reason); } }
}
