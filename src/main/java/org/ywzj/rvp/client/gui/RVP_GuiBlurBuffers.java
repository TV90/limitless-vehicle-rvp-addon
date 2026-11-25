package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/** 地图与命中面板分别持有一套低通缓冲；分帧准备，关闭界面后继续复用。 */
public final class RVP_GuiBlurBuffers {
    /** 与原玻璃效果相同的六层上限，不保存跨帧画面，只复用显存分配。 */
    public static final int COUNT = 6;
    /** 当前尺寸的缓冲；窗口变化时逐层替换，资源重载和退出世界时释放。 */
    private final RenderTarget[] levels = new RenderTarget[COUNT];

    public RenderTarget level(int index, int width, int height) {
        RenderTarget target = levels[index];
        if (target == null || target.width != width || target.height != height) {
            if (target != null) target.destroyBuffers();
            levels[index] = target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
        }
        return target;
    }

    /** 每次至多分配一层；准备未结束时返回 false，让调用方把剩余工作留到下一帧。 */
    public boolean prepareOne(int width, int height, int minimumWidth) {
        for (int i = 0; i < COUNT && width > minimumWidth; i++) {
            width = Math.max(1, (width + 1) / 2);
            height = Math.max(1, (height + 1) / 2);
            RenderTarget target = levels[i];
            if (target == null || target.width != width || target.height != height) {
                try {
                    level(i, width, height);
                } finally {
                    // 创建离屏目标会修改 framebuffer 与 viewport，不能带入下一帧的世界渲染。
                    Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
                }
                return false;
            }
        }
        return true;
    }

    public void release() {
        for (int i = 0; i < COUNT; i++) {
            if (levels[i] != null) levels[i].destroyBuffers();
            levels[i] = null;
        }
    }
}
