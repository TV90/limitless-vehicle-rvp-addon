package org.ywzj.rvp.client.visual.shockwave;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.render.RVP_GlStateSync;
import org.ywzj.rvp.client.render.RVP_BlendModeState;
import org.ywzj.rvp.weapon.visual.RVP_PressureWaveGeometry;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 正面压力波合成：提前保存世界深度，最后读取背景，中心通透、白雾内浓外淡，外层保留透明空气折射。
 * 不改主画面深度，不借用其它模组的纹理，也不假定当前一定绑定 Minecraft 主缓冲。
 */
final class RVP_PressureWavePass {
    /** 只读首帧状态日志，供实际客户端区分程序、附件和外部采样状态故障。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 新增、经用户确认的独立绘制程序。 */
    private ShaderInstance shader;
    /** 当前背景副本；半精度浮点避免光影画面中超过 1 的颜色被截断。 */
    private int sceneColor;
    /** 当前深度副本，使用最近点采样，避免物体边缘的深度被插值。 */
    private int sceneDepth;
    /** 副本宽度（像素）。 */
    private int width;
    /** 副本高度（像素）。 */
    private int height;
    /** 本帧世界深度是否已保存；一次合成后立即失效，禁止沿用上一帧的遮挡。 */
    private boolean depthReady;
    /** 保存深度时的视口（左、下、宽、高）；合成时须完全一致，避免窗口变化导致错位。 */
    private final int[] depthViewport = new int[4];
    /** 只属于本合成器的全屏三角形顶点数组，不触碰原版 BufferUploader 的绑定缓存。 */
    private int vertexArray;
    /** 全屏三角形顶点缓冲。 */
    private int vertexBuffer;

    /**
     * 一帧内不可变的空气层输入。
     * @param center 爆心观察空间坐标
     * @param innerRadius 内半径（格）
     * @param outerRadius 外半径（格）
     * @param brightness 凝结雾强度
     * @param fade 整体淡出与透明度倍率
     * @param refraction 背景折射倍率
     * @param dispersion 折射色散倍率
     * @param age 标准动画年龄（tick）
     * @param bounds 保守屏幕范围
     * @param noiseOrigin 按爆心区分的低频雾形偏移，不随相机转动
     */
    record Wave(Vector3f center, float innerRadius, float outerRadius, float brightness, float fade,
                float refraction, float dispersion, float age, RVP_PressureWaveGeometry.Bounds bounds,
                Vector3f noiseOrigin) { }

    /** 内浓外淡的凝结雾之后叠加透明压力波前；两层中心通透，波前不额外加白光。 */
    static void appendLayers(List<Wave> target, Wave body, RVP_ShockwaveRuntimeTuning.Snapshot tuning,
            float standardAge) {
        target.add(body);
        // 调用独立推力包络，仅加强短促的背景推挤和其后回弹，不改变已认可的雾量、范围与时长。
        float force = tuning.pressureRefraction(standardAge);
        if (force * body.fade <= 0.0001F) { return; }
        // 调用前沿厚度换算，把折射集中到外侧薄层，避免整团空气均匀晃动、显得松软。
        float width = tuning.pressureThickness(standardAge);
        if (width <= 0.0001F) { return; }
        target.add(new Wave(body.center, body.outerRadius * (1 - width), body.outerRadius,
                0, body.fade, force, body.dispersion, body.age, body.bounds, body.noiseOrigin));
    }

