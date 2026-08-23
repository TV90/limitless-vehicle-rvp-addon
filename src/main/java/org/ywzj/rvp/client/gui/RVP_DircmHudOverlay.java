package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.state.RVP_DircmHudState;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * DIRCM HUD：显示各照射通道（左右骨骼）的照射/充能状态。
 * 由服务端 {@link org.ywzj.rvp.network.S2CDircmHudSync} 推送数据驱动。
 * 多通道按骨块名区分显示（用户需求：区分左右通道）。
 */
public class RVP_DircmHudOverlay implements IGuiOverlay {

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

        var font = Minecraft.getInstance().font;
        int x = 15;
        int y = Math.max(screenHeight / 2 + 40, 60);
        for (RVP_DircmHudState.ChannelSnapshot channel : state.channels()) {
            String line;
            int color;
            if (channel.irradiating()) {
                line = "DIRCM[" + channel.boneName() + "]:照射中";
                color = Color.GREEN;
            } else if (channel.charging()) {
                int seconds = (channel.chargeRemainTick() + 19) / 20;
                line = "DIRCM[" + channel.boneName() + "]:充能 " + seconds + "s";
                color = Color.GRAY;
            } else {
                line = "DIRCM[" + channel.boneName() + "]:就绪";
                color = Color.GREEN;
            }
            guiGraphics.drawString(font, line, x, y, color, false);
            y += 12;
        }
    }
}