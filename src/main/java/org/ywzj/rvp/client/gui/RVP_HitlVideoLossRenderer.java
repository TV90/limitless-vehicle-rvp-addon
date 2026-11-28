package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.performance.RVP_RenderMetric;
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

    /** 噪点独立随机源，遮挡和终结效果共用，不消耗世界随机数。 */
    private static final net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create();
    /** 雪花噪点纹理缓存：屏幕尺寸一张，按需重绘。 */
    private static DynamicTexture snowTexture;
    private static ResourceLocation snowTextureLocation;
    private static int snowWidth = -1;
    private static int snowHeight = -1;
    private static int snowRegenTick = -1;
    /** 噪点纹理重绘间隔（tick）：每 4 tick 重绘一次模拟雪花闪动，避免逐帧重绘。 */
    private static final int SNOW_REGEN_INTERVAL = 4;

    private RVP_HitlVideoLossRenderer() { }

    @SubscribeEvent
    public static void registerShader(RegisterShadersEvent event) throws IOException {
        // 资源包可省略新雪花描述，省略时继续使用原有噪点纹理。
        if (event.getResourceProvider().getResource(RVP_MOD.modLocation("shaders/core/tv_snow.json")).isEmpty()) {
            snowShader = null;
            return;
        }
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
        if (graphics.guiWidth() <= 0 || graphics.guiHeight() <= 0) return;
        if (snowShader == null) {
            drawFallbackSnow(graphics, opacity);
            return;
        }
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

    /** 复用旧雪花纹理，沿用当前覆盖层的透明度。 */
    private static void drawFallbackSnow(GuiGraphics graphics, float opacity) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        try (var performance = RVP_RenderPerformance.gui(RVP_RenderStage.VIDEO_SNOW, graphics)) {
            ResourceLocation texture = ensureSnowTexture(level.getGameTime(), width, height);
            if (texture == null) return;
            graphics.flush();
            float[] color = RenderSystem.getShaderColor().clone();
            try {
                graphics.setColor(1f, 1f, 1f, opacity);
                graphics.blit(texture, 0, 0, 0, 0, width, height, width, height);
                graphics.flush();
                RVP_RenderPerformance.guiQuad(graphics, width, height);
            } finally {
                graphics.setColor(color[0], color[1], color[2], color[3]);
            }
        }
    }

    /** 复用/生成一张屏幕尺寸的雪花噪点纹理；每 {@code SNOW_REGEN_INTERVAL} tick 重绘一次模拟闪动。
     * 重绘是批量写像素 + 一次 upload，不产生逐像素 draw 提交。 */
    private static ResourceLocation ensureSnowTexture(long tick, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (w <= 0 || h <= 0) {
            return null;
        }
        int gameTick = (int) (tick % 1000000L);
        boolean sizeChanged = snowWidth != w || snowHeight != h;
        if (snowTexture != null && !sizeChanged && Math.abs(gameTick - snowRegenTick) < SNOW_REGEN_INTERVAL) {
            return snowTextureLocation;
        }
        if (sizeChanged || snowTexture == null) {
            if (snowTexture != null) {
                snowTexture.close();
            }
            NativeImage img = new NativeImage(w, h, false);
            snowTexture = new DynamicTexture(img);
            snowTextureLocation = mc.getTextureManager()
                    .register("rvp_tv_noise_" + w + "_" + h, snowTexture);
            snowWidth = w;
            snowHeight = h;
        }
        // 批量重绘噪点：约 90% 像素为暗色、少量亮灰点模拟雪花（写纹理，无 draw 开销）
        NativeImage img = snowTexture.getPixels();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = random.nextInt(100);
                int gray = r < 90 ? 0 : 60 + random.nextInt(160);
                img.setPixelRGBA(x, y, (0xFF << 24) | (gray << 16) | (gray << 8) | gray);
            }
        }
        // 调用动态视频纹理上传量统计；此项主要用于识别噪点刷新成本。
        RVP_RenderPerformance.add(RVP_RenderMetric.UPLOAD_BYTES, (long) w * h * 4);
        snowTexture.upload();
        snowRegenTick = gameTick;
        return snowTextureLocation;
    }
}
