package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ClientSaclosState;
import org.ywzj.vehicle.client.render.util.Color;

/** SACLOS laser-off hint only; lock marker is the world-space green square. */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_SaclosOverlay {

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }
        if (RVP_ClientHitlState.isDesignateMode()) {
            return;
        }
        if (!RVP_ClientSaclosState.isGuiding() || RVP_ClientSaclosState.isLaserEnabled()) {
            return;
        }

        GuiGraphics gg = event.getGuiGraphics();
        Component hint = Component.translatable("overlay.ywzj_rvp.saclos.laser_off");
        int w = mc.font.width(hint);
        int cx = gg.guiWidth() / 2;
        int cy = gg.guiHeight() / 2;
        gg.drawString(mc.font, hint, cx - w / 2, cy + 18, Color.RED, true);
    }
}
