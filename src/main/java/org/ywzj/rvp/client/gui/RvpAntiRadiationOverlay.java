package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.YwzjRvp;
import org.ywzj.rvp.client.state.RvpClientAntiRadiationState;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.util.VectorUtil;

@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = YwzjRvp.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RvpAntiRadiationOverlay {

    private static void drawRectOutline(GuiGraphics gg, int x, int y, int w, int h, int color, int t) {
        gg.fill(x, y, x + w, y + t, color);
        gg.fill(x, y + h - t, x + w, y + h, color);
        gg.fill(x, y + t, x + t, y + h - t, color);
        gg.fill(x + w - t, y + t, x + w, y + h - t, color);
    }

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
        if (!RvpClientAntiRadiationState.isActive()) {
            return;
        }

        GuiGraphics gg = event.getGuiGraphics();
        int boxColor = 0xCC7CFF00;

        for (RvpClientAntiRadiationState.Contact c : RvpClientAntiRadiationState.getContacts()) {
            Vec3 screen = VectorUtil.worldToScreen(c.position());
            if (screen.z <= 0) {
                continue;
            }
            int size = 14;
            int x = (int) (screen.x - size / 2f);
            int y = (int) (screen.y - size / 2f);
            drawRectOutline(gg, x, y, size, size, boxColor, 2);
        }

        int lockedVehicleId = RvpClientAntiRadiationState.getLockedVehicleId();
        int lockedRadarIndex = RvpClientAntiRadiationState.getLockedRadarIndex();
        if (lockedVehicleId >= 0 && lockedRadarIndex >= 0) {
            for (RvpClientAntiRadiationState.Contact c : RvpClientAntiRadiationState.getContacts()) {
                if (c.vehicleId() != lockedVehicleId || c.radarIndex() != lockedRadarIndex) {
                    continue;
                }
                Vec3 screen = VectorUtil.worldToScreen(c.position());
                if (screen.z <= 0) {
                    return;
                }
                PoseStack pose = gg.pose();
                pose.pushPose();
                pose.translate(screen.x, screen.y, 0);
                GuiHelper.drawCircle(pose, 0, 0, 10, Color.RED, 0.06f, 0f, 1f);
                GuiHelper.drawCircle(pose, 0, 0, 7, Color.RED, 0.06f, 0f, 1f);
                pose.popPose();
                return;
            }
        }
    }
}
