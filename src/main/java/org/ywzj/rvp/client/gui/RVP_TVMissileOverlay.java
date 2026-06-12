package org.ywzj.rvp.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.client.render.util.Color;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_TVMissileOverlay {

    private static int lastTickCount = Integer.MIN_VALUE;
    private static float lastYaw;
    private static float lastPitch;
    private static float lastRateDegPerSec;

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) {
            reset();
            return;
        }
        if (!RVP_ClientHitlState.isActive()) {
            reset();
            return;
        }
        Entity e = mc.level.getEntity(RVP_ClientHitlState.getActiveMissileId());
        if (!(e instanceof RVP_MissileEntity) || e.isRemoved()) {
            reset();
            return;
        }

        updateRate(e);

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;
        int x = 6;
        int y = 6;

        Component mode = switch (RVP_ClientHitlState.getVideoMode()) {
            case COLOR -> Component.translatable("overlay.ywzj_rvp.tv_missile.mode.color");
            case BW -> Component.translatable("overlay.ywzj_rvp.tv_missile.mode.bw");
            case THERMAL -> Component.translatable("overlay.ywzj_rvp.tv_missile.mode.thermal");
        };
        Component control = switch (RVP_ClientHitlState.getControlMode()) {
            case MOUSE -> Component.translatable("overlay.ywzj_rvp.hitl.control.mouse");
            case DESIGNATE -> Component.translatable("overlay.ywzj_rvp.hitl.control.designate");
            case VIEW -> Component.translatable("overlay.ywzj_rvp.hitl.control.view");
        };
        Component header = Component.translatable("overlay.ywzj_rvp.tv_missile.header", mode);
        Component turnRate = Component.translatable("overlay.ywzj_rvp.tv_missile.turn_rate", (double) lastRateDegPerSec);
        gg.drawString(font, header, x, y, Color.GREEN, true);
        gg.drawString(font, control, x, y + 10, Color.GREEN, true);
        gg.drawString(font, turnRate, x, y + 20, Color.GREEN, true);

        if (RVP_ClientHitlState.getControlMode() == RVP_EnumHitlControlMode.DESIGNATE) {
            int targetId = RVP_ClientHitlState.getClientDesignatedEntityId();
            if (targetId >= 0) {
                gg.drawString(font,
                        Component.translatable("overlay.ywzj_rvp.hitl.designate.intercept"),
                        x, y + 30, Color.GREEN, true);
            }
        }
    }

    private static void updateRate(Entity missile) {
        int tc = missile.tickCount;
        if (tc == lastTickCount) {
            return;
        }
        if (lastTickCount == Integer.MIN_VALUE) {
            lastTickCount = tc;
            lastYaw = missile.getYRot();
            lastPitch = missile.getXRot();
            lastRateDegPerSec = 0f;
            return;
        }
        int dt = Math.max(1, tc - lastTickCount);
        float yaw = missile.getYRot();
        float pitch = missile.getXRot();
        float dyaw = Mth.wrapDegrees(yaw - lastYaw);
        float dpitch = pitch - lastPitch;
        float delta = (float) Math.sqrt((double) (dyaw * dyaw + dpitch * dpitch));
        float degPerSec = delta * (20f / (float) dt);
        lastRateDegPerSec = Mth.lerp(0.35f, lastRateDegPerSec, degPerSec);
        lastTickCount = tc;
        lastYaw = yaw;
        lastPitch = pitch;
    }

    private static void reset() {
        lastTickCount = Integer.MIN_VALUE;
        lastRateDegPerSec = 0f;
        lastYaw = 0f;
        lastPitch = 0f;
    }
}
