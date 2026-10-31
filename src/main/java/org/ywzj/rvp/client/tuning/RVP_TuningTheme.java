package org.ywzj.rvp.client.tuning;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** 参考图的灰紫色紧凑控件；只负责外观，不读取任何特效或参数值。 */
public final class RVP_TuningTheme {
    /** 窗口与参数区的灰紫底色。 */
    public static final int PANEL = 0xFF5E5965;
    /** 按钮未选中时的深灰紫底色。 */
    public static final int BUTTON = 0xFF302C37;
    /** 鼠标悬停或键盘聚焦时的按钮底色。 */
    public static final int HOVER = 0xFF423C4B;
    /** 普通参数名和控件文字颜色。 */
    public static final int TEXT = 0xFFD6D1D9;
    /** 当前选中分类的淡黄色文字。 */
    public static final int SELECTED = 0xFFE5DE8D;
    /** 分类标题及分组的暖色文字。 */
    public static final int HEADING = 0xFFD6B087;
    /** 边框最暗的一圈。 */
    private static final int EDGE = 0xFF15121B;
    /** 内边框和凹槽上的浅色细线。 */
    private static final int HIGHLIGHT = 0xFF8A8293;

    private RVP_TuningTheme() { }

    /** 一像素切角矩形，接近参考图的小圆角，不需要贴图或额外渲染状态。 */
    public static void roundedFill(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        if (width < 3 || height < 3) { graphics.fill(x, y, x + width, y + height, color); return; }
        graphics.fill(x + 1, y, x + width - 1, y + height, color);
        graphics.fill(x, y + 1, x + width, y + height - 1, color);
    }