    void registerShader(RegisterShadersEvent event) throws IOException {
        // 调用 RVP 资源命名入口，注册获准新增的内浓外淡程序；正面中心仍保持通透。
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                RVP_MOD.modLocation("pressure_wave_mist_gradient"), DefaultVertexFormat.POSITION), loaded -> shader = loaded);
    }

    /**
     * 在世界绘制阶段保存真实遮挡；末尾的过载、热成像等画面处理可能把主缓冲深度写成整屏常数。
     * 这里只复制深度，颜色仍在完整世界合成后读取，以兼容 Fabulous 的天气独立缓冲。
     */
    boolean captureDepth() {
        RenderSystem.assertOnRenderThread();
        depthReady = false;
        if (shader == null) { return false; }
        // 调用状态作用域，复制过程中不改变其它世界效果使用的绑定、裁剪或采样状态。
        try (State state = new State()) {
            // 调用实际附件查询；不假定世界一定使用 Minecraft 主缓冲或某一种深度格式。
            if (state.viewport[2] <= 0 || state.viewport[3] <= 0 || state.drawBuffers[0] == GL11.GL_NONE
                    || !state.hasDepth()) { return false; }
            // 调用副本分配和深度复制，每帧只在后处理之前读取一次世界遮挡。
            ensureSize(state.viewport[2], state.viewport[3]);
            copyScene(sceneDepth, state.viewport);
            System.arraycopy(state.viewport, 0, depthViewport, 0, depthViewport.length);
            depthReady = true;
            return true;
        }
    }

    /** 本帧没有可见波面或世界已失效时，丢弃未消费的深度，不能供下一帧使用。 */
    void discardDepth() { depthReady = false; }

    /** 只在新波面的首帧由调试日志调用；不绑定或修改任何绘制状态。 */
    void logFrameState() {
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int attachment = framebuffer == 0 ? GL11.GL_DEPTH : GL30.GL_DEPTH_ATTACHMENT;
        int depthType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        // 仅在显卡支持时查询独立采样器对象，调试日志也须兼容原版允许的 OpenGL 3.2。
        var capabilities = GL.getCapabilities();
        boolean samplerSupported = capabilities.OpenGL33 || capabilities.GL_ARB_sampler_objects;
        int sampler0 = samplerSupported ? GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 0) : -1;
        int sampler1 = samplerSupported ? GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 1) : -1;
        LOGGER.debug("RVP冲击波绘制状态: shaderLoaded={} worldDepthReady={} framebuffer={} drawBuffer={} depthType={} sampler0={} sampler1={} depthMinFilter={}",
                shader != null, depthReady, framebuffer, GL11.glGetInteger(GL20.GL_DRAW_BUFFER0), depthType, sampler0, sampler1,
                sampler1 <= 0 ? 0 : GL33.glGetSamplerParameteri(sampler1, GL11.GL_TEXTURE_MIN_FILTER));
    }

    void render(Matrix4f projection, Matrix4f view, List<Wave> waves) {
        boolean ready = depthReady;
        depthReady = false;
        if (shader == null || !ready) { return; }
        RenderSystem.assertOnRenderThread();
        // 调用状态作用域，在异常、窗口缩放和不同光影缓冲下都恢复调用前的 GL 状态。
        try (State state = new State()) {
            // 两阶段视口必须相同；窗口变化时跳过本帧，下一帧重新保存，不能临时读取已被改写的深度。
            if (state.viewport[2] <= 0 || state.viewport[3] <= 0 || state.drawBuffers[0] == GL11.GL_NONE
                    || !Arrays.equals(state.viewport, depthViewport)) { return; }
            shader.setSampler("SceneColor", sceneColor);
            shader.setSampler("SceneDepth", sceneDepth);
            shader.safeGetUniform("InverseProjection").set(new Matrix4f(projection).invert());
            shader.safeGetUniform("ViewToWorld").set(new Matrix3f(view).invert());
            shader.safeGetUniform("ProjectionScale").set(projection.m00(), projection.m11());
            shader.safeGetUniform("ScreenSize").set((float) width, (float) height);
            GL30.glBindVertexArray(vertexArray);
            // 调用双向状态同步，不能继承 DH 回调留下的错误引擎缓存。
            RVP_GlStateSync.depthTest(false);
            RVP_GlStateSync.depthMask(false);
            RVP_GlStateSync.cull(false);
            RVP_GlStateSync.scissor(true);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            RVP_GlStateSync.colorMask(true, true, true, true);
            // 场景与深度使用各自纹理参数，不能沿用外部阴影比较或要求 mipmap 的采样器。
            if (state.samplerSupported) {
                GL33.glBindSampler(0, 0); GL33.glBindSampler(1, 0);
            }
            // 只写调用方正在显示的第一个颜色附件，不能污染光影的其它材质缓冲。
            GL11.glDrawBuffer(state.drawBuffers[0]);
            for (Wave wave : waves) {
                // 调用范围裁剪，只在球体覆盖的屏幕部分积分；尊重调用方原有剪裁区域。
                if (!state.scissor(wave.bounds)) { continue; }
                // 每层都读取前一层合成后的颜色，重叠爆炸不会互相擦掉，深度仍共享同一份副本。
                copyScene(sceneColor, state.viewport);
                shader.safeGetUniform("CenterView").set(wave.center);
                shader.safeGetUniform("Radii").set(wave.innerRadius, wave.outerRadius);
                shader.safeGetUniform("Brightness").set(wave.brightness);
                shader.safeGetUniform("LifeFade").set(wave.fade);
                shader.safeGetUniform("Refraction").set(wave.refraction);
                shader.safeGetUniform("Dispersion").set(wave.dispersion);
                shader.safeGetUniform("Age").set(wave.age);
                shader.safeGetUniform("NoiseOrigin").set(wave.noiseOrigin);
                shader.apply();
                try {
                    // 输出的是已包含原背景的最终颜色，关闭混合，避免把背景重复叠亮。
                    // 调用强制同步，确保完整背景不会在残留减法混合下被减成黑色。
                    RVP_GlStateSync.blend(false);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                } finally {
                    shader.clear();
                }
            }
        }
    }

    /** 只在首次使用或窗口尺寸改变时创建显存资源；不生成或修改任何磁盘贴图。 */
    private void ensureSize(int requestedWidth, int requestedHeight) {
        if (sceneColor != 0 && width == requestedWidth && height == requestedHeight) { return; }
        // 调用本合成器的释放入口，窗口缩放后不保留旧尺寸显存。
        release();
        width = requestedWidth;
        height = requestedHeight;
        // 调用统一纹理分配，颜色采用线性采样，深度采用最近点采样。
        sceneColor = createTexture(GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_LINEAR);
        sceneDepth = createTexture(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_NEAREST);
        vertexArray = GL30.glGenVertexArrays();
        vertexBuffer = GL15.glGenBuffers();
        GL30.glBindVertexArray(vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[] {-1, -1, 0, 3, -1, 0, -1, 3, 0}, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0L);
    }

    /** 创建只属于本合成器的二维纹理，不改变调用方场景纹理的采样参数。 */
    private int createTexture(int internalFormat, int format, int filter) {
        int texture = GL11.glGenTextures();
        // 调用双向纹理同步，新建显存不允许写到外部原生恢复后的错误绑定上。
        RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE0);
        RVP_GlStateSync.bindTexture2D(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, GL11.GL_FLOAT, 0L);
        return texture;
    }

    /** 从实际绘制缓冲的当前视口复制；深度复制允许原场景使用不同精度的深度格式。 */
    private static void copyScene(int texture, int[] viewport) {
        // 调用双向纹理同步，颜色和深度副本始终写入指定的自有对象。
        RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE0);
        RVP_GlStateSync.bindTexture2D(texture);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    /** 退出世界或缩放窗口时释放临时显存，注册的着色器由 Minecraft 资源重载管理。 */
    void release() {
        depthReady = false;
        Arrays.fill(depthViewport, 0);
        if (sceneColor != 0) { RenderSystem.deleteTexture(sceneColor); sceneColor = 0; }
        if (sceneDepth != 0) { RenderSystem.deleteTexture(sceneDepth); sceneDepth = 0; }
        if (vertexBuffer != 0) { GL15.glDeleteBuffers(vertexBuffer); vertexBuffer = 0; }
        if (vertexArray != 0) { GL30.glDeleteVertexArrays(vertexArray); vertexArray = 0; }
        width = 0;
        height = 0;
    }

    /** 只保存本 pass 会触碰的状态；不改观察矩阵、投影矩阵、视口或世界深度。 */
    private static final class State implements AutoCloseable {
        /** 原 Shader/Effect 混合调用历史，不能用进入时实际的 GL_BLEND 开关替代。 */
        private final RVP_BlendModeState blendModeState = RVP_BlendModeState.capture();
        /** 进入时实际绘制缓冲，不能替换成猜测的主缓冲。 */
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        /** 进入时读取缓冲。 */
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        /** 绘制缓冲原有的读取附件。 */
        private final int sourceReadBuffer;
        /** 绘制缓冲原有的颜色输出附件列表。 */
        private final int[] drawBuffers;
        /** 原视口，复制时保留其偏移量。 */
        private final int[] viewport = new int[4];
        /** 调用方是否已启用剪裁。 */
        private final boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        /** 调用方原剪裁矩形。 */
        private final int[] scissorBox = new int[4];
        /** 原着色程序。 */
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        /** RenderSystem 的原 Java 着色器指针。 */
        private final ShaderInstance previousShader = RenderSystem.getShader();
        /** 原顶点数组。 */
        private final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        /** 原顶点缓冲。 */
        private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        /** 原活动纹理单元。 */
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        /** 本程序使用的两个纹理单元的原绑定。 */
        private final int[] textures = new int[2];
        /** 两个输入单元原有的独立采样器；没有独立采样器支持时不查询、不调用。 */
        private final int[] samplers = new int[2];
        /** 保持原版允许的 OpenGL 3.2 下限，不能无条件调用 GL 3.3 采样器入口。 */
        private final boolean samplerSupported = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
        /** 原模板测试开关，特效只在自己的作用域内关闭。 */
        private final boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        /** 原颜色写掩码，防止合成被外部遮罩截成缺色画面。 */
        private final boolean[] colorMask = new boolean[4];
        /** 原深度测试开关。 */
        private final boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        /** 原深度写掩码。 */
        private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        /** 原背面剔除开关。 */
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        /** 原混合开关。 */
        private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        /** 原 RGB 源混合因子。 */
        private final int blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        /** 原 RGB 目标混合因子。 */
        private final int blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        /** 原 Alpha 源混合因子。 */
        private final int blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        /** 原 Alpha 目标混合因子。 */
        private final int blendDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        /** 原 RGB 混合方程；仅恢复因子并不能撤销 GL_MAX、减法等方程。 */
        private final int blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        /** 原 Alpha 混合方程，允许与 RGB 不同。 */
        private final int blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);

        private State() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int channel = 0; channel < colorMask.length; channel++) { colorMask[channel] = mask.get(channel) != 0; }
            int[] attachments = new int[GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)];
            int count = 1;
            for (int index = 0; index < attachments.length; index++) {
                attachments[index] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + index);
                if (attachments[index] != GL11.GL_NONE) { count = index + 1; }
            }
            drawBuffers = Arrays.copyOf(attachments, count);
            for (int unit = 0; unit < textures.length; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                textures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                if (samplerSupported) { samplers[unit] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, unit); }
            }
            GL13.glActiveTexture(activeTexture);
            // 调用双向 FBO 同步，读取目标与当前绘制目标配对，兼容已安装 Oculus 的无光影路径。
            RVP_GlStateSync.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFramebuffer);
            sourceReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            GL11.glReadBuffer(drawBuffers[0]);
        }

        /** 检查实际绘制缓冲的深度附件，兼容纹理、渲染缓冲以及窗口默认缓冲。 */
        private boolean hasDepth() {
            int attachment = drawFramebuffer == 0 ? GL11.GL_DEPTH : GL30.GL_DEPTH_ATTACHMENT;
            return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11.GL_NONE
                    && GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE) > 0;
        }

        /** 把标准化范围转成窗口像素，并与原剪裁相交。 */
        private boolean scissor(RVP_PressureWaveGeometry.Bounds bounds) {
            int left = viewport[0] + (int) Math.floor((bounds.left() * 0.5F + 0.5F) * viewport[2]);
            int bottom = viewport[1] + (int) Math.floor((bounds.bottom() * 0.5F + 0.5F) * viewport[3]);
            int right = viewport[0] + (int) Math.ceil((bounds.right() * 0.5F + 0.5F) * viewport[2]);
            int top = viewport[1] + (int) Math.ceil((bounds.top() * 0.5F + 0.5F) * viewport[3]);
            if (scissorEnabled) {
                left = Math.max(left, scissorBox[0]);
                bottom = Math.max(bottom, scissorBox[1]);
                right = Math.min(right, scissorBox[0] + scissorBox[2]);
                top = Math.min(top, scissorBox[1] + scissorBox[3]);
            }
            if (right <= left || top <= bottom) { return false; }
            GL11.glScissor(left, bottom, right - left, top - bottom);
            return true;
        }

        @Override
        public void close() {
            // 调用双向状态恢复，不能只恢复显卡而把引擎或 Oculus 的缓存留在特效状态。
            RVP_GlStateSync.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL20.glDrawBuffers(drawBuffers);
            RVP_GlStateSync.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFramebuffer);
            GL11.glReadBuffer(sourceReadBuffer);
            RVP_GlStateSync.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            for (int unit = 0; unit < textures.length; unit++) {
                RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE0 + unit);
                RVP_GlStateSync.bindTexture2D(textures[unit]);
                if (samplerSupported) { GL33.glBindSampler(unit, samplers[unit]); }
            }
            RVP_GlStateSync.activeTexture(activeTexture);
            RenderSystem.setShader(() -> previousShader);
            RVP_GlStateSync.useProgram(program);
            RVP_GlStateSync.depthTest(depthTest);
            RVP_GlStateSync.depthMask(depthMask);
            RVP_GlStateSync.cull(cull);
            RVP_GlStateSync.scissor(scissorEnabled);
            restoreEnabled(GL11.GL_STENCIL_TEST, stencil);
            RVP_GlStateSync.colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            // 调用完整混合恢复，因子、RGB/Alpha 方程、实际开关与 Shader 的 BlendMode 缓存一同恢复。
            RVP_GlStateSync.restoreBlend(blend, blendSourceRgb, blendDestinationRgb,
                    blendSourceAlpha, blendDestinationAlpha, blendEquationRgb, blendEquationAlpha);
            // 调用独立历史恢复，冲击波不改变其后本体过载滤镜对混合状态的判断。
            blendModeState.restore();
        }

        /** 恢复未经过 RenderSystem 缓存修改的能力开关。 */
        private static void restoreEnabled(int capability, boolean enabled) {
            if (enabled) { GL11.glEnable(capability); } else { GL11.glDisable(capability); }
        }
    }
}
