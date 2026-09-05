package org.ywzj.rvp.client.screen.tool;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** 战术地图向可插拔工具暴露的最小画布能力，避免工具复制坐标变换与地图布局。 */
public interface RVP_TacticalMapHost {
    /** @return 地图左边界，单位 GUI 像素。 */ int mapLeft();
    /** @return 地图上边界，单位 GUI 像素。 */ int mapTop();
    /** @return 地图右边界，单位 GUI 像素。 */ int mapRight();
    /** @return 地图下边界，单位 GUI 像素。 */ int mapBottom();
    /** @return 工具侧栏左边界，单位 GUI 像素。 */ int sideLeft();
    /** @return 工具侧栏右边界，单位 GUI 像素。 */ int sideRight();
    /** @return 当前地图比例，单位格/像素。 */ double blocksPerPixel();
    /** 把屏幕横坐标转换为世界 X。 */ double screenToWorldX(double screenX);
    /** 把屏幕纵坐标转换为世界 Z。 */ double screenToWorldZ(double screenY);
    /** 把世界 X 转换为屏幕横坐标。 */ double worldToScreenX(double worldX);
    /** 把世界 Z 转换为屏幕纵坐标。 */ double worldToScreenY(double worldZ);
    /** @return 当前鼠标位置对应的地表坐标；世界未就绪时返回 null。 */ @Nullable Vec3 pickMapPoint(double mouseX, double mouseY);
    /** @return 当前客户端字体。 */ Font font();

    /** 绘制一条经过地图裁剪的世界坐标线段。 */
    void drawWorldLine(GuiGraphics graphics, double worldX0, double worldZ0,
                       double worldX1, double worldZ1, int color);
}
