package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ClientAuxiliaryHudState;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.client.state.RVP_ClientLoiterState;
import org.ywzj.rvp.client.state.RVP_ClientLoiterSupport;
import org.ywzj.rvp.client.state.RVP_ClientRotaryFlightModeState;
import org.ywzj.rvp.client.state.RVP_FireControlStabilizerState;
import org.ywzj.rvp.client.state.RVP_NvgState;
import org.ywzj.rvp.config.RVP_VehicleExtendedConfigManager;
import org.ywzj.rvp.event.RVP_RotaryFlightMode;
import org.ywzj.rvp.ext.WeaponUnitDataExt;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore;
import org.ywzj.rvp.weapon.util.RVP_WeaponLeafResolver;
import org.ywzj.vehicle.all.AllKeys;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMissile;
import java.util.List;
import static org.ywzj.rvp.client.gui.RVP_VehicleActionHudModel.*;

/** 随分辨率适配的载具动作条，按辅助载具状态和当前武器能力显示。 */
public final class RVP_VehicleActionHudOverlay implements IGuiOverlay {
    private static final ResourceLocation[] TEXTURES = {
            texture("deploy_auxiliary"), texture("switch_auxiliary"), texture("tactical_map"), texture("vehicle_status"),
            texture("seeker"), texture("fire_control_stable"), texture("fire_control_semi_auto"), texture("fire_control_off"),
            texture("quick_lock"), texture("loiter"),
            texture("rotary_normal"), texture("rotary_auto_collective"), texture("rotary_hover"),
            texture("gps_mark"), texture("gps_single"), texture("gps_multi"), texture("gps_fast"), texture("gps_radar"),
            texture("airburst"),
            texture("vision_white"), texture("vision_night"), texture("vision_thermal"),
            texture("fire_support_bind"), texture("artillery_map")};
    private static final ResourceLocation DEPLOYED_TEXTURE = texture("deployed_auxiliary");
    private static final String[] LABELS = {"deploy_auxiliary", "switch_auxiliary", "tactical_map", "vehicle_status",
            "seeker", "fire_control_stable", "fire_control_semi_auto", "fire_control_off", "quick_lock",
            "loiter", "rotary_normal", "rotary_auto_collective", "rotary_hover",
            "gps_mark", "gps_single", "gps_multi", "gps_fast", "gps_radar", "airburst",
            "vision_white", "vision_night", "vision_thermal", "fire_support_bind", "artillery_map"};
    private static final KeyMapping[] BINDINGS = {RVP_Keys.DEPLOY_DEPLOYABLE_UAV, RVP_Keys.SWITCH_DEPLOYABLE_UAV,
            RVP_Keys.OPEN_GPS_PANEL, RVP_Keys.OPEN_EQUIP_PANEL, AllKeys.TOGGLE_SEEKER,
            RVP_Keys.FIRE_CONTROL_STABILIZER, RVP_Keys.FIRE_CONTROL_STABILIZER, RVP_Keys.FIRE_CONTROL_STABILIZER,
            RVP_Keys.HMD_TOGGLE, RVP_Keys.TOGGLE_UAV_LOITER,
            AllKeys.TOGGLE_HOVER_MODE, AllKeys.TOGGLE_HOVER_MODE, AllKeys.TOGGLE_HOVER_MODE,
            // GPS 标点与定距装订都走本体锁定键（R），GPS 模式切换沿用火控稳定器键（6）
            AllKeys.FIRE_CONTROL_LOCK, RVP_Keys.FIRE_CONTROL_STABILIZER, RVP_Keys.FIRE_CONTROL_STABILIZER,
            RVP_Keys.FIRE_CONTROL_STABILIZER, RVP_Keys.FIRE_CONTROL_STABILIZER, AllKeys.FIRE_CONTROL_LOCK,
            // 白光/夜视/热成像三态共用本体热成像键
            AllKeys.TOGGLE_THERMAL_IMAGING, AllKeys.TOGGLE_THERMAL_IMAGING, AllKeys.TOGGLE_THERMAL_IMAGING,
            // 火力装订/炮兵地图与战术地图共用 K 键
            RVP_Keys.OPEN_GPS_PANEL, RVP_Keys.OPEN_GPS_PANEL};
    private static ResourceLocation texture(String name) {
        return new ResourceLocation("ywzj_rvp", "textures/gui/actions/" + name + ".png");
    }

