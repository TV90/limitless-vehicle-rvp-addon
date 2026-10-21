package org.ywzj.rvp.client.gui;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.state.RVP_ClientMaintenanceState;
import org.ywzj.rvp.client.state.RVP_CountermeasureHudState;
import org.ywzj.rvp.client.state.RVP_EcmActiveHudState;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.client.gui.VehicleHudLayout;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.util.VehicleHudText;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/**
 * 干扰物 / ECM / 快速维修 HUD（2026-10-21 重构：对齐本体新版 HUD 样式）。
 *
 * <p><b>样式</b>：本体自 2026-10 起 HUD 走 {@link VehicleHudLayout}（虚拟 480 高坐标系，
 * 整体按 {@code guiHeight / 480} 缩放）+ {@link VehicleHudText#telemetry}（标签在左、
 * 数值固定列）与 12px 阴影字。本类改为同款：同一缩放、同一列位（标签列 = {@code telemetryX}、
 * 数值列 +54、键位紧随数值）、行距 {@link VehicleHudLayout#ROW_HEIGHT}（15），
 * 因此与本体遥测列严丝合缝。</p>
 *
 * <p><b>行内容</b>：标签（绿） ｜ 数值（绿 / 部分受损黄 / 耗尽·装填·冷却红） ｜ 键位（同色降透明，
 * 只有"可立即使用"状态才显示；装填/冷却/损坏时不显示）。</p>
 *
 * <p><b>位置</b>：飞行器紧接本体遥测列第 4 行之后（本体固定 4 行：
 * 旋翼 转速/桨距/速度/燃油，固定翼 油门/速度/高度/燃油）；地面载具本体无左侧遥测列，
 * 自屏幕中线下方起排（首行为速度）。</p>
 */
public class RVP_CountermeasureHudOverlay implements IGuiOverlay {

    /** 部分受损黄（2026-09-29 用户定版）：与俯视图受损线框 #D4C44F 同色，alpha 对齐 HUD 其它色。 */
    private static final int DAMAGED_YELLOW = 0xDDD4C44F;

    /** 数值列偏移：与本体 telemetry 的 {@code max(54, 标签宽+10)} 对齐（本类标签均为 2 字）。 */
    private static final float VALUE_COLUMN = 54f;

    /** 键位与数值之间的间距（紧贴数值，不设固定列）。 */
    private static final float KEY_GAP = 5f;

    /** 键位提示透明度（只作提示，弱于数值）。 */
    private static final int KEY_ALPHA = 0x99;

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
        // [RVP] 发射器全损判定（2026-09-28）：绑定骨的 COUNTERMEASURE 模块全部失效 →
        // 该系统整行红字"损坏"（部分失效表现为上限变小，不特殊标注）
        boolean flareDestroyed = isTypeDestroyed(vehicle, RVP_EnumCountermeasureType.FLARE);
        boolean chaffDestroyed = isTypeDestroyed(vehicle, RVP_EnumCountermeasureType.CHAFF);
        boolean smokeDestroyed = isTypeDestroyed(vehicle, RVP_EnumCountermeasureType.SMOKE);
        // [RVP] 部分受损黄（2026-09-29 用户定版）：部分发射器打坏但系统仍可用 → 行黄字
        boolean flareDamaged = !flareDestroyed && isTypeDamaged(vehicle, RVP_EnumCountermeasureType.FLARE);
        boolean chaffDamaged = !chaffDestroyed && isTypeDamaged(vehicle, RVP_EnumCountermeasureType.CHAFF);
        boolean smokeDamaged = !smokeDestroyed && isTypeDamaged(vehicle, RVP_EnumCountermeasureType.SMOKE);
        boolean hasEcm = isEcmSeatAllowed(vehicle, seatIndex) && isEcmAvailable(vehicle);
        // 快速维修行（融入缺省自动补位：服务端已同步 hasMaintenance 才占行，否则后续行前移）
        RVP_ClientMaintenanceState.Snapshot maintenanceState = RVP_ClientMaintenanceState.get(vehicle.getId());
        // 地面载具速度行始终显示（用户 2026-09-27），故地面载具不因"无干扰物/ECM/维修"而跳过渲染
        boolean groundVehicle = !(vehicle instanceof RotaryWingVehicle)
                && !(vehicle instanceof FixedWingVehicle);
        if (!groundVehicle && !hasFlare && !hasChaff && !hasSmoke && !hasEcm && maintenanceState == null
                && !flareDestroyed && !chaffDestroyed && !smokeDestroyed) {
            return;
        }

