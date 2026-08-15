package org.ywzj.rvp.client.gui;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.state.RVP_CountermeasureHudState;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 干扰物 HUD：显示「数量/总数 [键位]」行，装填时显示「装填 X 秒」倒计时。
 * 飞行器显示热焰弹 / 箔条行；地面载具显示烟雾行（各自 total>0 才显示）。样式对齐本体（绿字，耗尽红字）。
 */
public class RVP_CountermeasureHudOverlay implements IGuiOverlay {

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (LocalVehiclePlayer.instance == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null) {
            return;
        }
        RVP_CountermeasureHudState.Snapshot state = RVP_CountermeasureHudState.get(vehicle.getId());
        if (state == null
                || (state.flareTotal() <= 0 && state.chaffTotal() <= 0 && state.smokeTotal() <= 0)) {
            return;
        }
        var font = Minecraft.getInstance().font;
        // 本体列：leftX = centerX - 120，leftY = centerY - 21，行 +12/+24/+36/+48（油门/速度/高度/燃料）
        // 干扰物行叠放在其下方，样式对齐本体（绿字）
        int leftX = screenWidth / 2 - 120;
        int y = screenHeight / 2 - 21 + 60;
        drawRow(guiGraphics, font, "热诱", state.flareRemain(), state.flareTotal(),
                state.flareReloadRemain(), leftX, y, RVP_Keys.FIRE_FLARE);
        drawRow(guiGraphics, font, "箔条", state.chaffRemain(), state.chaffTotal(),
                state.chaffReloadRemain(), leftX, y + 12, RVP_Keys.FIRE_CHAFF);
        drawRow(guiGraphics, font, "烟雾", state.smokeRemain(), state.smokeTotal(),
                state.smokeReloadRemain(), leftX, y + 24, RVP_Keys.FIRE_SMOKE);
    }

    private static void drawRow(GuiGraphics guiGraphics, Font font, String label,
                                int remain, int total, int reloadRemain, int x, int y, KeyMapping key) {
        if (total <= 0) {
            return;
        }
        // 对齐本体纵向 HUD：正常绿字，耗尽红字，带阴影（同本体 drawString 默认样式）
        int color = remain <= 0 ? Color.RED : Color.GREEN;
        if (reloadRemain > 0) {
            // 装填倒计时（秒）
            int seconds = (reloadRemain + 19) / 20;
            guiGraphics.drawString(font, label + ": 装填 " + seconds + "秒", x, y, color);
        } else {
            // 当前数量 + 键位（英文冒号 + 空格，同本体 lang 样式）
            String keyName = key.getTranslatedKeyMessage().getString();
            guiGraphics.drawString(font, label + ": " + remain + " [" + keyName + "]",
                    x, y, color);
        }
    }
}
