package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.ywzj.rvp.client.seeker.RVP_ClientSeekerController;
import org.ywzj.rvp.client.seeker.RVP_SeekerGeometry;
import org.ywzj.rvp.weapon.seeker.RVP_SeekerWeaponUtil;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * RVP IR / SARH seeker HUD: screen-centre FOV ring + target lock rings (BFMC-style).
 */
public final class RVP_SeekerHudOverlay implements IGuiOverlay {

    public static final RVP_SeekerHudOverlay INSTANCE = new RVP_SeekerHudOverlay();

    private static final float OUTER_LOCK_RADIUS = 15f;
    private static final float INNER_LOCK_RADIUS = 11f;

    private RVP_SeekerHudOverlay() {}

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (!RVP_ClientSeekerController.isActive()) {
            return;
        }
        RVP_WeaponData data = RVP_ClientSeekerController.activeWeaponData();
        if (data == null) {
            return;
        }

        float centerX = screenWidth * 0.5f;
        float centerY = screenHeight * 0.5f;
        var operator = LocalVehiclePlayer.instance.getWeaponUnit();
        WeaponUnit root = operator != null ? operator.getRootParentWeaponUnit() : null;
        double[] ref = org.ywzj.rvp.client.seeker.RVP_SeekerGeometry.screenReference(root, centerX, centerY);
        drawFovRing(guiGraphics, (float) ref[0], (float) ref[1], root, data, partialTick);

        Entity target = RVP_ClientSeekerController.displayTarget();
        if (target != null) {
            drawLockRings(guiGraphics, target, partialTick);
        }
    }

    private static void drawFovRing(GuiGraphics guiGraphics, float centerX, float centerY, WeaponUnit operatorUnit,
                                    RVP_WeaponData data, float partialTick) {
        float radius = RVP_SeekerGeometry.screenFovRadius(operatorUnit, RVP_SeekerWeaponUtil.seekerFov(data));
        radius = Math.max(radius, 4f);

        float alpha = 0.45f;
        if (RVP_ClientSeekerController.isLocked()) {
            alpha = 0.65f;
        } else {
            var unit = LocalVehiclePlayer.instance.getWeaponUnit();
            if (unit != null) {
                alpha += (float) Math.sin(unit.getLockCoolingTick() / 5.0 * Math.PI) * 0.15f;
            }
        }
        int color = (Color.WHITE & 0x00FFFFFF) | ((int) (alpha * 255) << 24);
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(centerX, centerY, 0);
        GuiHelper.drawCircle(poseStack, 0, 0, radius, color, 0.012f, 0, 0);
        poseStack.popPose();
    }

    private static void drawLockRings(GuiGraphics guiGraphics, Entity target, float partialTick) {
        double curX = Mth.lerp(partialTick, target.xo, target.getX());
        double curY = Mth.lerp(partialTick, target.yo, target.getY());
        double curZ = Mth.lerp(partialTick, target.zo, target.getZ());
        Vec3 centerOffset = target.getBoundingBox().getCenter().subtract(target.position());
        Vec3 worldPos = new Vec3(curX, curY, curZ).add(centerOffset);
        Vec3 screenPos = VectorUtil.worldToScreen(worldPos);
        if (screenPos.z < 0) {
            return;
        }

        boolean locked = RVP_ClientSeekerController.isLocked();
        float progress = locked ? 1f : RVP_ClientSeekerController.lockProgress();
        int rgb = locked ? 0x00FF0000 : 0x00FFFFFF;
        float alpha = locked ? 0.9f : 0.55f;
        int color = rgb | ((int) (alpha * 255) << 24);

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(screenPos.x, screenPos.y, 0);

        GuiHelper.drawCircle(poseStack, 0, 0, OUTER_LOCK_RADIUS, color, 0.03f, 0, 0);

        if (locked || progress > 0f) {
            if (locked) {
                GuiHelper.drawCircle(poseStack, 0, 0, INNER_LOCK_RADIUS, color, 0.05f, 0, 0);
            } else {
                // Top (90°) → Q1 → Q4 → Q3 → Q2 in screen space; shader angle 0.5 = top.
                float end = 0.5f;
                float start = end - progress;
                if (start < 0f) {
                    start += 1f;
                }
                GuiHelper.drawCircle(poseStack, 0, 0, INNER_LOCK_RADIUS, color, 0.05f, start, end);
            }
        }

        poseStack.popPose();
    }
}
