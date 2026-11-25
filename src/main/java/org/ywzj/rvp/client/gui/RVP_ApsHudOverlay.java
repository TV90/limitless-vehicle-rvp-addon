package org.ywzj.rvp.client.gui;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_ApsHudState;
import org.ywzj.rvp.config.UIPresetAccess;
import org.ywzj.rvp.config.UIPresetManager.UIPosition;
import org.ywzj.vehicle.client.gui.VehicleHudLayout;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.util.VehicleHudText;
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
        int textColor = state.ammoCurrent() <= 0 ? Color.RED : Color.GREEN;
        String reloadText = resolveReloadText(state);
        // 装填类红字（2026-09-29 用户定版）：未满弹即处于装填，行红；满弹行保持常规色
        boolean reloading = state.ammoCurrent() < state.ammoMax();
        // APS 状态文字和本体遥测使用相同的 480 高逻辑画布。
        try (VehicleHudLayout layout = VehicleHudLayout.begin(guiGraphics)) {
            int x = pos != null ? pos.computeX(Math.round(layout.width())) : DEFAULT_X;
            int y = pos != null ? pos.computeY(Math.round(VehicleHudLayout.HEIGHT))
                    : Math.max(Math.round(VehicleHudLayout.HEIGHT / 2f
                    + DEFAULT_Y_OFFSET * VehicleHudLayout.HEIGHT / UIPosition.REF_HEIGHT), 15);
            float scale = pos == null ? 1.0f : Math.max(0.5f, Math.min(2.0f, pos.scale));
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(x, y, 0);
            guiGraphics.pose().scale(scale, scale, 1.0f);
            VehicleHudText.drawString(guiGraphics, "APS:" + state.ammoCurrent() + "/" + state.ammoMax(),
                    0, 0, textColor, false);
            VehicleHudText.drawString(guiGraphics, reloadText, 0, VehicleHudLayout.ROW_HEIGHT,
                    reloading ? Color.RED : textColor, false);
            guiGraphics.pose().popPose();
        }
    }

    private static String resolveReloadText(RVP_ApsHudState.Snapshot state) {
        if (state.ammoCurrent() >= state.ammoMax()) {
            return I18n.get("gui.ywzj_rvp.hud.aps_reload_none");
        }
        int remaining = Math.max(state.reloadOneTick() - state.reloadProgressTick(), 0);
        int seconds = (remaining + 19) / 20;
        return I18n.get("gui.ywzj_rvp.hud.aps_reload", seconds);
    }
}
