package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.performance.RVP_RenderPerformance;
import org.ywzj.rvp.client.performance.RVP_RenderStage;
import org.ywzj.rvp.client.render.RVP_BlendModeState;
import org.ywzj.rvp.client.render.RVP_GlStateSync;

import java.io.IOException;

/** TV 信号失效时的模拟电视雪花，保持 CRT 黑框与滚动的失同步暗带。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RVP_HitlVideoLossRenderer {
    /** 由 Minecraft 着色器重载管理释放和替换，不分配整屏 CPU 图像或动态纹理。 */
    private static ShaderInstance snowShader;

    private RVP_HitlVideoLossRenderer() { }

    @SubscribeEvent
    public static void registerShader(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), RVP_MOD.modLocation("tv_snow"),
                DefaultVertexFormat.POSITION_TEX), shader -> snowShader = shader);
    }

    /** 无效视频不保留飞行读数；继续沿用原有失图文字和终结时长。 */
    public static void drawCaption(GuiGraphics graphics, Font font) {
        if (graphics.guiWidth() <= 0 || graphics.guiHeight() <= 0) return;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(graphics.guiWidth() * 0.5, graphics.guiHeight() * 0.5 - font.lineHeight * 0.5, 0);
        graphics.drawString(font, "NO IMAGE", -font.width("NO IMAGE") / 2, 0, 0xFFFF6060, true);
        pose.popPose();
    }

    /** 一次 GPU 绘制，弧形黑框保持实色，内框雪花按失图进度和视频叠加。 */
    public static void drawSnow(GuiGraphics graphics, float opacity) {
        if (snowShader == null || graphics.guiWidth() <= 0 || graphics.guiHeight() <= 0) return;
        try (var performance = RVP_RenderPerformance.gui(RVP_RenderStage.VIDEO_SNOW, graphics)) {
            graphics.flush();
            var main = Minecraft.getInstance().getMainRenderTarget();
            snowShader.getUniform("Time").set((System.nanoTime() % 120_000_000_000L) / 1.0e9F);
            snowShader.getUniform("Resolution").set((float) main.viewWidth, (float) main.viewHeight);
            snowShader.getUniform("Opacity").set(opacity);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            // 调用共用 GL 状态交接，避免雪花的透明混合影响后续 HUD。
            var previousBlendMode = RVP_BlendModeState.capture();
            var previousShader = RenderSystem.getShader();
            RenderSystem.disableDepthTest();
            try {
                RVP_GlStateSync.restoreBlend(true, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                RenderSystem.setShader(() -> snowShader);
                var pose = graphics.pose().last().pose();
                var buffer = Tesselator.getInstance().getBuilder();
                buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                buffer.vertex(pose, 0, 0, 0).uv(0, 0).endVertex();
                buffer.vertex(pose, 0, graphics.guiHeight(), 0).uv(0, 1).endVertex();
                buffer.vertex(pose, graphics.guiWidth(), graphics.guiHeight(), 0).uv(1, 1).endVertex();
                buffer.vertex(pose, graphics.guiWidth(), 0, 0).uv(1, 0).endVertex();
                BufferUploader.drawWithShader(buffer.end());
                // 复用原 GUI 绘制统计，只提交一个四边形，没有纹理上传。
                RVP_RenderPerformance.guiQuad(graphics, graphics.guiWidth(), graphics.guiHeight());
            } finally {
                if (depth) RenderSystem.enableDepthTest();
                RVP_GlStateSync.restoreBlend(blend, sourceRgb, destinationRgb, sourceAlpha,
                        destinationAlpha, equationRgb, equationAlpha);
                previousBlendMode.restore();
                RenderSystem.setShader(() -> previousShader);
            }
        }
    }
}
