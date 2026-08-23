package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_ApsHudState;
import org.ywzj.rvp.config.UIPresetAccess;
import org.ywzj.rvp.config.UIPresetManager.UIPosition;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

public class RVP_ApsHudOverlay implements IGuiOverlay {

    /** 未配置预设时的默认位置（左缘 15、垂直居中上方）。 */
    private static final int DEFAULT_X = 15;
    private static final int DEFAULT_Y_OFFSET = -20;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (LocalVehiclePlayer.instance == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        var vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null) {
            return;
        }
        RVP_ApsHudState.Snapshot state = RVP_ApsHudState.get(vehicle.getId());
        if (state == null || state.ammoMax() <= 0) {
            return;
        }

        // UI 预设位置：ui_presets 的 aps_hud 组件可自由配置；未配置用默认位置
        UIPosition pos = UIPresetAccess.apsHud(vehicle);
        int x = pos != null ? pos.computeX(screenWidth) : DEFAULT_X;
        int y = pos != null ? pos.computeY(screenHeight)
                : Math.max(screenHeight / 2 + Math.round(DEFAULT_Y_OFFSET * ((float) screenHeight / org.ywzj.rvp.config.UIPresetManager.UIPosition.REF_HEIGHT)), 15);
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