    /** 窗口外沿的深浅双线与不透明底色，世界画面不透进参数区。 */
    public static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, EDGE);
        graphics.renderOutline(x + 1, y + 1, width - 2, height - 2, 0xFFB1AAB8);
        graphics.renderOutline(x + 2, y + 2, width - 4, height - 4, 0xFF28242F);
        graphics.fill(x + 3, y + 3, x + width - 3, y + height - 3, PANEL);
    }

    /** 按钮的细边与轻微立体感，替代原版 Minecraft 的宽厚纹理按钮。 */
    public static void button(GuiGraphics graphics, int x, int y, int width, int height, boolean hovered) {
        // 调用统一切角填充，所有导航、分组和菜单按钮保持同一套边缘样式。
        roundedFill(graphics, x, y, width, height, EDGE);
        roundedFill(graphics, x + 1, y + 1, width - 2, height - 2, hovered ? HOVER : BUTTON);
        graphics.hLine(x + 2, x + width - 3, y + 1, HIGHLIGHT);
        graphics.hLine(x + 2, x + width - 3, y + height - 2, 0xFF1E1A25);
        graphics.vLine(x + 1, y + 1, y + height - 2, 0xFF6B6376);
        graphics.vLine(x + width - 2, y + 1, y + height - 2, 0xFF18151F);
    }

    /** 搜索框与数值框的下凹细边；正文仍交给原版输入框处理光标和选区。 */
    public static void input(GuiGraphics graphics, int x, int y, int width, int height, boolean focused) {
        roundedFill(graphics, x, y, width, height, EDGE);
        roundedFill(graphics, x + 1, y + 1, width - 2, height - 2, 0xFF4A4551);
        graphics.hLine(x + 2, x + width - 3, y + height - 2, focused ? 0xFFBBB1C6 : HIGHLIGHT);
        graphics.vLine(x + width - 2, y + 1, y + height - 2, 0xFF746B81);
    }

    /** 参考图的窄滑条：多层暗槽、浅色细填充和短滑块，数值逻辑仍由原版滑条处理。 */
    public static void slider(GuiGraphics graphics, int x, int y, int width, int height, double position, boolean focused) {
        roundedFill(graphics, x, y, width, height, EDGE);
        roundedFill(graphics, x + 1, y + 1, width - 2, height - 2, 0xFF89818F);
        roundedFill(graphics, x + 2, y + 2, width - 4, height - 4, 0xFF24202C);
        graphics.fill(x + 4, y + 4, x + width - 4, y + height - 3, 0xFF55505D);
        int right = x + 4 + (int) Math.round(Math.max(0, Math.min(1, position)) * (width - 8));
        if (right > x + 4) {
            graphics.fill(x + 4, y + 4, right, y + height - 3, focused ? 0xFFE3DEE8 : 0xFFBEB8C5);
            graphics.hLine(x + 4, right - 1, y + 4, 0xFFE4E0E8);
        }
    }

    /** 内容区边框与滚动条均使用细暗槽，避免混入原版白灰色的大滑块。 */
    public static void scrollbar(GuiGraphics graphics, int x, int top, int bottom, int thumbTop, int thumbHeight) {
        roundedFill(graphics, x, top, 5, bottom - top, EDGE);
        graphics.fill(x + 1, top + 1, x + 4, bottom - 1, 0xFF37313F);
        roundedFill(graphics, x + 1, thumbTop, 3, thumbHeight, 0xFF8C8397);
        if (thumbHeight > 2) { graphics.vLine(x + 1, thumbTop, thumbTop + thumbHeight - 1, 0xFFB3A9BF); }
    }

    /** 只有皮肤不同的标准按钮，保留原版鼠标、键盘和旁白行为。 */
    public static final class CompactButton extends Button {
        /** 使用屏幕当前字体，跟随资源重载和用户语言。 */
        private final Font font;

        public CompactButton(Font font, String title, Runnable action) {
            super(0, 0, 60, 13, Component.literal(title), ignored -> action.run(), DEFAULT_NARRATION);
            this.font = font;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // 调用统一皮肤；文字不会继承原版按钮的厚边与纹理。
            button(graphics, getX(), getY(), getWidth(), getHeight(), isHoveredOrFocused());
            String label = font.plainSubstrByWidth(getMessage().getString(), Math.max(0, getWidth() - 6));
            int color = active ? getFGColor() : 0xFF8E8697;
            graphics.drawString(font, label, getX() + (getWidth() - font.width(label)) / 2,
                    getY() + (getHeight() - 8) / 2, color, false);
        }
    }

    /** 搜索和数值共用的窄输入框，不重写文字编辑或输入法行为。 */
    public static class CompactEditBox extends EditBox {
        public CompactEditBox(Font font, String narration) {
            super(font, 0, 0, 60, 11, Component.literal(narration));
            setBordered(false);
            setTextColor(TEXT);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // 输入文字向内留三像素；背景边框不改变实际的字符选区与剪贴板处理。
            input(graphics, getX() - 3, getY() - 2, getWidth() + 6, getHeight() + 3, isFocused());
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** 数值框保留完整编辑精度，未编辑时从整数部分显示，避免拖动后只看见跳动的小数尾数。 */
    public static final class CompactNumberEditBox extends CompactEditBox {
        /** 回填期间禁止把显示文本或光标变化重新写回业务参数。 */
        private boolean syncing;
        /** 上次完整文本；原版移动光标也会调用responder，必须与真正的文本编辑区分。 */
        private String lastText = "";

        public CompactNumberEditBox(Font font, String narration) { super(font, narration); }

        @Override public void setResponder(Consumer<String> responder) {
            lastText = getValue();
            super.setResponder(text -> {
                if (text.equals(lastText)) { return; }
                lastText = text;
                if (!syncing && responder != null) { responder.accept(text); }
            });
        }

        /** 同步业务读数而不产生写入；完整文本仍可点击编辑，不以截短后的数字覆盖实际参数。 */
        public void syncValue(String text) {
            syncing = true;
            try {
                if (!text.equals(getValue())) { setValue(text); }
                if (!isFocused()) {
                    // 调用头部定位，取消原版setValue自动跳到文本末尾造成的横向滚动。
                    showNumberStart();
                }
            } finally {
                lastText = getValue();
                syncing = false;
            }
        }

        @Override public void setFocused(boolean focused) {
            super.setFocused(focused);
            if (!focused) {
                // 调用同一头部定位，手动输入后退出编辑也始终保留整数和小数点可见。
                showNumberStart();
            }
        }

        private void showNumberStart() {
            setCursorPosition(0);
            setHighlightPos(0);
        }
    }
}
