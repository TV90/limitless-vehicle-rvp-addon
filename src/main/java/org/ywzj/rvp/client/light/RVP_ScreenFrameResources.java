package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import java.util.Arrays;

/**
 * 灯光共享场景副本；所有灯共用一次颜色、一次近景和可选的一次 DH 深度，不逐灯创建帧缓冲。
 * 冲击波当前资源封装在私有 pass 内，灯光使用独立目标，不改动既有折射资源。
 */
final class RVP_ScreenFrameResources {
    /** 本帧场景颜色副本。 */
    private int sceneColor;
    /** 本帧场景深度副本，不允许跨帧使用。 */
    private int sceneDepth;
    /** DH 远景深度副本，与近景分别按各自的矩阵重建。 */
    private int distantDepth;
    /** DH 深度宽度，可与主画面的内部渲染分辨率不同。 */
    private int distantWidth;
    /** DH 深度高度。 */
    private int distantHeight;
    /** 只读取外部深度的自有 FBO；每次重新挂接，避免外部重建后编号复用。 */
    private int depthReadFramebuffer;
    /** 光照贡献纹理。 */
    private int lightTexture;
    /** 光照贡献帧缓冲，只写自身附件。 */
    private int lightFramebuffer;
    /** 半分辨率光晕纹理，两次模糊交替使用。 */
    private final int[] bloomTextures = new int[2];
    /** 半分辨率光晕目标。 */
    private final int[] bloomFramebuffers = new int[2];
    /** 独立全屏三角形，避免碰原版 BufferUploader 的缓存。 */
    private int vertexArray;
    /** 全屏三角形顶点缓冲。 */
    private int vertexBuffer;
    /** 场景宽度，单位像素。 */
    private int width;
    /** 场景高度，单位像素。 */
    private int height;
    /** 本帧深度准备完成。 */
    private boolean depthReady;
    /** 深度所属的主目标，窗口重建后不复用旧附件。 */
    private int sourceFramebuffer;

    /** 在光影最终处理和手持物阶段之前保存近景；外部当前绑定的 MRT 不影响只读复制。 */
    boolean captureDepth() {
        Minecraft mc = Minecraft.getInstance();
        // 调用与显卡回归共用的捕获入口；当前外部绑定不代表 Minecraft 主目标无效。
        return captureDepth(mc.getMainRenderTarget().frameBufferId, mc.getMainRenderTarget().getDepthTextureId(),
                mc.getMainRenderTarget().width, mc.getMainRenderTarget().height);
    }

    /** 对已明确归属的世界目标做只读捕获，也供隐藏 GL 上下文验证真实生产实现。 */
    boolean captureDepth(int main, int depth, int requestedWidth, int requestedHeight) {
        depthReady = false;
        if (main <= 0 || depth <= 0 || requestedWidth <= 0 || requestedHeight <= 0) { return false; }
        // 调用完整状态作用域，分配、复制和异常退出均不泄漏绑定或采样器。
        try (State ignored = new State()) {
            ensureSize(requestedWidth, requestedHeight);
            // 调用借用深度读取，不要求当前 DRAW FBO 等于原版目标，也不改外部 FBO 的附件。
            if (!copyDepth(depth, sceneDepth, width, height)) { return false; }
            sourceFramebuffer = main;
            depthReady = true;
            return true;
        }
    }

    /** 独立保存 DH 的真实尺寸深度；只在有活动灯且 DH 本帧提供来源时执行一次。 */
    boolean captureDistantDepth(int sourceTexture) {
        try (State ignored = new State()) {
            if (sourceTexture <= 0 || !GL11.glIsTexture(sourceTexture)) { return false; }
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(sourceTexture);
            int actualWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int actualHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (actualWidth <= 0 || actualHeight <= 0) { return false; }
            if (distantDepth == 0 || distantWidth != actualWidth || distantHeight != actualHeight) {
                if (distantDepth != 0) { RenderSystem.deleteTexture(distantDepth); }
                distantDepth = texture(actualWidth, actualHeight, GL30.GL_DEPTH_COMPONENT32F,
                        GL11.GL_DEPTH_COMPONENT, GL11.GL_NEAREST);
                distantWidth = actualWidth; distantHeight = actualHeight;
            }
            // 调用共享只读复制，每次解绑再挂接，外部以同一编号重建纹理也能读取新对象。
            return copyDepth(sourceTexture, distantDepth, distantWidth, distantHeight);
        }
    }

