package org.ywzj.rvp.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/** RVP 载具 HUD 预设采用 1080p 自动档的参考比例，图形与文字共享同一次变换。 */
final class RVP_PresetHudLayout implements AutoCloseable {
    /** 原自动档在 1920×1080 下的逻辑宽度。 */
    private static final float REFERENCE_WIDTH = 480.0F;
    /** 原自动档在 1920×1080 下的逻辑高度。 */
    private static final float REFERENCE_HEIGHT = 270.0F;

    /** 当前 HUD 的绘制上下文。 */
    private final GuiGraphics graphics;
    /** 以设计坐标表示的可用宽度。 */
    private final int width;
    /** 以设计坐标表示的可用高度。 */
    private final int height;

    private RVP_PresetHudLayout(GuiGraphics graphics) {
        this.graphics = graphics;
        int guiWidth = Math.max(1, graphics.guiWidth());
        int guiHeight = Math.max(1, graphics.guiHeight());
        // 窄窗口同时受宽度约束，预设位置可按完整逻辑画布计算。
        float scale = Math.min(guiWidth / REFERENCE_WIDTH, guiHeight / REFERENCE_HEIGHT);
        width = Math.round(guiWidth / scale);
        height = Math.round(guiHeight / scale);
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1.0F);
    }

    static RVP_PresetHudLayout begin(GuiGraphics graphics) {
        return new RVP_PresetHudLayout(graphics);
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    @Override
    public void close() {
        graphics.pose().popPose();
    }
}
