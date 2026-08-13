package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_CountermeasureHudState;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 干扰物 HUD（热焰弹 / 箔条剩余 + 装填倒计时），对齐 RVP_ApsHudOverlay。
 */
public class RVP_CountermeasureHudOverlay implements IGuiOverlay {

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (LocalVehiclePlayer.instance == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        var vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null) {
            return;
        }
        RVP_CountermeasureHudState.Snapshot state = RVP_CountermeasureHudState.get(vehicle.getId());
        if (state == null || (state.flareTotal() <= 0 && state.chaffTotal() <= 0)) {
            return;
        }

        int x = 15;
        int y = Math.max(screenHeight / 2 - 20, 15);
        var font = Minecraft.getInstance().font;

        if (state.flareTotal() > 0) {
            int color = state.flareRemain() <= 0 ? Color.RED : Color.WHITE;
            guiGraphics.drawString(font, "FL:" + state.flareRemain() + "/" + state.flareTotal(), x, y, color, false);
            guiGraphics.drawString(font, resolveReload(state.flareReloadRemain()), x, y + 12, Color.WHITE, false);
            y += 24;
        }
        if (state.chaffTotal() > 0) {
            int color = state.chaffRemain() <= 0 ? Color.RED : Color.GREEN;
            guiGraphics.drawString(font, "CH:" + state.chaffRemain() + "/" + state.chaffTotal(), x, y, color, false);
            guiGraphics.drawString(font, resolveReload(state.chaffReloadRemain()), x, y + 12, Color.WHITE, false);
        }
    }

    private static String resolveReload(int reloadRemain) {
        if (reloadRemain <= 0) {
            return "";
        }
        int seconds = (reloadRemain + 19) / 20;
        return "装填:" + seconds + "秒";
    }
}
