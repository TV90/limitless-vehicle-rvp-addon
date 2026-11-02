package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;

/** 雪花只遮住世界视频；展板底板、模型、文字和其余 HUD 均在它之后绘制。 */
public final class RVP_HitlVideoBackgroundOverlay implements IGuiOverlay {
    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) return;
        if (needsSnow(RVP_ClientHitlState.isActive(), RVP_ClientHitlState.isHitlLinkBlocked(),
                RVP_ClientHitlState.isNoImage(mc.level))) {
            RVP_HitlVideoLossRenderer.drawSnow(graphics, mc.level.getGameTime());
        }
    }

    /** 命中后的终结提示也有雪花；结束提示后不得残留上一次的阻断状态。 */
    public static boolean needsSnow(boolean active, boolean blocked, boolean noImage) {
        return noImage || active && blocked;
    }
}
