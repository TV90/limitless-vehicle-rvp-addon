package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * RVP 自绘通道的显卡状态交接；先设置真实 GL，再经过引擎入口同步其缓存。
 *
 * <p>DH 回调和其它模组可能使用原生 GL。只调用 RenderSystem 会因缓存命中而跳过真正的操作，
 * 只调用原生 GL 又会让后续引擎调用继续使用错误缓存，因此两步必须成对。</p>
 */
public final class RVP_GlStateSync {
    private RVP_GlStateSync() { }

    /** 切换真实活动纹理单元，并同步 Minecraft 缓存；参数为 GL_TEXTURE0 加单元下标。 */
    public static void activeTexture(int unit) {
        GL13.glActiveTexture(unit);
        RenderSystem.activeTexture(unit);
    }

    /** 调用方须先用本类 activeTexture 选单元，保证原生绑定和引擎绑定落在同一单元。 */
    public static void bindTexture2D(int texture) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        RenderSystem.bindTexture(texture);
    }

    /** 同步真实 FBO 与 Oculus 的绑定缓存；Oculus 已安装但未开光影时也需要走引擎入口。 */
    public static void bindFramebuffer(int target, int framebuffer) {
        GL30.glBindFramebuffer(target, framebuffer);
        GlStateManager._glBindFramebuffer(target, framebuffer);
    }

    /** 同步真实着色程序与第三方引擎缓存，不能仅恢复 RenderSystem 的 Java Shader 指针。 */
    public static void useProgram(int program) {
        GL20.glUseProgram(program);
        GlStateManager._glUseProgram(program);
    }

    /** 不依赖进入作用域时的引擎缓存，可靠设置深度测试开关。 */
    public static void depthTest(boolean enabled) {
        if (enabled) { GL11.glEnable(GL11.GL_DEPTH_TEST); RenderSystem.enableDepthTest(); }
        else { GL11.glDisable(GL11.GL_DEPTH_TEST); RenderSystem.disableDepthTest(); }
    }

    /** 禁写深度必须同时更新真实值和缓存，防止后续全屏面或清屏误写世界深度。 */
    public static void depthMask(boolean enabled) {
        GL11.glDepthMask(enabled);
        RenderSystem.depthMask(enabled);
    }

    /** 同步深度比较函数，DH 与近景可使用不同的正/反向深度规则。 */
    public static void depthFunction(int function) {
        GL11.glDepthFunc(function);
        RenderSystem.depthFunc(function);
    }

    /** 可靠设置背面剔除开关，不让全屏三角形沿用模型的裁剪状态。 */
    public static void cull(boolean enabled) {
        if (enabled) { GL11.glEnable(GL11.GL_CULL_FACE); RenderSystem.enableCull(); }
        else { GL11.glDisable(GL11.GL_CULL_FACE); RenderSystem.disableCull(); }
    }

    /** 裁剪开关也有引擎缓存；矩形由各作用域按原坐标单独恢复。 */
    public static void scissor(boolean enabled) {
        if (enabled) { GL11.glEnable(GL11.GL_SCISSOR_TEST); GlStateManager._enableScissorTest(); }
        else { GL11.glDisable(GL11.GL_SCISSOR_TEST); GlStateManager._disableScissorTest(); }
    }

    /** 可靠设置混合开关；完整背景合成必须确实关闭，不能让缓存命中省略它。 */
    public static void blend(boolean enabled) {
        if (enabled) { GL11.glEnable(GL11.GL_BLEND); RenderSystem.enableBlend(); }
        else { GL11.glDisable(GL11.GL_BLEND); RenderSystem.disableBlend(); }
    }

    /** 恢复 RGB/Alpha 混合因子，同时同步 Minecraft 的因子缓存。 */
    public static void blendFunction(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
        GL14.glBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
        RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
    }

    /** 恢复写掩码，避免外部原生改动与缓存不同步时，下一次申请全颜色写入被跳过。 */
    public static void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        GL11.glColorMask(red, green, blue, alpha);
        RenderSystem.colorMask(red, green, blue, alpha);
    }

    /**
     * 退出自绘通道时恢复混合，并同步 ShaderInstance 使用的另一层 BlendMode 缓存。
     * 先经过不透明模式再恢复原模式，避免 BlendMode 的相等判断跳过状态设置；
     * 最后分别恢复 RGB/Alpha 方程，因为原版 BlendMode 只能表达共用一个方程。
     */
    public static void restoreBlend(boolean enabled, int sourceRgb, int destinationRgb,
            int sourceAlpha, int destinationAlpha, int equationRgb, int equationAlpha) {
        new BlendMode().apply();
        if (enabled) {
            new BlendMode(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha, equationRgb).apply();
        }
        // 调用双向同步入口，原版或第三方缓存无论处于何种状态都必须恢复真实因子和开关。
        blendFunction(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
        GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
        blend(enabled);
    }
}
