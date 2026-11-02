package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** 遮挡与终结共用原有雪花纹理效果，尺寸跟随视口；无效视频不伪装成仍有实时图像。 */
public final class RVP_HitlVideoLossRenderer {
    private RVP_HitlVideoLossRenderer() { }

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

    /** 终结画面只绘制雪花及失图文字，不继续显示已经失效的飞行读数。 */
    public static void draw(GuiGraphics graphics, Font font, long tick) {
        // 调用既有雪花屏和共用居中提示，两个 HITL 控制模式使用同一视觉出口。
        drawSnow(graphics, tick);
        drawCaption(graphics, font);
    }

    /** 仅居中位置随视口变化，失图提示也沿用原生 GUI 字号。 */
    public static void drawCaption(GuiGraphics graphics, Font font) {
        if (graphics.guiWidth() <= 0 || graphics.guiHeight() <= 0) return;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(graphics.guiWidth() * 0.5, graphics.guiHeight() * 0.5 - font.lineHeight * 0.5, 0);
        int half = font.width("NO IMAGE") / 2;
        graphics.fill(-half - 7, -7, half + 7, 15, 0xE6000000);
        graphics.drawString(font, "NO IMAGE", -half, 0, 0xFFFF6060, true);
        pose.popPose();
    }

    /** 链路被阻时的干扰滤镜：黑底 + 整屏贴一张预生成雪花噪点纹理。
     * 由原来的「每帧循环数千次 fill 小矩形」改为「黑底一次 draw + 噪点纹理一次 blit」，
     * 消除逐像素顶点提交导致的客户端 FPS 骤降。 */
    public static void drawSnow(GuiGraphics gg, long tick) {
        int w = gg.guiWidth();
        int h = gg.guiHeight();
        gg.fill(0, 0, w, h, 0xFF000000); // 黑底（单次 draw）
        ResourceLocation loc = ensureSnowTexture(tick, w, h);
        if (loc != null) {
            // 整屏贴噪点纹理：单次 draw（替代原来数千次 2×2 fill）
            gg.blit(loc, 0, 0, 0, 0, w, h, w, h);
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
        snowTexture.upload();
        snowRegenTick = gameTick;
        return snowTextureLocation;
    }

}
