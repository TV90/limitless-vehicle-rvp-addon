package org.ywzj.rvp.client.gui;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.state.RVP_ClientMaintenanceState;
import org.ywzj.rvp.client.state.RVP_CountermeasureHudState;
import org.ywzj.rvp.client.state.RVP_EcmActiveHudState;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
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
        // 座位权限门控：非授权座位不显示对应干扰物/ECM 的 HUD 文案行（其余行自动递补位置）
        int seatIndex = LocalVehiclePlayer.instance.seat == null ? -1 : LocalVehiclePlayer.instance.seat.seatIndex;
        boolean hasFlare = isTypeSeatAllowed(vehicle, seatIndex, RVP_EnumCountermeasureType.FLARE)
                && state != null && state.flareTotal() > 0;
        boolean hasChaff = isTypeSeatAllowed(vehicle, seatIndex, RVP_EnumCountermeasureType.CHAFF)
                && state != null && state.chaffTotal() > 0;
        boolean hasSmoke = isTypeSeatAllowed(vehicle, seatIndex, RVP_EnumCountermeasureType.SMOKE)
                && state != null && state.smokeTotal() > 0;
        boolean hasEcm = isEcmSeatAllowed(vehicle, seatIndex) && isEcmAvailable(vehicle);
        // 快速维修行（融入缺省自动补位：服务端已同步 hasMaintenance 才占行，否则后续行前移）
        RVP_ClientMaintenanceState.Snapshot maintenanceState = RVP_ClientMaintenanceState.get(vehicle.getId());
        if (!hasFlare && !hasChaff && !hasSmoke && !hasEcm && maintenanceState == null) {
            return;
        }
        var font = Minecraft.getInstance().font;
        // 本体左侧信息列：leftX = centerX - 120，leftY = centerY - 21，行距 12
        int leftX = screenWidth / 2 - 120;
        int centerY = screenHeight / 2;
        boolean rotaryWing = vehicle instanceof RotaryWingVehicle;
        boolean airborne = rotaryWing || vehicle instanceof FixedWingVehicle;
        if (airborne) {
            // 飞行器：热诱/箔条/ECM 组起点紧贴本体信息列末行（旋翼末行 leftY+36 → 组起点 leftY+48；
            // 固定翼末行 leftY+48 → 组起点 leftY+60），三者在组内按 热诱→箔条→ECM 顺序紧凑排列，缺失则递补
            int y = centerY - 21 + (rotaryWing ? 48 : 60);
            boolean airDecoyDrawn = false;
            if (hasFlare) {
                drawRow(guiGraphics, font, "热诱", state.flareRemain(), state.flareTotal(),
                        state.flareReloadRemain(), leftX, y, RVP_Keys.FIRE_FLARE);
                y += 12;
                airDecoyDrawn = true;
            }
            if (hasChaff) {
                drawRow(guiGraphics, font, "箔条", state.chaffRemain(), state.chaffTotal(),
                        state.chaffReloadRemain(), leftX, y, RVP_Keys.FIRE_CHAFF);
                y += 12;
                airDecoyDrawn = true;
            }
            if (hasEcm) {
                drawEcmRow(guiGraphics, font, vehicle, leftX, y);
                y += 12;
                airDecoyDrawn = true;
            }
            if (hasSmoke) {
                // 烟雾属另一类型干扰物组：已绘制空战干扰物组时再空一行分隔
                drawRow(guiGraphics, font, "烟雾", state.smokeRemain(), state.smokeTotal(),
                        state.smokeReloadRemain(), leftX, airDecoyDrawn ? y + 12 : y, RVP_Keys.FIRE_SMOKE);
                y = (airDecoyDrawn ? y + 12 : y) + 12;
            }
            if (maintenanceState != null) {
                // 维修行挂在干扰物组之后（烟雾缺失时自动递补其位置）
                drawMaintenanceRow(guiGraphics, font, maintenanceState, leftX, y);
            }
        } else {
            // 地面载具：ECM 置于烟雾下方；无烟雾时 ECM 递补烟雾位
            int y = centerY - 4;
            if (hasFlare) {
                drawRow(guiGraphics, font, "热诱", state.flareRemain(), state.flareTotal(),
                        state.flareReloadRemain(), leftX, y, RVP_Keys.FIRE_FLARE);
                y += 12;
            }
            if (hasChaff) {
                drawRow(guiGraphics, font, "箔条", state.chaffRemain(), state.chaffTotal(),
                        state.chaffReloadRemain(), leftX, y, RVP_Keys.FIRE_CHAFF);
                y += 12;
            }
            if (hasSmoke) {
                drawRow(guiGraphics, font, "烟雾", state.smokeRemain(), state.smokeTotal(),
                        state.smokeReloadRemain(), leftX, y, RVP_Keys.FIRE_SMOKE);
                y += 12;
            }
            if (hasEcm) {
                drawEcmRow(guiGraphics, font, vehicle, leftX, y);
                y += 12;
            }
            if (maintenanceState != null) {
                // 维修行递补干扰物/ECM 组末尾的空位
                drawMaintenanceRow(guiGraphics, font, maintenanceState, leftX, y);
            }
        }
    }

    /** 是否装备主动ECM（任一骨块存活）。 */
    private static boolean isEcmAvailable(AbstractVehicle vehicle) {
        var devices = RVP_VehicleHitboxFactorManager.INSTANCE.resolveEcmActiveDevices(vehicle);
        if (devices == null || devices.isEmpty()) {
            return false;
        }
        for (String bone : devices.keySet()) {
            if (RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), bone, BoneModuleType.ECM_ACTIVE)) {
                return true;
            }
        }
        // 无骨骼ECM（__vehicle__）始终可用
        return devices.containsKey("__vehicle__");
    }

    /** 本地玩家当前座位索引（未上载具/座位缺失返回 -1）。 */
    private static int localSeatIndex() {
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        return lvp == null || lvp.seat == null ? -1 : lvp.seat.seatIndex;
    }

    /** 当前座位是否允许使用某类干扰物（读载具包 countermeasure 配置的 allowed_seat_indexes）。 */
    private static boolean isTypeSeatAllowed(AbstractVehicle vehicle, int seatIndex,
                                             RVP_EnumCountermeasureType type) {
        var data = org.ywzj.rvp.countermeasure.RVP_CountermeasureConfigManager.INSTANCE
                .resolve(vehicle.getVehicleId());
        if (data == null) {
            return false;
        }
        var system = switch (type) {
            case FLARE -> data.getFlare();
            case CHAFF -> data.getChaff();
            case SMOKE -> data.getSmoke();
            default -> null;
        };
        return system != null && system.isSeatAllowed(seatIndex);
    }

    /** 当前座位是否被任一存活 ECM 骨块的配置允许（无骨骼 __vehicle__ 视作始终存活）。 */
    private static boolean isEcmSeatAllowed(AbstractVehicle vehicle, int seatIndex) {
        var devices = RVP_VehicleHitboxFactorManager.INSTANCE.resolveEcmActiveDevices(vehicle);
        if (devices == null || devices.isEmpty()) {
            return false;
        }
        for (var entry : devices.entrySet()) {
            if ("__vehicle__".equals(entry.getKey())) {
                if (entry.getValue().isSeatAllowed(seatIndex)) {
                    return true;
                }
                continue;
            }
            if (RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), entry.getKey(), BoneModuleType.ECM_ACTIVE)
                    && entry.getValue().isSeatAllowed(seatIndex)) {
                return true;
            }
        }
        return false;
    }

    /** 绘制 ECM 行（无“(主动)”后缀，样式对齐干扰物）。 */
    private static void drawEcmRow(GuiGraphics guiGraphics, Font font, AbstractVehicle vehicle, int x, int y) {
        var snapshot = RVP_EcmActiveHudState.get(vehicle.getId());
        if (snapshot != null && snapshot.isActive()) {
            int seconds = (snapshot.activeRemainTick() + 19) / 20;
            guiGraphics.drawString(font, "ECM: 反制中 " + seconds + "s", x, y, Color.GREEN);
            return;
        }
        if (snapshot != null && snapshot.isCoolingDown()) {
            int seconds = (snapshot.cooldownRemainTick() + 19) / 20;
            guiGraphics.drawString(font, "ECM: 装填 " + seconds + "秒", x, y, Color.GREEN);
            return;
        }
        String keyName = RVP_Keys.FIRE_ECM.getTranslatedKeyMessage().getString();
        guiGraphics.drawString(font, "ECM: 就绪 [" + keyName + "]", x, y, Color.GREEN);
    }

    /** 绘制快速维修行（文案对齐干扰物/ECM 行：就绪带键位 / 维修中 / 冷却倒计时）。 */
    private static void drawMaintenanceRow(GuiGraphics guiGraphics, Font font,
                                           RVP_ClientMaintenanceState.Snapshot state, int x, int y) {
        if (state.isUsing()) {
            guiGraphics.drawString(font, "维修: 维修中", x, y, Color.GREEN);
            return;
        }
        if (state.isCoolingDown()) {
            int seconds = (state.cooldownRemain() + 19) / 20;
            guiGraphics.drawString(font, "维修: 冷却 " + seconds + "秒", x, y, Color.GREEN);
            return;
        }
        String keyName = RVP_Keys.USE_MAINTENANCE.getTranslatedKeyMessage().getString();
        guiGraphics.drawString(font, "维修: 就绪 [" + keyName + "]", x, y, Color.GREEN);
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
