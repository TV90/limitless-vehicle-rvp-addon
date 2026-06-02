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
import org.ywzj.rvp.YwzjRvp;
import org.ywzj.rvp.client.state.RvpClientTVMissileState;
import org.ywzj.rvp.entity.weapon.TVMissileEntity;
import org.ywzj.vehicle.client.render.util.Color;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = YwzjRvp.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RvpTVMissileOverlay {

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
        if (!RvpClientTVMissileState.isActive()) {
            reset();
            return;
        }
        Entity e = mc.level.getEntity(RvpClientTVMissileState.getActiveMissileId());
        if (!(e instanceof TVMissileEntity missile) || missile.isRemoved()) {
            reset();
            return;
        }

        updateRate(missile);

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;
        int x = 6;
        int y = 6;

        Component mode = switch (RvpClientTVMissileState.getVideoMode()) {
            case COLOR -> Component.translatable("overlay.ywzj_rvp.tv_missile.mode.color");
            case BW -> Component.translatable("overlay.ywzj_rvp.tv_missile.mode.bw");
            case THERMAL -> Component.translatable("overlay.ywzj_rvp.tv_missile.mode.thermal");
        };
        Component header = Component.translatable("overlay.ywzj_rvp.tv_missile.header", mode);
        Component turnRate = Component.translatable("overlay.ywzj_rvp.tv_missile.turn_rate", (double) lastRateDegPerSec);
        gg.drawString(font, header, x, y, Color.GREEN, true);
        gg.drawString(font, turnRate, x, y + 10, Color.GREEN, true);
    }

    private static void updateRate(TVMissileEntity missile) {
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
