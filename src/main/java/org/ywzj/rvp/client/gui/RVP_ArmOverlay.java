package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.state.RVP_ClientArmState;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.List;

public class RVP_ArmOverlay {

    private static final int BOX_SIZE = 14;
    private static final int BOX_COLOR = 0xCC7CFF00;

    public static void render(GuiGraphics guiGraphics) {
        RVP_ClientArmState state = RVP_ClientArmState.getInstance();
        if (!state.isActive()) {
            return;
        }

        List<RVP_ClientArmState.Contact> contacts = state.getContacts();
        if (contacts.isEmpty()) {
            return;
        }

        int screenWidth = guiGraphics.guiWidth();
        int screenHeight = guiGraphics.guiHeight();
        int lockedVehicleId = state.getLockedVehicleId();
        int lockedRadarIndex = state.getLockedRadarIndex();

        for (RVP_ClientArmState.Contact contact : contacts) {
            Vec3 screen = VectorUtil.worldToScreen(contact.position());
            if (screen.z <= 0) {
                continue;
            }
            double sx = screen.x;
            double sy = screen.y;
            if (sx < 0 || sx > screenWidth || sy < 0 || sy > screenHeight) {
                continue;
            }

            boolean isLocked = contact.vehicleId() == lockedVehicleId && contact.radarIndex() == lockedRadarIndex;

            // Green box for all contacts (same as old RVP)
            int x = (int) (sx - BOX_SIZE / 2f);
            int y = (int) (sy - BOX_SIZE / 2f);
            drawRectOutline(guiGraphics, x, y, BOX_SIZE, BOX_SIZE, BOX_COLOR, 2);

            // Red smooth double circles for locked target (same as old RVP using GuiHelper)
            if (isLocked) {
                PoseStack pose = guiGraphics.pose();
                pose.pushPose();
                pose.translate(sx, sy, 0);
                GuiHelper.drawCircle(pose, 0, 0, 10, Color.RED, 0.06f, 0f, 1f);
                GuiHelper.drawCircle(pose, 0, 0, 7, Color.RED, 0.06f, 0f, 1f);
                pose.popPose();
            }
        }
    }

    private static void drawRectOutline(GuiGraphics gg, int x, int y, int w, int h, int color, int t) {
        gg.fill(x, y, x + w, y + t, color);
        gg.fill(x, y + h - t, x + w, y + h, color);
        gg.fill(x, y + t, x + t, y + h - t, color);
        gg.fill(x + w - t, y + t, x + w, y + h - t, color);
    }
}
