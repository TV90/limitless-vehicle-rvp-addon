package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.state.RVP_ClientLockWarningState;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

/**
 * 锁定告警提示文案（血条上方，红色闪烁，居中显示）。
 *
 * <p>显示三种提示，按优先级（IR/AIR &gt; ARH &gt; 雷达锁定）自下而上叠放：
 * <ul>
 *   <li>被 IR / AIR 导弹追踪：提示按热焰弹键释放热焰弹规避；</li>
 *   <li>被 ARH 导弹追踪：提示按箔条键释放箔条规避；</li>
 *   <li>被雷达锁定：提示按箔条键释放箔条规避。</li>
 * </ul>
 * IR/AIR/ARH 追踪状态由 {@code S2CMissileTrackAlert} 写入 {@link RVP_ClientLockWarningState}，
 * 雷达锁定由本体 {@link WarningReceiver} 的 RADAR_LOCK 告警判定。位置/字号固定，按屏幕
 * 分辨率（GUI 像素）自适应。</p>
 */
public class RVP_LockWarningOverlay implements IGuiOverlay {

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick,
                       int screenWidth, int screenHeight) {
        if (LocalVehiclePlayer.instance == null) {
            return;
        }
        AbstractVehicle vehicle = LocalVehiclePlayer.instance.vehicle;
        if (vehicle == null || vehicle.level() == null) {
            return;
        }
        boolean irTrack = RVP_ClientLockWarningState.isIrTrack();
        boolean arhTrack = RVP_ClientLockWarningState.isArhTrack();
        boolean radarLock = hasRadarLock(vehicle);
        if (!irTrack && !arhTrack && !radarLock) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int color = blinkRed();
        int centerX = screenWidth / 2;
        // 本体健康条：renderBaseInfo 平移 (screenWidth/2, screenHeight+16) 后画在 (0,-20,120,5)，
        // 顶边在 screenHeight-9；提示文案从其上方更高的固定偏移起逐行向下排布。
        int y = screenHeight - 64;
        if (irTrack) {
            drawCentered(guiGraphics, font, Component.translatable("rvp.lock_warning.ir",
                    RVP_Keys.FIRE_FLARE.getTranslatedKeyMessage()), centerX, y, color);
            y += font.lineHeight;
        }
        if (arhTrack) {
            drawCentered(guiGraphics, font, Component.translatable("rvp.lock_warning.arh",
                    RVP_Keys.FIRE_CHAFF.getTranslatedKeyMessage()), centerX, y, color);
            y += font.lineHeight;
        }
        if (radarLock) {
            drawCentered(guiGraphics, font, Component.translatable("rvp.lock_warning.radar",
                    RVP_Keys.FIRE_CHAFF.getTranslatedKeyMessage()), centerX, y, color);
        }
    }

    private static boolean hasRadarLock(AbstractVehicle vehicle) {
        WarningReceiver receiver = vehicle.warningReceiver;
        if (receiver == null || receiver.targets == null) {
            return false;
        }
        return receiver.targets.values().stream()
                .anyMatch(target -> target.warnType() == WarnType.RADAR_LOCK);
    }

    /** 红色闪烁：透明度按正弦在 0.45~1.0 间波动。 */
    private static int blinkRed() {
        double t = (System.currentTimeMillis() % 1000) / 1000.0;
        double alpha = 0.45 + 0.55 * Math.sin(t * Math.PI * 2);
        return (((int) (alpha * 255)) << 24) | 0xFF0000;
    }

    private static void drawCentered(GuiGraphics guiGraphics, Font font, Component text,
                                     int centerX, int y, int color) {
        guiGraphics.drawString(font, text, centerX - font.width(text) / 2, y, color, true);
    }
}
