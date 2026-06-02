package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.YwzjRvp;
import org.ywzj.rvp.client.state.RvpClientGPSState;
import org.ywzj.rvp.client.state.RvpClientGPSUtil;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.util.VectorUtil;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = YwzjRvp.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RvpGPSOverlay {

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (mc.options.hideGui) {
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();

        if (RvpClientGPSUtil.isGPSBombSelected()) {
            Vec3 aimPos = RvpClientGPSUtil.raycastGPSTarget(mc);
            if (aimPos != null) {
                Vec3 screenAimPos = VectorUtil.worldToScreen(aimPos);
                if (screenAimPos.z > 0) {
                    PoseStack pose = gg.pose();
                    pose.pushPose();
                    pose.translate(screenAimPos.x, screenAimPos.y, 0);
                    GuiHelper.drawCircle(pose, 0, 0, 6, Color.WHITE, 0.05f, 0f, 1f);
                    pose.popPose();
                }
            }
        }

        if (!RvpClientGPSState.isActive()) {
            return;
        }
        if (!player.level().dimension().location().equals(RvpClientGPSState.getDimension())) {
            return;
        }
        Vec3 targetPos = RvpClientGPSState.getPos();
        Vec3 screenPos = VectorUtil.worldToScreen(targetPos);
        if (screenPos.z <= 0) {
            return;
        }

        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(screenPos.x, screenPos.y, 0);
        GuiHelper.drawCircle(pose, 0, 0, 10, Color.GREEN, 0.05f, 0f, 1f);
        pose.popPose();

        double dist = player.position().distanceTo(targetPos);
        int bx = (int) Math.floor(targetPos.x);
        int by = (int) Math.floor(targetPos.y);
        int bz = (int) Math.floor(targetPos.z);
        Component line1 = Component.translatable("overlay.ywzj_rvp.gps.distance", (int) dist);
        Component line2 = Component.translatable("overlay.ywzj_rvp.gps.coords", bx, by, bz);

        Font font = mc.font;
        int w1 = font.width(line1);
        int w2 = font.width(line2);
        gg.drawString(font, line1, (int) (screenPos.x - w1 / 2f), (int) (screenPos.y + 14), Color.GREEN, true);
        gg.drawString(font, line2, (int) (screenPos.x - w2 / 2f), (int) (screenPos.y + 24), Color.GREEN, true);
    }
}