    @Override public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        var mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || mc.player == null || mc.level == null
                || !(mc.player.getVehicle() instanceof org.ywzj.vehicle.entity.vehicle.AbstractVehicle vehicle)
                || RVP_ClientHitlState.isActive() || RVP_ClientHitlState.isNoImage(mc.level)) return;
        var state = RVP_ClientAuxiliaryHudState.get(vehicle.getUUID(), mc.level.getGameTime());
        var operator = LocalVehiclePlayer.instance;
        WeaponUnit unit = operator.onVehicle() && operator.vehicle == vehicle ? operator.getWeaponUnit() : null;
        // 开关类动作读取实际状态：开启时常亮，关闭时不亮（不再只靠按键瞬间的高亮）
        boolean seeker = unit != null && supportsSeeker(unit);
        boolean seekerActive = seeker && unit.isSeekerOn();
        // 火控三态只占一张卡：图标/文案按当前模式切换，稳定/半自动视为火控开启（常亮），关闭不亮
        Action fireControlAction = null;
        boolean fireControlActive = false;
        if (unit != null && RVP_MachinegunLeadSolver.isCurrentRvpMachinegun(unit)
                && RVP_FireControlStabilizerState.canToggleMode(unit) && !RVP_ClientGPSUtil.isGPSBombSelected()) {
            RVP_FireControlStabilizerState.Mode mode = RVP_FireControlStabilizerState.getMode(unit);
            fireControlAction = switch (mode) {
                case STABLE -> Action.FIRE_CONTROL_STABLE;
                case SEMI_AUTO -> Action.FIRE_CONTROL_SEMI_AUTO;
                case OFF -> Action.FIRE_CONTROL_OFF;
            };
            fireControlActive = mode != RVP_FireControlStabilizerState.Mode.OFF;
        }
        boolean quickLock = unit != null && RVP_ClientHmdState.supportsQuickLock(unit);
        RVP_ClientHmdState quickLockHmd = RVP_ClientHmdState.getInstance();
        // 快速锁定图标只代表雷达/光电头瞄；IR 头瞄开启不能点亮它。
        boolean quickLockActive = quickLock && (quickLockHmd.isRadarHmd() || quickLockHmd.isEoHmd());
        // GPS 标点在 FAST 模式下由开火键直接取点，R 键被禁用，因此不显示标点卡
        boolean gpsSelected = unit != null && RVP_ClientGPSUtil.isGPSBombSelected();
        RVP_ClientGPSState.Mode gpsMode = RVP_ClientGPSState.getMode();
        boolean gpsMark = gpsSelected && gpsMode != RVP_ClientGPSState.Mode.FAST;
        boolean gpsMarkActive = gpsMark && RVP_ClientGPSState.isActive();
        Action gpsModeAction = !gpsSelected ? null : switch (gpsMode) {
            case SINGLE -> Action.GPS_SINGLE;
            case MULTI -> Action.GPS_MULTI;
            case FAST -> Action.GPS_FAST;
            case RADAR -> Action.GPS_RADAR;
        };
        // 定距空爆卡：按引信数据通用判定（3OF82/3OF26EDKV/XM914 等），AHEAD 自动装订不显示
        boolean airburst = false;
        boolean airburstActive = false;
        if (unit != null) {
            AbstractVehicleWeapon<?> leaf = RVP_WeaponLeafResolver.resolveLeaf(unit).orElse(null);
            if (leaf instanceof RVP_WeaponBase rvpWeapon
                    && rvpWeapon.getData().getFuseData().isProgrammableAirburst()
                    && !rvpWeapon.getData().getFuseData().isAheadEnabled()) {
                airburst = true;
                airburstActive = RVP_AirburstRangeStore.get(vehicle, unit, rvpWeapon.getIndex()) > 0;
            }
        }
        // 通道三态卡：同一按键循环白光/夜视/热成像，状态判定与 RVP_ClientEvents 的按键语义一致。
        Action visionAction = null;
        boolean visionActive = false;
        if (unit != null) {
            boolean hasThermal = unit.withThermalImager();
            boolean hasNightVision = unit.getData() instanceof WeaponUnitDataExt ext
                    && ext.ywzj_rvp$withNightVision();
            if (hasThermal || hasNightVision) {
                boolean scope = LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
                if (scope && hasThermal) {
                    // 观瞄态有热成像：开关归本体 thermalImaging，夜视让位。
                    boolean thermal = LocalVehiclePlayer.instance.thermalImaging;
                    visionAction = thermal ? Action.VISION_THERMAL : Action.VISION_WHITE;
                    visionActive = thermal;
                } else if (scope && hasNightVision) {
                    boolean nvg = RVP_NvgState.isScopeEnabled();
                    visionAction = nvg ? Action.VISION_NIGHT : Action.VISION_WHITE;
                    visionActive = nvg;
                } else {
                    // 第三人称热成像不存在，该键控制的是夜视位。
                    boolean nvg = RVP_NvgState.isThirdPersonEnabled() && (hasNightVision || hasThermal);
                    visionAction = nvg ? Action.VISION_NIGHT : Action.VISION_WHITE;
                    visionActive = nvg;
                }
            }
        }
        // 盘旋卡片只在当前载具自身可盘旋时出现；直升机三态卡片只给配置了自动总距的 RVP 旋翼机
        boolean loiterApplicable = RVP_ClientLoiterSupport.applicable(vehicle);
        boolean loitering = RVP_ClientLoiterState.isVehicleLoitering(vehicle.getId());
        RVP_RotaryFlightMode rotaryMode = vehicle instanceof RotaryWingVehicle
                && RVP_VehicleExtendedConfigManager.hasAutoCollectiveConfig(vehicle.getVehicleId())
                ? RVP_ClientRotaryFlightModeState.get(vehicle.getId()) : null;
        // K 键地图卡跟随当前武器：可呼叫火力时显示装订，炮兵地图弹种显示炮兵地图，否则战术地图。
        RVP_WeaponBase currentRvpWeapon = RVP_WeaponResolveHelper.currentPrimaryRvp(unit);
        boolean fireSupportBind = false;
        boolean artilleryMap = false;
        if (currentRvpWeapon != null) {
            var podData = currentRvpWeapon.getData().getTargetingPodData();
            fireSupportBind = podData != null && !podData.getFireSupportEntries().isEmpty();
            artilleryMap = currentRvpWeapon.getData().getMiscData().isArtilleryMap();
        }
        Action mapAction = RVP_VehicleActionHudModel.mapAction(fireSupportBind, artilleryMap);
        List<Card> cards = cards(state,
                new WeaponPrompts(seeker, seekerActive, fireControlAction, fireControlActive,
                        quickLock, quickLockActive, gpsMark, gpsMarkActive, gpsModeAction, airburst, airburstActive,
                        visionAction, visionActive),
                new FlightPrompts(loiterApplicable, loitering, rotaryMode), mapAction);
        String[] keys = new String[BINDINGS.length];
        boolean[] pressed = new boolean[BINDINGS.length];
        for (Card card : cards) {
            int index = card.action().ordinal();
            keys[index] = keyHint(BINDINGS[index]);
            pressed[index] = BINDINGS[index].isDown();
        }
        drawRow(graphics, cards, keys, pressed);
    }

    private static boolean supportsSeeker(WeaponUnit unit) {
        var weapon = RVP_WeaponResolveHelper.currentPrimary(unit);
        if (weapon instanceof RVP_WeaponBase rvp) {
            return rvp.withSeeker();
        }
        if (!(weapon instanceof VehicleMissile missile)
                || missile.getData().getGuidance() != VehicleMissileWeaponData.Guidance.HOMING) {
            return false;
        }
        return switch (missile.getData().getHomingMode()) {
            case INFRARED, SEMI_ACTIVE_RADAR, ACTIVE_RADAR -> true;
            default -> false;
        };
    }

    /** 组合键保留实际修饰键；鼠标绑定用 M1/M2 等短写，未绑定用 --。 */
    public static String keyHint(KeyMapping key) {
        if (key.isUnbound()) return "--";
        var bound = key.getKey();
        String name = bound.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE
                ? "M" + (bound.getValue() + 1) : bound.getDisplayName().getString();
        String modifier = switch (key.getKeyModifier().name()) {
            case "CONTROL" -> "Ctrl+"; case "SHIFT" -> "Shift+"; case "ALT" -> "Alt+"; default -> "";
        };
        return modifier + name;
    }

    /** 绘制完整动作行，参考单位先统一缩放，再绘制所有卡片和文字；不随 GUI scale 膨胀。 */
    public static void drawRow(GuiGraphics graphics, List<Card> cards, String[] keys, boolean[] pressed) {
        Layout layout = layout(graphics.guiWidth(), graphics.guiHeight(), cards.size());
        if (layout.scale() <= 0) return;
        var pose = graphics.pose(); pose.pushPose();
        pose.translate(layout.left(), layout.top(), 0); pose.scale(layout.scale(), layout.scale(), 1F);
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i); pose.pushPose(); pose.translate(i * (CARD_WIDTH + GAP), 0, 0);
            drawCard(graphics, card, keys[card.action().ordinal()], pressed[card.action().ordinal()]);
            pose.popPose();
        }
        pose.popPose();
    }

    /** 参考画布上的单卡：部署完成换成无箭头/确认勾图标，冷却时压暗并显示红色秒数。 */
    private static void drawCard(GuiGraphics graphics, Card card, String key, boolean pressed) {
        boolean deployed = card.phase() == Phase.DEPLOYED;
        boolean active = card.phase() == Phase.ACTIVE;
        boolean bright = card.enabled() || deployed || active;
        int border = pressed && card.enabled() ? 0xFFE2C774
                : deployed ? 0xC477A68A : active ? 0xC400E5FF : 0xA0526068;
        graphics.fill(0, 0, CARD_WIDTH, CARD_HEIGHT, active ? 0xB3122A30 : 0xB3172127);
        graphics.renderOutline(0, 0, CARD_WIDTH, CARD_HEIGHT, border);
        graphics.fill(1, 1, CARD_WIDTH - 1, 22, pressed && card.enabled() ? 0xDC5C563C : 0xD734424B);
        text(graphics, key, CARD_WIDTH / 2F, 3, 1.9F,
                card.enabled() ? 0xFFE0C66D : 0xFF727A80, CARD_WIDTH - 6);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        graphics.setColor(bright ? 1F : .38F, bright ? 1F : .41F, bright ? 1F : .43F, 1F);
        // 图标统一 62px；键名与标签所有卡片同一字号，不再对通道卡单独放大。
        float iconSize = 62F;
        var pose = graphics.pose(); pose.pushPose();
        pose.translate((CARD_WIDTH - iconSize) / 2F, 22F, 0);
        pose.scale(iconSize / 32F, iconSize / 32F, 1);
        graphics.blit(deployed ? DEPLOYED_TEXTURE : TEXTURES[card.action().ordinal()], 0, 0, 0, 0, 32, 32, 32, 32);
        pose.popPose(); graphics.setColor(1F, 1F, 1F, 1F);
        if (card.phase() == Phase.COOLING)
            text(graphics, card.cooldownSeconds() + "s", CARD_WIDTH / 2F, 49, 2F, 0xFFFF6060, CARD_WIDTH - 6);
        String label = Component.translatable("hud.ywzj_rvp.action." +
                (deployed ? "deployed_auxiliary"
                        : active && card.action() == Action.LOITER ? "loiter_active"
                        : LABELS[card.action().ordinal()])).getString();
        text(graphics, label, CARD_WIDTH / 2F, 84F, 2.1F,
                deployed ? 0xFFA2D5AF : active ? 0xFF7FF2FF : bright ? 0xFFE4E8E8 : 0xFF7A8080, CARD_WIDTH - 4);
    }

    /** 长按键名和标签在卡内缩放，保留完整文字。 */
    private static void text(GuiGraphics graphics, String value, float center, float y, float scale, int color, int maxWidth) {
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(value);
        float fittedScale = Math.min(scale, (float) maxWidth / Math.max(1, textWidth));
        var pose = graphics.pose(); pose.pushPose(); pose.translate(center, y, 0); pose.scale(fittedScale, fittedScale, 1F);
        graphics.drawString(font, value, -textWidth / 2, 0, color, true); pose.popPose();
    }
}
