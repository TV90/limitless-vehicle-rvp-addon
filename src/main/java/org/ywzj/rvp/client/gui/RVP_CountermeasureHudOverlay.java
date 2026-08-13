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
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 干扰物 HUD：在本体固定翼/直升机纵向 HUD（燃料/速度/高度列）下方再开两列——
 * 热焰弹列 / 铝箔条列，显示「数量/总数 [键位]」，装填时显示「装填 X 秒」倒计时。
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
        // 干扰物系统仅对固定翼 / 直升机生效（对齐本体纵向 HUD 的显示前提）
        if (!(vehicle instanceof FixedWingVehicle) && !(vehicle instanceof RotaryWingVehicle)) {
            return;
        }
        RVP_CountermeasureHudState.Snapshot state = RVP_CountermeasureHudState.get(vehicle.getId());
        if (state == null || (state.flareTotal() <= 0 && state.chaffTotal() <= 0)) {
            return;
        }
        var font = Minecraft.getInstance().font;
        // 本体列：leftX = centerX - 120，leftY = centerY - 21，行 +12/+24/+36/+48（油门/速度/高度/燃料）
        // 干扰物两列放在其下方
        int leftX = screenWidth / 2 - 120;
        int y = screenHeight / 2 - 21 + 60;
        int columnGap = 92;
        drawColumn(guiGraphics, font, "热焰弹", state.flareRemain(), state.flareTotal(),
                state.flareReloadRemain(), leftX, y, RVP_Keys.FIRE_FLARE);
        drawColumn(guiGraphics, font, "铝箔条", state.chaffRemain(), state.chaffTotal(),
                state.chaffReloadRemain(), leftX + columnGap, y, RVP_Keys.FIRE_CHAFF);
    }

    private static void drawColumn(GuiGraphics guiGraphics, Font font, String label,
                                   int remain, int total, int reloadRemain, int x, int y, KeyMapping key) {
        if (total <= 0) {
            return;
        }
        int color = remain <= 0 ? Color.RED : Color.WHITE;
        if (reloadRemain > 0) {
            // 装填倒计时（秒）
            int seconds = (reloadRemain + 19) / 20;
            guiGraphics.drawString(font, label + "：装填 " + seconds + "秒", x, y, color, false);
        } else {
            // 数量/总数 + 键位
            String keyName = key.getTranslatedKeyMessage().getString();
            guiGraphics.drawString(font, label + ":" + remain + "/" + total + " [" + keyName + "]",
                    x, y, color, false);
        }
    }
}
