package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.state.RVP_ClientLockWarningState;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureConfigManager;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureData;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureSystemData;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.passenger.WarningReceiver;
import org.ywzj.vehicle.vehicle.pojo.WarnType;

/**
 * 锁定告警提示文案（血条上方，红色闪烁，居中显示）。
 *
 * <p>本告警为<b>飞行器专用</b>（热焰弹/箔条规避提示），仅固定翼/旋翼载具显示：
 * 按优先级（IR/AIR &gt; ARH &gt; 雷达锁定）自下而上叠放：
 * <ul>
 *   <li>被 IR / AIR 导弹追踪：提示按热焰弹键释放热焰弹规避；</li>
 *   <li>被 ARH 导弹追踪：提示按箔条键释放箔条规避；</li>
 *   <li>被雷达锁定：提示按箔条键释放箔条规避。</li>
 * </ul>
 * 地面载具不显示本告警（坦克的烟雾/ECM 告警走独立分支，见
 * {@code docs/plan/RVP干扰物重构数据模型/RVP告警系统方案_20260816.md}）。
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
        boolean laser = RVP_ClientLockWarningState.isLaserTrack();
        boolean hitlTv = RVP_ClientLockWarningState.isHitlTvTrack();
        boolean irTrack = RVP_ClientLockWarningState.isIrTrack();
        boolean arhTrack = RVP_ClientLockWarningState.isArhTrack();
        boolean radarLock = hasRadarLock(vehicle);
        if (!laser && !hitlTv && !irTrack && !arhTrack && !radarLock) {
            return;
        }
        boolean ground = !isAircraft(vehicle);
        // 载具干扰物能力：用于决定提示是否附带"释放干扰物"建议
        boolean hasFlare = hasCountermeasure(vehicle, RVP_EnumCountermeasureType.FLARE);
        boolean hasSmoke = hasCountermeasure(vehicle, RVP_EnumCountermeasureType.SMOKE);
        boolean hasChaff = hasCountermeasure(vehicle, RVP_EnumCountermeasureType.CHAFF);
        boolean hasEcm = hasActiveEcm(vehicle);

        Font font = Minecraft.getInstance().font;
        int color = blinkRed();
        int centerX = screenWidth / 2;
        // 本体健康条：renderBaseInfo 平移 (screenWidth/2, screenHeight+16) 后画在 (0,-20,120,5)，
        // 顶边在 screenHeight-9；提示文案从其上方更高的固定偏移起逐行向下排布。
        int y = screenHeight - 64;
        // 红外锁定：有对应干扰物（飞行器=热焰弹，地面=烟雾）才给"抛洒"建议，否则只提示被锁定
        if (irTrack) {
            Component text;
            if (ground) {
                text = hasSmoke
                        ? Component.translatable("rvp.lock_warning.ir_ground", RVP_Keys.FIRE_SMOKE.getTranslatedKeyMessage())
                        : Component.translatable("rvp.lock_warning.ir_none");
            } else {
                text = hasFlare
                        ? Component.translatable("rvp.lock_warning.ir", RVP_Keys.FIRE_FLARE.getTranslatedKeyMessage())
                        : Component.translatable("rvp.lock_warning.ir_none");
            }
            drawCentered(guiGraphics, font, text, centerX, y, color);
            y += font.lineHeight;
        }
        // 雷达制导导弹锁定：优先箔条，无箔条但有主动ECM 则提示释放 ECM，都没有只提示被锁定（地面载具同样提示）
        if (arhTrack) {
            Component text;
            if (hasChaff) {
                text = Component.translatable("rvp.lock_warning.arh", RVP_Keys.FIRE_CHAFF.getTranslatedKeyMessage());
            } else if (hasEcm) {
                text = Component.translatable("rvp.lock_warning.arh_ecm", RVP_Keys.FIRE_ECM.getTranslatedKeyMessage());
            } else {
                text = Component.translatable("rvp.lock_warning.arh_none");
            }
            drawCentered(guiGraphics, font, text, centerX, y, color);
            y += font.lineHeight;
        }
        // 雷达锁定：同雷达弹逻辑（地面载具同样提示）
        if (radarLock) {
            Component text;
            if (hasChaff) {
                text = Component.translatable("rvp.lock_warning.radar", RVP_Keys.FIRE_CHAFF.getTranslatedKeyMessage());
            } else if (hasEcm) {
                text = Component.translatable("rvp.lock_warning.radar_ecm", RVP_Keys.FIRE_ECM.getTranslatedKeyMessage());
            } else {
                text = Component.translatable("rvp.lock_warning.radar_none");
            }
            drawCentered(guiGraphics, font, text, centerX, y, color);
            y += font.lineHeight;
        }
        if (laser) {
            Component text;
            if (ground) {
                text = hasSmoke
                        ? Component.translatable("rvp.lock_warning.laser_ground", RVP_Keys.FIRE_SMOKE.getTranslatedKeyMessage())
                        : Component.translatable("rvp.lock_warning.laser_none");
            } else {
                text = Component.translatable("rvp.lock_warning.laser_air");
            }
            drawCentered(guiGraphics, font, text, centerX, y, color);
            y += font.lineHeight;
        }
        if (hitlTv) {
            Component text;
            if (ground) {
                text = hasSmoke
                        ? Component.translatable("rvp.lock_warning.hitl_tv_ground", RVP_Keys.FIRE_SMOKE.getTranslatedKeyMessage())
                        : Component.translatable("rvp.lock_warning.hitl_tv_none");
            } else {
                text = Component.translatable("rvp.lock_warning.hitl_tv_air");
            }
            drawCentered(guiGraphics, font, text, centerX, y, color);
        }
    }

    /** 载具是否装备某类 RVP 干扰物（热焰弹/箔条/烟雾）。 */
    private static boolean hasCountermeasure(AbstractVehicle vehicle, RVP_EnumCountermeasureType type) {
        RVP_CountermeasureData data = RVP_CountermeasureConfigManager.INSTANCE.resolve(vehicle.getVehicleId());
        if (data == null) {
            return false;
        }
        RVP_CountermeasureSystemData system;
        switch (type) {
            case FLARE -> system = data.getFlare();
            case CHAFF -> system = data.getChaff();
            case SMOKE -> system = data.getSmoke();
            default -> system = null;
        }
        return system != null && system.isEnabled();
    }

    /** 载具是否装备主动ECM（可释放的 ECM 干扰）。 */
    private static boolean hasActiveEcm(AbstractVehicle vehicle) {
        var devices = RVP_VehicleHitboxFactorManager.INSTANCE.resolveEcmActiveDevices(vehicle);
        return devices != null && !devices.isEmpty();
    }

    private static boolean hasRadarLock(AbstractVehicle vehicle) {
        WarningReceiver receiver = vehicle.warningReceiver;
        if (receiver == null || receiver.targets == null) {
            return false;
        }
        return receiver.targets.values().stream()
                .anyMatch(target -> target.warnType() == WarnType.RADAR_LOCK);
    }

    /** 是否飞行器（固定翼 / 旋翼）：热焰弹/箔条告警仅对飞行器显示。 */
    private static boolean isAircraft(AbstractVehicle vehicle) {
        return vehicle instanceof FixedWingVehicle || vehicle instanceof RotaryWingVehicle;
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
