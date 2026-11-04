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
import org.ywzj.rvp.client.state.RVP_ClientGPSUtil;
import org.ywzj.rvp.client.state.RVP_ClientHmdState;
import org.ywzj.rvp.client.state.RVP_FireControlStabilizerState;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.all.AllKeys;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.VehicleMissile;
import java.util.List;
import static org.ywzj.rvp.client.gui.RVP_VehicleActionHudModel.*;

/** 随分辨率适配的载具动作条，按辅助载具状态和当前武器能力显示。 */
public final class RVP_VehicleActionHudOverlay implements IGuiOverlay {
    private static final ResourceLocation[] TEXTURES = {
            texture("deploy_auxiliary"), texture("switch_auxiliary"), texture("tactical_map"), texture("vehicle_status"),
            texture("seeker"), texture("fire_control"), texture("quick_lock")};
    private static final ResourceLocation DEPLOYED_TEXTURE = texture("deployed_auxiliary");
    private static final String[] LABELS = {"deploy_auxiliary", "switch_auxiliary", "tactical_map", "vehicle_status",
            "seeker", "fire_control", "quick_lock"};
    private static final KeyMapping[] BINDINGS = {RVP_Keys.DEPLOY_DEPLOYABLE_UAV, RVP_Keys.SWITCH_DEPLOYABLE_UAV,
            RVP_Keys.OPEN_GPS_PANEL, RVP_Keys.OPEN_EQUIP_PANEL, AllKeys.TOGGLE_SEEKER,
            RVP_Keys.FIRE_CONTROL_STABILIZER, RVP_Keys.HMD_TOGGLE};
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
        boolean seeker = unit != null && supportsSeeker(unit);
        boolean fireControl = unit != null && RVP_MachinegunLeadSolver.isCurrentRvpMachinegun(unit)
                && RVP_FireControlStabilizerState.canToggleMode(unit) && !RVP_ClientGPSUtil.isGPSBombSelected();
        boolean quickLock = unit != null && RVP_ClientHmdState.supportsQuickLock(unit);
        List<Card> cards = cards(state, seeker, fireControl, quickLock);
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
        boolean bright = card.enabled() || deployed;
        int border = pressed && card.enabled() ? 0xFFE2C774 : deployed ? 0xC477A68A : 0xA0526068;
        graphics.fill(0, 0, CARD_WIDTH, CARD_HEIGHT, 0xB3172127);
        graphics.renderOutline(0, 0, CARD_WIDTH, CARD_HEIGHT, border);
        graphics.fill(1, 1, CARD_WIDTH - 1, 22, pressed && card.enabled() ? 0xDC5C563C : 0xD734424B);
        text(graphics, key, CARD_WIDTH / 2F, 3, 1.6F, card.enabled() ? 0xFFE0C66D : 0xFF727A80, CARD_WIDTH - 6);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        graphics.setColor(bright ? 1F : .38F, bright ? 1F : .41F, bright ? 1F : .43F, 1F);
        var pose = graphics.pose(); pose.pushPose(); pose.translate(18, 25, 0); pose.scale(52F / 32, 52F / 32, 1);
        graphics.blit(deployed ? DEPLOYED_TEXTURE : TEXTURES[card.action().ordinal()], 0, 0, 0, 0, 32, 32, 32, 32);
        pose.popPose(); graphics.setColor(1F, 1F, 1F, 1F);
        if (card.phase() == Phase.COOLING)
            text(graphics, card.cooldownSeconds() + "s", CARD_WIDTH / 2F, 49, 2F, 0xFFFF6060, CARD_WIDTH - 6);
        String label = Component.translatable("hud.ywzj_rvp.action." +
                (deployed ? "deployed_auxiliary" : LABELS[card.action().ordinal()])).getString();
        text(graphics, label, CARD_WIDTH / 2F, 83, 1.75F,
                deployed ? 0xFFA2D5AF : bright ? 0xFFE4E8E8 : 0xFF727A80, CARD_WIDTH - 4);
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
