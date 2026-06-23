package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_ApsHudState;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

public class RVP_ApsHudOverlay implements IGuiOverlay {

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (LocalVehiclePlayer.instance == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        var vehicle = LocalVehiclePlayer.instance.getVehicle();
        if (vehicle == null) {
            return;
        }
        RVP_ApsHudState.Snapshot state = RVP_ApsHudState.get(vehicle.getId());
        if (state == null || state.ammoMax() <= 0) {
            return;
        }

        int x = 15;
        int y = Math.max(screenHeight / 2 - 20, 15);
        int textColor = state.ammoCurrent() <= 0 ? Color.RED : Color.GREEN;

        guiGraphics.drawString(
                Minecraft.getInstance().font,
                "APS:" + state.ammoCurrent() + "/" + state.ammoMax(),
                x,
                y,
                textColor,
                false
        );

        String reloadText = resolveReloadText(state);
        guiGraphics.drawString(
                Minecraft.getInstance().font,
                reloadText,
                x,
                y + 12,
                textColor,
                false
        );
    }

    private static String resolveReloadText(RVP_ApsHudState.Snapshot state) {
        if (state.ammoCurrent() >= state.ammoMax()) {
            return "装填时间:无需装填";
        }
        int remaining = Math.max(state.reloadOneTick() - state.reloadProgressTick(), 0);
        int seconds = (remaining + 19) / 20;
        return "装填时间:" + seconds + "秒";
    }
}
