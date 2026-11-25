package org.ywzj.rvp.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/** 战术地图使用统一的设计坐标，材质、字体和输入一起随窗口缩放。 */
record RVP_TacticalViewport(int width, int height, float scaleX, float scaleY) {
    /** 对齐 1080p 下原画面大小 2 的逻辑宽度。 */
    private static final int REFERENCE_WIDTH = 960;
    /** 对齐 1080p 下原画面大小 2 的逻辑高度。 */
    private static final int REFERENCE_HEIGHT = 540;

    static RVP_TacticalViewport of(int guiWidth, int guiHeight) {
        int w = Math.max(1, guiWidth);
        int h = Math.max(1, guiHeight);
        float scale = Math.min(w / (float) REFERENCE_WIDTH, h / (float) REFERENCE_HEIGHT);
        int width = Math.round(w / scale);
        int height = Math.round(h / scale);
        return new RVP_TacticalViewport(width, height, w / (float) width, h / (float) height);
    }

    double mapX(double x) {
        return x / scaleX;
    }

    double mapY(double y) {
        return y / scaleY;
    }

    /** 1.20.1 的 GuiGraphics 裁剪使用 GUI 坐标，忽略绘制姿态矩阵。 */
    static void scissor(GuiGraphics g, int left, int top, int right, int bottom) {
        Matrix4f pose = g.pose().last().pose();
        g.enableScissor(Mth.floor(guiX(pose, left)), Mth.floor(guiY(pose, top)),
                Mth.ceil(guiX(pose, right)), Mth.ceil(guiY(pose, bottom)));
    }

    static float guiX(Matrix4f pose, float x) {
        return pose.m00() * x + pose.m30();
    }

    static float guiY(Matrix4f pose, float y) {
        return pose.m11() * y + pose.m31();
    }
}
