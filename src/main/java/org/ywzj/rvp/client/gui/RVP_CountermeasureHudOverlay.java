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
 * 干扰物 HUD：显示「数量/总数 [键位]」行，装填时显示「装填 X 秒」倒计时。
 * 样式对齐本体（绿字，耗尽红字）。位置按载具类型分开锚定：
 * <ul>
 *   <li>固定翼：热诱/箔条组紧跟本体信息列（{@link FixedWingVehicleOverlay} 末行燃油在 leftY+48）；</li>
 *   <li>旋翼：同上（{@link RotaryWingVehicleOverlay} 末行燃油在 leftY+36）；</li>
 *   <li>地面载具：本体无左侧信息列，烟雾行置于屏幕竖直中部。</li>
 * </ul>
 * 飞行器上若同时有热诱/箔条与烟雾，两组之间空一行分隔。
 */
public class RVP_CountermeasureHudOverlay implements IGuiOverlay {

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        // 调用本体 LocalVehiclePlayer：判断玩家是否正乘坐载具
        if (LocalVehiclePlayer.instance == null || !LocalVehiclePlayer.instance.onVehicle()) {
            return;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null) {
            return;
        }
        // 取当前载具的干扰物余量同步状态
        RVP_CountermeasureHudState.Snapshot state = RVP_CountermeasureHudState.get(vehicle.getId());
        if (state == null
                || (state.flareTotal() <= 0 && state.chaffTotal() <= 0 && state.smokeTotal() <= 0)) {
            return;
        }
        var font = Minecraft.getInstance().font;
        // 本体左侧信息列：leftX = centerX - 120，leftY = centerY - 21，行距 12
        int leftX = screenWidth / 2 - 120;
        int centerY = screenHeight / 2;
        boolean rotaryWing = vehicle instanceof RotaryWingVehicle;
        boolean airborne = rotaryWing || vehicle instanceof FixedWingVehicle;
        if (airborne) {
            // 飞行器：热诱/箔条组起点紧贴本体信息列末行（旋翼末行 leftY+36 → 组起点 leftY+48；
            // 固定翼末行 leftY+48 → 组起点 leftY+60），不再统一用固定翼偏移导致旋翼多隔一行空白
            int y = centerY - 21 + (rotaryWing ? 48 : 60);
            boolean airDecoyDrawn = false;
            if (state.flareTotal() > 0) {
                drawRow(guiGraphics, font, "热诱", state.flareRemain(), state.flareTotal(),
                        state.flareReloadRemain(), leftX, y, RVP_Keys.FIRE_FLARE);
                y += 12;
                airDecoyDrawn = true;
            }
            if (state.chaffTotal() > 0) {
                drawRow(guiGraphics, font, "箔条", state.chaffRemain(), state.chaffTotal(),
                        state.chaffReloadRemain(), leftX, y, RVP_Keys.FIRE_CHAFF);
                y += 12;
                airDecoyDrawn = true;
            }
            if (state.smokeTotal() > 0) {
                // 烟雾属另一类型干扰物组：已绘制空战干扰物组时再空一行分隔
                drawRow(guiGraphics, font, "烟雾", state.smokeRemain(), state.smokeTotal(),
                        state.smokeReloadRemain(), leftX, airDecoyDrawn ? y + 12 : y, RVP_Keys.FIRE_SMOKE);
            }
        } else {
            // 地面载具：本体无左侧信息列，整块置于屏幕竖直中部（顶边 centerY-4 使文字视觉居中于中线）
            int y = centerY - 4;
            if (state.flareTotal() > 0) {
                drawRow(guiGraphics, font, "热诱", state.flareRemain(), state.flareTotal(),
                        state.flareReloadRemain(), leftX, y, RVP_Keys.FIRE_FLARE);
                y += 12;
            }
            if (state.chaffTotal() > 0) {
                drawRow(guiGraphics, font, "箔条", state.chaffRemain(), state.chaffTotal(),
                        state.chaffReloadRemain(), leftX, y, RVP_Keys.FIRE_CHAFF);
                y += 12;
            }
            if (state.smokeTotal() > 0) {
                drawRow(guiGraphics, font, "烟雾", state.smokeRemain(), state.smokeTotal(),
                        state.smokeReloadRemain(), leftX, y, RVP_Keys.FIRE_SMOKE);
            }
        }
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
