package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 失图视频绘制在载具瞄具遮罩之后、TV 状态文字之前，避免雪花被盖成黑屏。 */
public final class RVP_HitlVideoBackgroundOverlay implements IGuiOverlay {
    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || !mc.options.getCameraType().isFirstPerson() || mc.player == null
                || !(mc.player.getVehicle() instanceof AbstractVehicle) || mc.level == null) return;
        // 调用共用失图时钟；恢复链路后，仍绘制渐出部分。
        float opacity = RVP_ClientHitlState.getSnowOpacity(mc.level);
        if (needsSnow(RVP_ClientHitlState.isActive(), opacity, RVP_ClientHitlState.isNoImage(mc.level))) {
            RVP_HitlVideoLossRenderer.drawSnow(graphics, opacity);
        }
    }

    /** 活跃视频允许渐入和渐出，结束后仍严格受失图提示的有效期约束。 */
    public static boolean needsSnow(boolean active, float opacity, boolean noImage) {
        return opacity > 0f && (active || noImage);
    }
}
