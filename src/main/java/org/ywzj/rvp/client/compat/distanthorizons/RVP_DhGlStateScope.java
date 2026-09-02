package org.ywzj.rvp.client.compat.distanthorizons;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.BufferUtils;
import net.minecraft.client.renderer.ShaderInstance;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** 在 DH 回调内完整保存并恢复 RVP 会触碰的 OpenGL 状态。 */
final class RVP_DhGlStateScope implements AutoCloseable {
    /** 本兼容 pass 最多使用的二维纹理单元数。 */
    private static final int TRACKED_TEXTURE_UNITS = 3;
    /** 进入作用域前的 draw framebuffer。 */
    private final int drawFramebuffer;
    /** 进入作用域前的 read framebuffer。 */
    private final int readFramebuffer;
    /** 进入作用域前的 viewport。 */
    private final int[] viewport;
    /** 进入作用域前是否启用 scissor。 */
    private final boolean scissorEnabled;
    /** 进入作用域前的 scissor box。 */
    private final int[] scissorBox;
    /** 进入作用域前的 shader program。 */
    private final int program;
    /** 进入作用域前 RenderSystem 记录的 ShaderInstance。 */
    private final ShaderInstance renderSystemShader;
    /** 进入作用域前的 VAO。 */
    private final int vertexArray;
    /** 进入作用域前的活动纹理单元。 */
    private final int activeTexture;
    /** 从纹理单元零开始的二维纹理绑定。 */
    private final int[] texture2dBindings;
    /** 进入作用域前是否启用深度测试。 */
    private final boolean depthTestEnabled;
    /** 进入作用域前的深度比较函数。 */
    private final int depthFunction;
    /** 进入作用域前的深度写掩码。 */
    private final boolean depthMask;
    /** 进入作用域前的清除深度值。 */
    private final double clearDepth;
    /** 进入作用域前是否启用混合。 */
    private final boolean blendEnabled;
    /** 进入作用域前的 RGB 源混合因子。 */
    private final int blendSourceRgb;
    /** 进入作用域前的 RGB 目标混合因子。 */
    private final int blendDestinationRgb;
    /** 进入作用域前的 Alpha 源混合因子。 */
    private final int blendSourceAlpha;
    /** 进入作用域前的 Alpha 目标混合因子。 */
    private final int blendDestinationAlpha;
    /** 进入作用域前的 RGB 混合方程。 */
    private final int blendEquationRgb;
    /** 进入作用域前的 Alpha 混合方程。 */
    private final int blendEquationAlpha;
    /** 进入作用域前是否启用面剔除。 */
    private final boolean cullEnabled;
    /** 进入作用域前的 RGBA 写掩码。 */
    private final boolean[] colorMask;
    /** 进入作用域前的清屏颜色。 */
    private final float[] clearColor;
    /** 是否已经完成恢复。 */
    private boolean closed;

    private RVP_DhGlStateScope() {
        RenderSystem.assertOnRenderThread();
        drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        viewport = readIntegers(GL11.GL_VIEWPORT, 4);
        scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        scissorBox = readIntegers(GL11.GL_SCISSOR_BOX, 4);
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        renderSystemShader = RenderSystem.getShader();
        vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        texture2dBindings = new int[TRACKED_TEXTURE_UNITS];
        for (int unit = 0; unit < TRACKED_TEXTURE_UNITS; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            texture2dBindings[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        GL13.glActiveTexture(activeTexture);
        depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        blendDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        colorMask = readBooleans(GL11.GL_COLOR_WRITEMASK, 4);
        clearColor = readFloats(GL11.GL_COLOR_CLEAR_VALUE, 4);
    }

    /** 捕获当前 GL 状态并打开异常安全作用域。 */
    static RVP_DhGlStateScope capture() {
        return new RVP_DhGlStateScope();
    }

    /** 无论正常返回或异常均恢复进入回调前的完整状态。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        setEnabled(GL11.GL_SCISSOR_TEST, scissorEnabled);
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        if (renderSystemShader != null) {
            // 调用 RenderSystem 恢复其 Java 侧 shader 指针，避免只恢复 GL program 后缓存失配。
            RenderSystem.setShader(() -> renderSystemShader);
        }
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vertexArray);
        for (int unit = 0; unit < TRACKED_TEXTURE_UNITS; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture2dBindings[unit]);
        }
        GL13.glActiveTexture(activeTexture);
        setEnabled(GL11.GL_DEPTH_TEST, depthTestEnabled);
        GL11.glDepthFunc(depthFunction);
        GL11.glDepthMask(depthMask);
        GL11.glClearDepth(clearDepth);
        setEnabled(GL11.GL_BLEND, blendEnabled);
        GL14.glBlendFuncSeparate(blendSourceRgb, blendDestinationRgb,
                blendSourceAlpha, blendDestinationAlpha);
        GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
        setEnabled(GL11.GL_CULL_FACE, cullEnabled);
        GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
    }

    /** 读取固定长度的整数向量状态。 */
    private static int[] readIntegers(int state, int count) {
        IntBuffer buffer = BufferUtils.createIntBuffer(count);
        GL11.glGetIntegerv(state, buffer);
        int[] values = new int[count];
        buffer.get(values);
        return values;
    }

    /** 读取固定长度的布尔向量状态。 */
    private static boolean[] readBooleans(int state, int count) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(count);
        GL11.glGetBooleanv(state, buffer);
        boolean[] values = new boolean[count];
        for (int index = 0; index < count; index++) {
            values[index] = buffer.get(index) != 0;
        }
        return values;
    }

    /** 读取固定长度的浮点向量状态。 */
    private static float[] readFloats(int state, int count) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(count);
        GL11.glGetFloatv(state, buffer);
        float[] values = new float[count];
        buffer.get(values);
        return values;
    }

    /** 按捕获值启用或关闭一个 OpenGL capability。 */
    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }
}