        // 本体 HUD 坐标系（虚拟 480 高）：所有坐标都在这个空间里，字号/行距与本体一致。
        try (VehicleHudLayout layout = VehicleHudLayout.begin(guiGraphics)) {
            float centerX = layout.width() / 2f;
            float leftX = VehicleHudLayout.telemetryX(centerX);
            float rowH = VehicleHudLayout.ROW_HEIGHT;
            boolean rotaryWing = vehicle instanceof RotaryWingVehicle;
            boolean airborne = rotaryWing || vehicle instanceof FixedWingVehicle;
            int flareRemain = state == null ? 0 : state.flareRemain();
            int flareTotal = state == null ? 0 : state.flareTotal();
            int flareReload = state == null ? 0 : state.flareReloadRemain();
            int chaffRemain = state == null ? 0 : state.chaffRemain();
            int chaffTotal = state == null ? 0 : state.chaffTotal();
            int chaffReload = state == null ? 0 : state.chaffReloadRemain();
            int smokeRemain = state == null ? 0 : state.smokeRemain();
            int smokeTotal = state == null ? 0 : state.smokeTotal();
            int smokeReload = state == null ? 0 : state.smokeReloadRemain();
            if (airborne) {
                // 飞行器：本体遥测固定 4 行（末行 TELEMETRY_Y + 3 * ROW_HEIGHT），本组紧接其下
                float y = VehicleHudLayout.TELEMETRY_Y + 4f * rowH;
                boolean airDecoyDrawn = false;
                if (hasFlare || flareDestroyed) {
                    drawDecoyRow(guiGraphics, Component.translatable("gui.ywzj_rvp.hud.label_flare"),
                            flareDestroyed, flareDamaged, RVP_Keys.FIRE_FLARE, leftX, y,
                            flareRemain, flareTotal, flareReload);
                    y += rowH;
                    airDecoyDrawn = true;
                }
                if (hasChaff || chaffDestroyed) {
                    drawDecoyRow(guiGraphics, Component.translatable("gui.ywzj_rvp.hud.label_chaff"),
                            chaffDestroyed, chaffDamaged, RVP_Keys.FIRE_CHAFF, leftX, y,
                            chaffRemain, chaffTotal, chaffReload);
                    y += rowH;
                    airDecoyDrawn = true;
                }
                if (hasEcm) {
                    drawEcmRow(guiGraphics, vehicle, leftX, y);
                    y += rowH;
                    airDecoyDrawn = true;
                }
                if (hasSmoke || smokeDestroyed) {
                    // 烟雾属另一类型干扰物组：已绘制空战干扰物组时再空一行分隔
                    float smokeY = airDecoyDrawn ? y + rowH : y;
                    drawDecoyRow(guiGraphics, Component.translatable("gui.ywzj_rvp.hud.label_smoke"),
                            smokeDestroyed, smokeDamaged, RVP_Keys.FIRE_SMOKE, leftX, smokeY,
                            smokeRemain, smokeTotal, smokeReload);
                    y = smokeY + rowH;
                }
                if (maintenanceState != null) {
                    // 维修行挂在干扰物组之后（烟雾缺失时自动递补其位置）
                    drawMaintenanceRow(guiGraphics, maintenanceState, leftX, y);
                }
            } else {
                // 地面载具：速度行置顶（用户 2026-09-27），其后 ECM 置于烟雾下方；无烟雾时 ECM 递补烟雾位
                float y = VehicleHudLayout.CENTER_Y + 10f;
                drawSpeedRow(guiGraphics, vehicle, leftX, y);
                y += rowH;
                if (hasFlare || flareDestroyed) {
                    drawDecoyRow(guiGraphics, Component.translatable("gui.ywzj_rvp.hud.label_flare"),
                            flareDestroyed, flareDamaged, RVP_Keys.FIRE_FLARE, leftX, y,
                            flareRemain, flareTotal, flareReload);
                    y += rowH;
                }
                if (hasChaff || chaffDestroyed) {
                    drawDecoyRow(guiGraphics, Component.translatable("gui.ywzj_rvp.hud.label_chaff"),
                            chaffDestroyed, chaffDamaged, RVP_Keys.FIRE_CHAFF, leftX, y,
                            chaffRemain, chaffTotal, chaffReload);
                    y += rowH;
                }
                if (hasSmoke || smokeDestroyed) {
                    drawDecoyRow(guiGraphics, Component.translatable("gui.ywzj_rvp.hud.label_smoke"),
                            smokeDestroyed, smokeDamaged, RVP_Keys.FIRE_SMOKE, leftX, y,
                            smokeRemain, smokeTotal, smokeReload);
                    y += rowH;
                }
                if (hasEcm) {
                    drawEcmRow(guiGraphics, vehicle, leftX, y);
                    y += rowH;
                }
                if (maintenanceState != null) {
                    // 维修行递补干扰物/ECM 组末尾的空位
                    drawMaintenanceRow(guiGraphics, maintenanceState, leftX, y);
                }
            }
        }
    }

    // ==================== 行绘制（本体 telemetry 三段式） ====================

    /**
     * 一行 = 标签 ｜ 数值 ｜ 键位。
     *
     * @param key 键位名；null/空 = 不画（装填、冷却、损坏等不可操作状态）
     */
    private static void drawRow(GuiGraphics guiGraphics, Component label, Component value,
                                @Nullable String key, float x, float y, int valueColor) {
        VehicleHudText.drawString(guiGraphics, label, x, y, Color.GREEN);
        float valueX = x + VALUE_COLUMN;
        VehicleHudText.drawString(guiGraphics, value, valueX, y, valueColor);
        if (key != null && !key.isEmpty()) {
            // 键位紧贴数值（不设固定列）：数值宽度随状态变化，键位跟着走，视觉上贴着左侧文案。
            int keyColor = (valueColor & 0x00FFFFFF) | (KEY_ALPHA << 24);
            float keyX = valueX + VehicleHudText.width(value.getString()) + KEY_GAP;
            VehicleHudText.drawString(guiGraphics, "[" + key + "]", keyX, y, keyColor);
        }
    }

    /** 干扰物行：损坏 → 红字"损坏"；装填中 → 红字倒计时（无键位）；否则数量 + 键位。 */
    private static void drawDecoyRow(GuiGraphics guiGraphics, Component label, boolean destroyed, boolean damaged,
                                     KeyMapping key, float x, float y, int remain, int total, int reloadRemain) {
        if (destroyed) {
            drawRow(guiGraphics, label, Component.translatable("gui.ywzj_rvp.hud.value_destroyed"),
                    null, x, y, Color.RED);
            return;
        }
        if (total <= 0) {
            return;
        }
        if (reloadRemain > 0) {
            drawRow(guiGraphics, label,
                    Component.translatable("gui.ywzj_rvp.hud.value_reload", (reloadRemain + 19) / 20),
                    null, x, y, Color.RED);
            return;
        }
        int color = damaged ? DAMAGED_YELLOW : (remain <= 0 ? Color.RED : Color.GREEN);
        drawRow(guiGraphics, label, Component.literal(String.valueOf(remain)),
                shortenKeyName(key.getTranslatedKeyMessage().getString()), x, y, color);
    }

    /** 地面载具速度行：前进正 / 倒车负（水平速度在本体前向上的投影，格/tick × 72 = km/h）。 */
    private static void drawSpeedRow(GuiGraphics guiGraphics, AbstractVehicle vehicle, float x, float y) {
        var velocity = vehicle.getDeltaMovement();
        double horizontalSq = velocity.x * velocity.x + velocity.z * velocity.z;
        int kph = 0;
        if (horizontalSq > 1.0E-6) {
            var forward = vehicle.getLookAngle();
            // 前向水平单位向量（抑制俯仰分量，倒车判定=速度与前向水平投影反向）
            double fx = forward.x, fz = forward.z;
            double forwardLenSq = fx * fx + fz * fz;
            double proj;
            if (forwardLenSq < 1.0E-6) {
                proj = 0;
            } else {
                proj = (velocity.x * fx + velocity.z * fz) / Math.sqrt(forwardLenSq);
            }
            // 格/tick → km/h：×20 tick/s × 3.6 km/h per m/s（1 格 = 1 米）
            kph = (int) Math.floor(proj * 20.0 * 3.6 + (proj >= 0 ? 1.0E-3 : -1.0E-3));
        }
        drawRow(guiGraphics, Component.translatable("gui.ywzj_rvp.hud.label_speed"),
                Component.literal(kph + " KPH"), null, x, y, Color.GREEN);
    }

    /** 主动 ECM 行（反制中 / 冷却 / 就绪 + 键位）。 */
    private static void drawEcmRow(GuiGraphics guiGraphics, AbstractVehicle vehicle, float x, float y) {
        Component label = Component.translatable("gui.ywzj_rvp.hud.label_ecm");
        var snapshot = RVP_EcmActiveHudState.get(vehicle.getId());
        if (snapshot != null && snapshot.isActive()) {
            int seconds = (snapshot.activeRemainTick() + 19) / 20;
            drawRow(guiGraphics, label, Component.translatable("gui.ywzj_rvp.hud.value_jamming", seconds),
                    null, x, y, Color.GREEN);
            return;
        }
        if (snapshot != null && snapshot.isCoolingDown()) {
            int seconds = (snapshot.cooldownRemainTick() + 19) / 20;
            drawRow(guiGraphics, label, Component.translatable("gui.ywzj_rvp.hud.value_cooling", seconds),
                    null, x, y, Color.RED);
            return;
        }
        drawRow(guiGraphics, label, Component.translatable("gui.ywzj_rvp.hud.value_ready"),
                shortenKeyName(RVP_Keys.FIRE_ECM.getTranslatedKeyMessage().getString()), x, y, Color.GREEN);
    }

    /** 快速维修行（维修中 / 冷却 / 就绪 + 键位）。 */
    private static void drawMaintenanceRow(GuiGraphics guiGraphics, RVP_ClientMaintenanceState.Snapshot state,
                                           float x, float y) {
        Component label = Component.translatable("gui.ywzj_rvp.hud.label_maintenance");
        if (state.isUsing()) {
            int remainSec = (state.useRemain() + 19) / 20;
            drawRow(guiGraphics, label, Component.translatable("gui.ywzj_rvp.hud.value_repairing", remainSec),
                    null, x, y, Color.GREEN);
            return;
        }
        if (state.isCoolingDown()) {
            int seconds = (state.cooldownRemain() + 19) / 20;
            drawRow(guiGraphics, label, Component.translatable("gui.ywzj_rvp.hud.value_cooling", seconds),
                    null, x, y, Color.RED);
            return;
        }
        drawRow(guiGraphics, label, Component.translatable("gui.ywzj_rvp.hud.value_ready"),
                shortenKeyName(RVP_Keys.USE_MAINTENANCE.getTranslatedKeyMessage().getString()), x, y, Color.GREEN);
    }

    // ==================== 判定 ====================

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

    /**
     * [RVP] 干扰物系统发射器是否全部损坏（2026-09-28）：系统启用且配置了 bone_modules、
     * 绑定骨的 COUNTERMEASURE 模块全部失效。读服务端侧表（与 isEcmAvailable 同款——
     * 单机直读；专用服客户端表空则不显示损坏行，服务端 gate 仍生效）。
     */
    private static boolean isTypeDestroyed(AbstractVehicle vehicle, RVP_EnumCountermeasureType type) {
        var data = org.ywzj.rvp.countermeasure.RVP_CountermeasureConfigManager.INSTANCE.resolve(vehicle.getVehicleId());
        if (data == null) {
            return false;
        }
        var system = switch (type) {
            case FLARE -> data.getFlare();
            case CHAFF -> data.getChaff();
            case SMOKE -> data.getSmoke();
            default -> null;
        };
        if (system == null || !system.isEnabled()) {
            return false;
        }
        var bones = system.getBoneModules();
        if (bones == null || bones.isEmpty()) {
            return false; // 未绑定发射器骨：无模块化损伤
        }
        for (String bone : bones) {
            if (RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), bone, BoneModuleType.COUNTERMEASURE)) {
                return false;
            }
        }
        return true;
    }

    /**
     * [RVP] 干扰物系统是否部分受损（2026-09-29 用户定版）：配置了 bone_modules 且绑定骨的
     * COUNTERMEASURE 模块**部分**失效（仍有存活发射器）→ HUD 行黄字；全失效走
     * {@link #isTypeDestroyed} 红字"损坏"。未绑定发射器骨时恒 false（无模块化损伤）。
     */
    private static boolean isTypeDamaged(AbstractVehicle vehicle, RVP_EnumCountermeasureType type) {
        var data = org.ywzj.rvp.countermeasure.RVP_CountermeasureConfigManager.INSTANCE.resolve(vehicle.getVehicleId());
        if (data == null) {
            return false;
        }
        var system = switch (type) {
            case FLARE -> data.getFlare();
            case CHAFF -> data.getChaff();
            case SMOKE -> data.getSmoke();
            default -> null;
        };
        if (system == null || !system.isEnabled()) {
            return false;
        }
        var bones = system.getBoneModules();
        if (bones == null || bones.isEmpty()) {
            return false;
        }
        boolean anyAlive = false;
        boolean anyDead = false;
        for (String bone : bones) {
            if (RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), bone, BoneModuleType.COUNTERMEASURE)) {
                anyAlive = true;
            } else {
                anyDead = true;
            }
        }
        return anyAlive && anyDead;
    }

    /**
     * [RVP] 键位名缩短（2026-09-28 用户要求）：原版键名太长（"Left Control"）遮挡 HUD——
     * 修饰键前缀缩写（Left/Right → L/R）、Control → CTR；中文键名（"左Ctrl"）不含前缀原样返回。
     */
    private static String shortenKeyName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String shortened = name;
        if (shortened.startsWith("Left ")) {
            shortened = "L " + shortened.substring(5);
        } else if (shortened.startsWith("Right ")) {
            shortened = "R " + shortened.substring(6);
        }
        if (shortened.endsWith("Control")) {
            shortened = shortened.substring(0, shortened.length() - "Control".length()) + "CTR";
        }
        return shortened;
    }
}
