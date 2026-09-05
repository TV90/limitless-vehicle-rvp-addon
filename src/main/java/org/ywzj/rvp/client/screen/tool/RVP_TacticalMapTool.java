package org.ywzj.rvp.client.screen.tool;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 可安装到既有战术地图画布上的客户端工具。 */
public interface RVP_TacticalMapTool {
    /** @return 工具稳定资源 ID。 */ ResourceLocation id();
    /** 地图完成控件和布局初始化后调用。 */ void initialize(RVP_TacticalMapHost host);
    /** 地图客户端 Tick；工具只读取客户端快照，不推进权威任务。 */ void tick(RVP_TacticalMapHost host);
    /** 处理鼠标按下；返回 true 时阻止地图默认左键行为。 */
    boolean mouseClicked(RVP_TacticalMapHost host, double mouseX, double mouseY, int button);
    /** 处理鼠标拖动；返回 true 时阻止地图平移。 */
    boolean mouseDragged(RVP_TacticalMapHost host, double mouseX, double mouseY,
                         int button, double dragX, double dragY);
    /** 处理鼠标释放；返回 true 时阻止地图默认释放行为。 */
    boolean mouseReleased(RVP_TacticalMapHost host, double mouseX, double mouseY, int button);
    /** 在地图实体和基础侧栏之上绘制预览与状态。 */
    void renderOverlay(RVP_TacticalMapHost host, GuiGraphics graphics,
                       int mouseX, int mouseY, float partialTick);
    /** Screen 关闭时清理仅属于本次打开的拖动状态。 */ void onClose();
}
