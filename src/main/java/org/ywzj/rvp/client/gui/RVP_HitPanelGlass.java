package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.parser.Color;
import com.sighs.apricityui.render.FontDrawer;
import com.sighs.apricityui.style.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** 命中回放专用玻璃底板：只采样面板范围，不降低文字和三维回放的分辨率。 */
final class RVP_HitPanelGlass {
    /** 连续缩小六次足以覆盖高分辨率；面板不活动时释放，缩放窗口时按需重建。 */
    private static final RenderTarget[] LEVELS = new RenderTarget[6];
    /** 与改装界面相同的窄体英文和中文字体回退，不修改用户已有字体资源。 */
    private static final String FONTS = "\"Purista SemiBold\", Purista, \"Microsoft YaHei\", \"Segoe UI\", sans-serif";

    private RVP_HitPanelGlass() {}

    static void release() {
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i] != null) LEVELS[i].destroyBuffers();
            LEVELS[i] = null;
        }
    }

    /** 逐级线性低通采样，再用透明边缘网格混回主画面；不经过 CPU 截图或贴图上传。 */
    static void draw(GuiGraphics gg, int left, int top, int right, int bottom) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget(), source = main;
        double scale = mc.getWindow().getGuiScale();
        int x0 = Math.max(0, (int) Math.floor(left * scale));
        int y0 = Math.max(0, main.viewHeight - (int) Math.ceil(bottom * scale));
        int x1 = Math.min(main.viewWidth, (int) Math.ceil(right * scale));
        int y1 = Math.min(main.viewHeight, main.viewHeight - (int) Math.floor(top * scale));
        int width = x1 - x0, height = y1 - y0;
        if (width <= 0 || height <= 0) return;
        int targetWidth = Math.max(1, width / 16);
        gg.flush();
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissorBox = new int[4];
        if (scissor) GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        // 帧缓冲缩小使用自身坐标，临时关闭主画面的裁剪，避免高 GUI 缩放下采样为空。
        RenderSystem.disableScissor();
        try {
            for (int i = 0; i < LEVELS.length && width > targetWidth; i++) {
                int w = Math.max(1, (width + 1) / 2), h = Math.max(1, (height + 1) / 2);
                RenderTarget dest = LEVELS[i];
                if (dest == null || dest.width != w || dest.height != h) {
                    if (dest != null) dest.destroyBuffers();
                    LEVELS[i] = dest = new TextureTarget(w, h, false, Minecraft.ON_OSX);
                    dest.setFilterMode(GL11.GL_LINEAR);
                }
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dest.frameBufferId);
                GL30.glBlitFramebuffer(x0, y0, x1, y1, 0, 0, w, h,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
                source = dest;
                x0 = y0 = 0; x1 = width = w; y1 = height = h;
            }
        } finally {
            main.bindWrite(true);
            if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        }
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        float edge = Math.min((right - left) / 8f, (float) (8 / scale));
        float[] xs = {left, left + edge, right - edge, right};
        float[] ys = {top, top + edge, bottom - edge, bottom};
        try {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, source.getColorTextureId());
            var matrix = gg.pose().last().pose();
            var buf = Tesselator.getInstance().getBuilder();
            buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
                int[][] corners = {{col, row}, {col, row + 1}, {col + 1, row + 1}, {col + 1, row}};
                for (int[] c : corners) {
                    float x = xs[c[0]], y = ys[c[1]];
                    int alpha = c[0] == 0 || c[0] == 3 || c[1] == 0 || c[1] == 3 ? 0 : 230;
                    buf.vertex(matrix, x, y, 0).uv((x - left) / (right - left), 1 - (y - top) / (bottom - top))
                            .color(255, 255, 255, alpha).endVertex();
                }
            }
            BufferUploader.drawWithShader(buf.end());
            // 冷灰渐变底色与采样共用羽化边缘，没有边框、描边或不透明角块。
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
                int[][] corners = {{col, row}, {col, row + 1}, {col + 1, row + 1}, {col + 1, row}};
                for (int[] c : corners) {
                    float y = ys[c[1]], t = (y - top) / (bottom - top);
                    int alpha = c[0] == 0 || c[0] == 3 || c[1] == 0 || c[1] == 3 ? 0 : (int) (118 + 30 * t);
                    buf.vertex(matrix, xs[c[0]], y, 0).color((int) (39 - 19 * t), (int) (47 - 21 * t),
                            (int) (54 - 23 * t), alpha).endVertex();
                }
            }
            BufferUploader.drawWithShader(buf.end());
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
        }
    }

    /** 用晴雪 UI 的高清字体栅格化，避免原版点阵字在固定物理字号下被放大。 */
    static Text text(String value, double size, int color, boolean bold) {
        Text text = new Text();
        text.content = value;
        text.fontFamily = FONTS;
        text.fontSize = size;
        text.fontWeight = bold ? 700 : 400;
        text.color = new Color(color);
        return text;
    }

    /** 同一字体度量用于右对齐与长标题缩放，避免中英文混排发生遮挡。 */
    static double width(Text text) {
        return Text.measureLine(text, text.content);
    }

    static void label(GuiGraphics gg, Text text, double x, double y) {
        label(gg, text, x, y, width(text));
    }

    /** 每段文字都有独立的内容裁剪区；抗锯齿和辉光也不能越过相邻字段或面板留白。 */
    static void label(GuiGraphics gg, Text text, double x, double y, double maxWidth) {
        gg.flush();
        gg.enableScissor((int) Math.floor(x - 1), (int) Math.floor(y - text.fontSize),
                (int) Math.ceil(x + maxWidth + 1), (int) Math.ceil(y + text.fontSize * 1.5));
        FontDrawer.pushDocumentPixelScale(Minecraft.getInstance().getWindow().getGuiScale());
        try {
            FontDrawer.drainCompletedRasters();
            // 低透明度的近邻辉光只柔化字缘，主字仍按原物理分辨率绘制。
            int color = text.color.getValue();
            text.color = new Color((color & 0xFFFFFF) | 0x18000000);
            double glow = .65 / Minecraft.getInstance().getWindow().getGuiScale();
            FontDrawer.drawFont(gg.pose(), text, new Position(x - glow, y));
            FontDrawer.drawFont(gg.pose(), text, new Position(x + glow, y));
            FontDrawer.drawFont(gg.pose(), text, new Position(x, y - glow));
            FontDrawer.drawFont(gg.pose(), text, new Position(x, y + glow));
            text.color = new Color(color);
            FontDrawer.drawFont(gg.pose(), text, new Position(x, y));
        } finally {
            FontDrawer.popDocumentPixelScale();
            gg.disableScissor();
        }
    }
}
