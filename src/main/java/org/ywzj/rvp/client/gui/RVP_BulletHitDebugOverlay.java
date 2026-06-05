package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientBulletHitDebugState;

/**
 * 子弹命中载具时在屏幕上方显示入射角、飞行距离与伤害倍率（测试用）。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_BulletHitDebugOverlay {

    private static final int COLOR_TITLE = 0xFF40E8FF;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFAAAAAA;

    private RVP_BulletHitDebugOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!RVP_ClientBulletHitDebugState.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        render(event.getGuiGraphics(), mc.font);
    }

    private static void render(GuiGraphics gg, Font font) {
        float angle = RVP_ClientBulletHitDebugState.getIncidenceAngleDeg();
        float dist = RVP_ClientBulletHitDebugState.getDistanceM();
        float total = RVP_ClientBulletHitDebugState.getTotalMultiplier();

        Component title = Component.translatable("overlay.ywzj_rvp.bullet_hit_debug.title");
        Component line1 = Component.translatable("overlay.ywzj_rvp.bullet_hit_debug.angle", fmt(angle));
        Component line2 = Component.translatable("overlay.ywzj_rvp.bullet_hit_debug.distance", fmt(dist));
        Component line3 = Component.translatable("overlay.ywzj_rvp.bullet_hit_debug.total_mult", fmt(total));
        Component detail = Component.translatable(
                "overlay.ywzj_rvp.bullet_hit_debug.detail",
                fmt(RVP_ClientBulletHitDebugState.getDistanceMultiplier()),
                fmt(RVP_ClientBulletHitDebugState.getIncidenceMultiplier()),
                fmt(RVP_ClientBulletHitDebugState.getPenetrationMultiplier()),
                fmt(RVP_ClientBulletHitDebugState.getVehicleTypeMultiplier()));

        int screenW = gg.guiWidth();
        int y = 8;
        int titleW = font.width(title);
        int x = (screenW - titleW) / 2;
        gg.drawString(font, title, x, y, COLOR_TITLE, true);
        y += 10;
        drawCentered(gg, font, line1, y, COLOR_TEXT);
        y += 10;
        drawCentered(gg, font, line2, y, COLOR_TEXT);
        y += 10;
        drawCentered(gg, font, line3, y, 0xFFFFD060);
        y += 10;
        drawCentered(gg, font, detail, y, COLOR_DIM);
    }

    private static void drawCentered(GuiGraphics gg, Font font, Component text, int y, int color) {
        int x = (gg.guiWidth() - font.width(text)) / 2;
        gg.drawString(font, text, x, y, color, true);
    }

    private static String fmt(float value) {
        return String.format("%.2f", value);
    }
}
