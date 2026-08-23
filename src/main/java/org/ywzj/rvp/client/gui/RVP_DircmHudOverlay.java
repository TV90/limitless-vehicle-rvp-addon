package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_DircmHudState;
import org.ywzj.rvp.config.UIPresetAccess;
import org.ywzj.rvp.config.UIPresetManager.UIPosition;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * DIRCM HUD：显示各照射通道的照射/充能状态。
 * 由服务端 {@link org.ywzj.rvp.network.S2CDircmHudSync} 推送数据驱动。
 * 通道显示名用载具 JSON 的 {@code display_name}（如"左"/"右"），未配置用骨块名；
 * 位置可经 UI 预设（{@code ui_presets} 的 {@code dircm_hud} 组件）自由配置，
 * 未配置时用默认位置（屏幕左缘 8、垂直居中上方 60）。
 */
public class RVP_DircmHudOverlay implements IGuiOverlay {

    /** 未配置预设时的默认位置：左缘 8、垂直居中上方 60（GUI 单位）。 */
    private static final int DEFAULT_X = 8;
    private static final int DEFAULT_Y_OFFSET = -60;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (LocalVehiclePlayer.instance == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        var vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null) {
            return;
        }
        RVP_DircmHudState.Snapshot state = RVP_DircmHudState.get(vehicle.getId());
        if (state == null || state.channels() == null || state.channels().isEmpty()) {
            return;
        }

        // UI 预设位置：ui_presets 的 dircm_hud 组件可自由配置；未配置用默认位置
        UIPosition pos = UIPresetAccess.dircmHud(vehicle);
        int x = pos != null ? pos.computeX(screenWidth) : DEFAULT_X;
        int y = pos != null ? pos.computeY(screenHeight)
                : Math.max(screenHeight / 2 + Math.round(DEFAULT_Y_OFFSET * ((float) screenHeight / org.ywzj.rvp.config.UIPresetManager.UIPosition.REF_HEIGHT)), 15);

        var font = Minecraft.getInstance().font;
        for (RVP_DircmHudState.ChannelSnapshot channel : state.channels()) {
            String name = channel.displayName() != null && !channel.displayName().isBlank()
                    ? channel.displayName() : channel.boneName();
            String line;
            int color;
            if (channel.irradiating()) {
                line = "DIRCM[" + name + "]:照射中";
                color = Color.GREEN;
            } else if (channel.charging()) {
                int seconds = (channel.chargeRemainTick() + 19) / 20;
                line = "DIRCM[" + name + "]:充能 " + seconds + "s";
                color = Color.GRAY;
            } else {
                line = "DIRCM[" + name + "]:就绪";
                color = Color.GREEN;
            }
            guiGraphics.drawString(font, line, x, y, color, false);
            y += 12;
        }
    }
}