    /** 从借用深度复制到自有 DEPTH32F；CopyTex 支持来源为 DEPTH24_STENCIL8，不要求 blit 格式相同。 */
    private boolean copyDepth(int sourceTexture, int destination, int expectedWidth, int expectedHeight) {
        if (!GL11.glIsTexture(sourceTexture)) { return false; }
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(sourceTexture);
        if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != expectedWidth
                || GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != expectedHeight) { return false; }
        if (depthReadFramebuffer == 0) {
            depthReadFramebuffer = GL30.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthReadFramebuffer);
            GL11.glDrawBuffer(GL11.GL_NONE); GL11.glReadBuffer(GL11.GL_NONE);
        } else { GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, depthReadFramebuffer); }
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, sourceTexture, 0);
        try {
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) { return false; }
            RenderSystem.bindTexture(destination);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, expectedWidth, expectedHeight);
            return true;
        } finally {
            // 借用期立即结束，后续外部删除纹理时不会因 RVP 仍持有附件而延迟销毁。
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        }
    }

    /** 完整世界颜色只复制一次；必须和提前捕获的深度配对，窗口变化时跳过。 */
    boolean captureColor() {
        Minecraft mc = Minecraft.getInstance();
        // 调用与显卡回归共用的颜色捕获，窗口/目标重建后不能消费旧深度。
        return captureColor(mc.getMainRenderTarget().frameBufferId,
                mc.getMainRenderTarget().width, mc.getMainRenderTarget().height);
    }

    /** 读取光影最终输出的主颜色，不依赖当前绑定是否为 Iris 的别名 FBO。 */
    boolean captureColor(int main, int currentWidth, int currentHeight) {
        boolean ready = depthReady;
        depthReady = false;
        if (!ready || sourceFramebuffer != main || currentWidth != width || currentHeight != height) { return false; }
        // Iris/Oculus 的 final pass 可能绑定一个共用主颜色的自有 FBO；只从明确的主目标读最终颜色。
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebuffer);
        if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) { return false; }
        int previousRead = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        try {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(sceneColor);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        } finally { GL11.glReadBuffer(previousRead); }
        return true;
    }

    /** 只在首次有光或窗口缩放时申请纹理，不触碰磁盘资源。 */
    private void ensureSize(int requestedWidth, int requestedHeight) {
        if (sceneColor != 0 && width == requestedWidth && height == requestedHeight) { return; }
        // 调用独立释放入口，窗口缩放不会积累显存。
        release();
        width = requestedWidth; height = requestedHeight;
        sceneColor = texture(width, height, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_LINEAR);
        sceneDepth = texture(width, height, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_NEAREST);
        lightTexture = texture(width, height, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_LINEAR);
        lightFramebuffer = framebuffer(lightTexture);
        vertexArray = GL30.glGenVertexArrays();
        vertexBuffer = GL15.glGenBuffers();
        GL30.glBindVertexArray(vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1, -1, 0, 3, -1, 0, -1, 3, 0}, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
    }

    /** 光晕单独延迟分配，失败时仍能直接合成基础屏幕光。 */
    void ensureBloom() {
        if (bloomTextures[0] != 0 && bloomTextures[1] != 0) { return; }
        for (int index = 0; index < 2; index++) {
            bloomTextures[index] = texture(bloomWidth(), bloomHeight(), GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_LINEAR);
            bloomFramebuffers[index] = framebuffer(bloomTextures[index]);
        }
    }

    /** 申请自己的纹理，不修改其它模组附件上的过滤/比较模式。 */
    private static int texture(int width, int height, int internal, int format, int filter) {
        int texture = GL11.glGenTextures();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, width, height, 0, format, GL11.GL_FLOAT, 0L);
        return texture;
    }

    /** 检查自身离屏附件完整性，异常由外层合成器限频记录并跳过本帧。 */
    private static int framebuffer(int color) {
        int framebuffer = GL30.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(framebuffer);
            throw new IllegalStateException("RVP lighting framebuffer incomplete");
        }
        return framebuffer;
    }

    /** 设置离屏目标或主画面目标；仅允许写入第一个颜色附件。 */
    void bind(int framebuffer, int width, int height) {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, width, height);
        GL30.glBindVertexArray(vertexArray);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.colorMask(true, true, true, true);
        // 自有采样器为空，防止其它模组绑定的阴影比较采样器污染深度读取。
        if (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects) {
            GL33.glBindSampler(0, 0); GL33.glBindSampler(1, 0); GL33.glBindSampler(2, 0);
        }
    }

    /** 完整屏幕三角形只绘制一次，所有光在同一 Shader 循环内完成。 */
    void draw(ShaderInstance shader) {
        shader.apply();
        try {
            RenderSystem.disableBlend();
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally { shader.clear(); }
    }

    /** 源亮度按 1-(1-源)*(1-背景) 叠加，可达到显示峰值，Alpha 完全沿用背景。 */
    void drawEmission(ShaderInstance shader) {
        shader.apply();
        try {
            // 调用和生产状态回归共用的混合入口，避免 HDR 核心改写原场景透明度。
            beginEmissionBlend();
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally { shader.clear(); }
    }

    /** 单独设置喷口混合，也供真实 GL 状态回归验证，调用方须处在 State 作用域内。 */
    void beginEmissionBlend() {
        RenderSystem.enableBlend();
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
        RenderSystem.blendFuncSeparate(GL11.GL_ONE_MINUS_DST_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
    }

    /** 释放显存；着色器本身由资源重载管理。 */
    void release() {
        depthReady = false;
        if (sceneColor != 0) { RenderSystem.deleteTexture(sceneColor); sceneColor = 0; }
        if (sceneDepth != 0) { RenderSystem.deleteTexture(sceneDepth); sceneDepth = 0; }
        if (distantDepth != 0) { RenderSystem.deleteTexture(distantDepth); distantDepth = 0; }
        if (depthReadFramebuffer != 0) { GL30.glDeleteFramebuffers(depthReadFramebuffer); depthReadFramebuffer = 0; }
        if (lightTexture != 0) { RenderSystem.deleteTexture(lightTexture); lightTexture = 0; }
        if (lightFramebuffer != 0) { GL30.glDeleteFramebuffers(lightFramebuffer); lightFramebuffer = 0; }
        for (int i = 0; i < 2; i++) {
            if (bloomTextures[i] != 0) { RenderSystem.deleteTexture(bloomTextures[i]); bloomTextures[i] = 0; }
            if (bloomFramebuffers[i] != 0) { GL30.glDeleteFramebuffers(bloomFramebuffers[i]); bloomFramebuffers[i] = 0; }
        }
        if (vertexBuffer != 0) { GL15.glDeleteBuffers(vertexBuffer); vertexBuffer = 0; }
        if (vertexArray != 0) { GL30.glDeleteVertexArrays(vertexArray); vertexArray = 0; }
        width = 0; height = 0; distantWidth = 0; distantHeight = 0;
    }

    /** 无需绘制的帧也必须丢弃深度。 */
    void discard() { depthReady = false; }
    /** 场景颜色纹理。 */
    int color() { return sceneColor; }
    /** 场景深度纹理。 */
    int depth() { return sceneDepth; }
    /** DH 自有深度副本。 */
    int distantDepth() { return distantDepth; }
    /** 光贡献纹理。 */
    int light() { return lightTexture; }
    /** 光贡献目标。 */
    int lightTarget() { return lightFramebuffer; }
    /** 半分辨率纹理。 */
    int bloom(int index) { return bloomTextures[index]; }
    /** 半分辨率目标。 */
    int bloomTarget(int index) { return bloomFramebuffers[index]; }
    /** 主目标标识。 */
    int mainTarget() { return sourceFramebuffer; }
    /** 场景宽度。 */
    int width() { return width; }
    /** 场景高度。 */
    int height() { return height; }
    /** 向上取整的半宽。 */
    int bloomWidth() { return Math.max(1, (width + 1) / 2); }
    /** 向上取整的半高。 */
    int bloomHeight() { return Math.max(1, (height + 1) / 2); }

    /** 仅保存本管线会改动的 GL 状态，包含原版缓存、外部采样器和非默认读取目标。 */
    static final class State implements AutoCloseable {
        /** 原绘制目标。 */
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        /** 原读取目标。 */
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        /** 原颜色输出附件列表。 */
        private final int[] drawBuffers = new int[GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)];
        /** 原视口。 */
        private final int[] viewport = new int[4];
        /** 原裁剪矩形。 */
        private final int[] scissor = new int[4];
        /** 原裁剪开关。 */
        private final boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        /** 光影可能使用模板测试，合成后必须恢复。 */
        private final boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        /** 光影遗留的颜色写掩码，不允许屏幕光改变后续 UI 写入。 */
        private final boolean[] colorMask = new boolean[4];
        /** 原深度开关。 */
        private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        /** 原深度写掩码。 */
        private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        /** 原剔除开关。 */
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        /** 原混合开关。 */
        private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        /** RGB 与 Alpha 混合因子。 */
        private final int[] blendFactors = {GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)};
        /** 原 RGB/Alpha 混合方程，喷口独立叠加临时切到加法后必须逐项恢复。 */
        private final int[] blendEquations = {GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),
                GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)};
        /** 原实际着色程序。 */
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        /** RenderSystem 原 Shader 指针。 */
        private final ShaderInstance shader = RenderSystem.getShader();
        /** 原顶点数组。 */
        private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        /** 原顶点缓冲。 */
        private final int buffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        /** 原活动纹理单元。 */
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        /** 三个纹理单元的原绑定，包含最终光晕合成的第三张输入。 */
        private final int[] textures = new int[3];
        /** 三个纹理单元的原独立采样器。 */
        private final int[] samplers = new int[3];
        /** 当前驱动是否支持独立采样器，兼容最低 OpenGL 3.2。 */
        private final boolean samplerSupported = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;

        State() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
            java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) { colorMask[i] = mask.get(i) != 0; }
            for (int i = 0; i < drawBuffers.length; i++) { drawBuffers[i] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + i); }
            for (int i = 0; i < textures.length; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                if (samplerSupported) { samplers[i] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i); }
            }
            GL13.glActiveTexture(activeTexture);
        }

        @Override
        public void close() {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL20.glDrawBuffers(Arrays.copyOf(drawBuffers, drawFramebuffer == 0 ? 1 : drawBuffers.length));
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            for (int i = 0; i < textures.length; i++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i); RenderSystem.bindTexture(textures[i]);
                if (samplerSupported) { GL33.glBindSampler(i, samplers[i]); }
            }
            RenderSystem.activeTexture(activeTexture);
            // 经过引擎入口恢复 FBO/程序，使 Oculus 的状态缓存与真实 GL 保持一致。
            RenderSystem.setShader(() -> shader); GlStateManager._glUseProgram(program);
            // 调用原始能力恢复；这些状态未经过 RenderSystem 改写，其 Java 缓存无需额外改动。
            enabled(GL11.GL_DEPTH_TEST, depth); GL11.glDepthMask(depthMask);
            enabled(GL11.GL_CULL_FACE, cull); enabled(GL11.GL_SCISSOR_TEST, scissorEnabled);
            enabled(GL11.GL_STENCIL_TEST, stencil);
            RenderSystem.colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            RenderSystem.blendFuncSeparate(blendFactors[0], blendFactors[1], blendFactors[2], blendFactors[3]);
            GL20.glBlendEquationSeparate(blendEquations[0], blendEquations[1]);
            if (blend) { RenderSystem.enableBlend(); } else { RenderSystem.disableBlend(); }
        }

        /** 恢复一个未经过缓存改写的 GL 能力开关。 */
        private static void enabled(int capability, boolean value) {
            if (value) { GL11.glEnable(capability); } else { GL11.glDisable(capability); }
        }
    }
}
