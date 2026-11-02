package org.ywzj.rvp.client.gui;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

/** 战争雷霆式底部动作卡：透明图标、独立键帽，读取真实按键绑定，不承担交互或权限判断。 */
public final class RVP_VehicleActionHudOverlay implements IGuiOverlay {
    /** 顺序和图形语义固定，避免切车型时按键提示跳位。 */
    public enum Action {
        DEPLOY("deploy_auxiliary"), SWITCH("switch_auxiliary"), MAP("tactical_map"), STATUS("vehicle_status");
        public final ResourceLocation texture;
        public final String label;
        Action(String name) {
            texture = new ResourceLocation("ywzj_rvp", "textures/gui/actions/" + name + ".png");
            label = "hud.ywzj_rvp.action." + name;
        }
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (mc.options.hideGui || mc.screen != null || mc.player == null || mc.level == null
                || local == null || !local.onVehicle() || local.vehicle == null
                || RVP_ClientHitlState.isActive() || RVP_ClientHitlState.isNoImage(mc.level)) return;
        KeyMapping[] bindings = {RVP_Keys.DEPLOY_DEPLOYABLE_UAV, RVP_Keys.SWITCH_DEPLOYABLE_UAV,
                RVP_Keys.OPEN_GPS_PANEL, RVP_Keys.OPEN_EQUIP_PANEL};
        // 部署键无适用配置时置灰；切换仍显示，以兼容无需部署配置的子机返回母车操作。
        boolean deployable = RVP_DeployableUavConfigCache.get(local.vehicle.getVehicleId()).isConfigured();
        int cardWidth = Math.min(52, Math.max(32, (graphics.guiWidth() - 28) / 4));
        int left = (graphics.guiWidth() - (cardWidth * 4 + 12)) / 2;
        int top = Math.max(4, graphics.guiHeight() - 77);
        for (Action action : Action.values()) {
            KeyMapping key = bindings[action.ordinal()];
            String hint = keyHint(key);
            drawCard(graphics, action, hint, left + action.ordinal() * (cardWidth + 4), top,
                    cardWidth, action != Action.DEPLOY || deployable, key.isDown());
        }
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

    /** 图标尺寸不受文字长度影响；长组合键裁剪居中，完整绑定仍可在控制设置中修改。 */
    public static void drawCard(GuiGraphics graphics, Action action, String key, int x, int y,
                                int width, boolean enabled, boolean pressed) {
        var font = Minecraft.getInstance().font;
        int frame = pressed && enabled ? 0xFFE2C774 : 0xA0526068;
        int ink = enabled ? 0xFFE4E8E8 : 0xFF727A80;
        graphics.fill(x, y, x + width, y + 58, 0xAB172127);
        graphics.renderOutline(x, y, width, 58, frame);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 14, pressed && enabled ? 0xDC5C563C : 0xD734424B);
        String shown = font.plainSubstrByWidth(key, width - 6);
        graphics.drawString(font, shown, x + (width - font.width(shown)) / 2, y + 3,
                enabled ? 0xFFE0C66D : 0xFF727A80, false);
        // 四个 PNG 使用同一笔画/构图，灰态只改 shader tint，不生成第二套纹理。
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        graphics.setColor(enabled ? 1F : .45F, enabled ? 1F : .48F, enabled ? 1F : .5F, 1F);
        graphics.blit(action.texture, x + (width - 32) / 2, y + 16, 0, 0, 32, 32, 32, 32);
        graphics.setColor(1F, 1F, 1F, 1F);
        String label = font.plainSubstrByWidth(Component.translatable(action.label).getString(), width - 4);
        graphics.drawString(font, label, x + (width - font.width(label)) / 2, y + 48, ink, false);
    }
}
