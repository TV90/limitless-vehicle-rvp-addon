package org.ywzj.rvp.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.FormattedCharSequence;

/** 战术页的无边框玻璃、浅色选择态和原生文字。坐标均为 GUI 像素。 */
public final class RVP_MapChrome {
    /** 主要文字、次要文字、选中态文字和键盘焦点。 */
    public static final int TEXT = 0xFFF3F6F2, MUTED = 0xFFDBE2DE, INK = 0xFFFFFFFF, ACCENT = 0xFFF2CB75;

    private RVP_MapChrome() {}

    /** 为地图工具裁剪设计坐标中的矩形，复用主地图的视口变换。 */
    public static void scissor(GuiGraphics graphics, int left, int top, int right, int bottom) {
        RVP_TacticalViewport.scissor(graphics, left, top, right, bottom);
    }

    public static void glass(GuiGraphics g, int x, int y, int r, int b) {
        // 采样同位置的地图，仅在玻璃面内叠加烟色渐变。
        RVP_MapFrost.tile(g, x,y,r,b);
        g.fillGradient(x,y,r,b,0x4D1D282B,0x7010171C);
    }

    public static void strip(GuiGraphics g, int x, int y, int r, int b, boolean selected, boolean hover) {
        if (selected || hover) g.fillGradient(x,y,r,b,selected ? 0x45EDF5F0 : 0x32EDF5F0,
                selected ? 0x26D9E8E1 : 0x19D9E8E1);
    }

    /** 斜角只用于标题底色；抽屉和菜单没有外框。 */
    public static void title(GuiGraphics g, Font f, String label, int x, int y, int r, int b) {
        int notch = Math.min(6,(b-y)/3);
        g.fillGradient(x+notch,y,r,b,0x38EDF4F0,0x1AE6EFEA);
        for (int i=0; i<notch; i++) g.fill(x+i,y+notch-i,x+i+1,b,0x28E6EFEA);
        line(g, f, label, x+notch+4,y+(b-y-textHeight(f))/2+1,r-x-notch-8,TEXT);
    }

    /** 顶栏页签共用烟灰渐变和右上折角；下拉箭头占用独立的位置。 */
    static void toolbarTab(GuiGraphics g, Font font, Component label, int x, int y, int w, int h,
                           boolean dropdown, int textColor) {
        int right = x + w, bottom = y + h, fold = Math.min(8, h / 4);
        int top = 0xD13F4448;
        for (int i = 0; i < fold; i++) {
            g.fill(x + fold - i, y + i, right, y + i + 1, top);
            g.fill(right - fold + i, y + i, right, y + i + 1, 0xFFAD813D);
        }
        g.fillGradient(x, y + fold, right, bottom, top, 0xE01A1D22);
        int arrow = dropdown ? Math.min(16, h - 7) : 0;
        buttonLabel(g, font, label, x + 2, y, right - 2 - arrow, bottom, textColor);
        if (dropdown) RVP_MapGlyph.DOWN.draw(g, right - 3 - arrow / 2, y + h / 2 + 1, arrow, textColor);
    }

    // 字体使用与地图图形相同的外层视口变换。
    static float textScale() { return 1.0F; }

    static int textHeight(Font font) { return font.lineHeight; }

    static void text(GuiGraphics g, Font f, FormattedCharSequence value, int x, int y, int color) {
        g.drawString(f, value, x, y, color, true);
    }

    public static void line(GuiGraphics g, Font font, String value, int x, int y, int width, int color) {
        if (width <= 0) return;
        String label = font.plainSubstrByWidth(value, (int) (width / textScale()));
        text(g, font, net.minecraft.network.chat.Component.literal(label).getVisualOrderText(), x, y, color);
    }

    /** 为弹药名等长值先缩短绘制宽度，仍超出时在独立区域滚动。 */
    public static void fitLine(GuiGraphics graphics, Font font, String value, int x, int y, int width, int color) {
        if (width <= 0) return;
        float scale = Math.min(1.0F, width / (float) Math.max(1, font.width(value)));
        if (scale < 0.68F) {
            scrollingLabel(graphics, font, Component.literal(value), x - 3, y - 1, x + width + 3,
                    y + font.lineHeight - 1, color, false);
            return;
        }
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y + (1.0F - scale) * font.lineHeight * 0.5F, 0.0F);
            graphics.pose().scale(scale, scale, 1.0F);
            graphics.drawString(font, value, 0, 0, color, true);
        } finally {
            graphics.pose().popPose();
        }
    }

    /** 短标签居中，长标签在同一个按钮的裁剪区内往返滚动。 */
    public static void buttonLabel(GuiGraphics g, Font font, net.minecraft.network.chat.Component label,
                                   int x, int y, int right, int bottom, int color) {
        scrollingLabel(g, font, label, x, y, right, bottom, color, true);
    }

    /** 下拉列表保留左对齐，超长文字与页签使用相同的裁剪和往返滚动。 */
    static void scrollingLabel(GuiGraphics g, Font font, Component label, int x, int y,
                               int right, int bottom, int color, boolean centered) {
        int space = right - x - 6;
        if (space <= 0) return;
        int width = Math.round(font.width(label) * textScale());
        int excess = width - space;
        int left = centered ? x + (right - x - width) / 2 : x + 3;
        if (excess > 0) {
            double seconds = net.minecraft.Util.getMillis() / 1000d;
            double cycle = seconds / Math.max(3.0, excess / 24.0);
            double phase = .5 - .5 * Math.cos(cycle * Math.PI * 2);
            left = x + 3 - (int) Math.round(excess * Mth.clamp(phase * 1.5 - .25, 0, 1));
            RVP_TacticalViewport.scissor(g, x + 3, y, right - 3, bottom);
        }
        text(g, font, label.getVisualOrderText(), left, y + (bottom - y - textHeight(font)) / 2 + 1, color);
        if (excess > 0) g.disableScissor();
    }

    /** 左列表视口和底部按钮各占一块，滚动偏移不影响按钮的位置。 */
    public record ListArea(int top, int bottom, int step) {
        public int visible() { return Math.max(0, (bottom - top) / step); }
        public int clamp(int first, int count) { return Mth.clamp(first, 0, Math.max(0, count - visible())); }
        public int rowAt(double y, int first, int count) {
            if (y < top || y >= top + visible() * step) return -1;
            int index = first + ((int)y - top) / step;
            return index < count ? index : -1;
        }
        public int y(int index, int first) { return top + (index - first) * step; }
    }
}
