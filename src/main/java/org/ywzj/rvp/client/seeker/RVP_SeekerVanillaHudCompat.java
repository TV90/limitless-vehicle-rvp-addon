package org.ywzj.rvp.client.seeker;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.seeker.RVP_SeekerWeaponUtil;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/**
 * Restores vanilla radar lock squares for RVP SARH when the operator station is not {@code RF} sensor type.
 */
public final class RVP_SeekerVanillaHudCompat {

    private static final float FOV_RING_MIN_RADIUS = 24f;

    private RVP_SeekerVanillaHudCompat() {}

    /** Suppress only the large vanilla seeker FOV ring, not radar lock squares. */
    public static boolean shouldSuppressVanillaSeekerFov(float circleRadius) {
        return RVP_ClientSeekerController.isActive() && circleRadius >= FOV_RING_MIN_RADIUS;
    }

    /** Suppress duplicate small seeker lock circles on target; RVP HUD draws its own rings. */
    public static boolean shouldSuppressVanillaSeekerLockCircle(float circleRadius) {
        return RVP_ClientSeekerController.isActive() && circleRadius >= 4f && circleRadius < FOV_RING_MIN_RADIUS;
    }

    public static void renderSarhRadarLockFrame(GuiGraphics guiGraphics, float partialTick) {
        if (!RVP_ClientSeekerController.isActive()) {
            return;
        }
        var weaponData = RVP_ClientSeekerController.activeWeaponData();
        if (weaponData == null
                || RVP_SeekerWeaponUtil.seekerMode(weaponData) != RVP_EnumGuidanceType.SARH) {
            return;
        }
        WeaponUnit unit = LocalVehiclePlayer.instance.getWeaponUnit();
        if (unit == null) {
            return;
        }
        WeaponUnit root = unit.getRootParentWeaponUnit();
        if (root.getFireControlSensorType() == org.ywzj.vehicle.custom.part.data.WeaponUnitData.FireControlSensorType.RF) {
            return;
        }
        RadarUnit radar = RVP_SeekerWeaponUtil.resolveFireControlRadar(root);
        if (radar == null) {
            return;
        }
        Entity locked = radar.getLockedEntity();
        if (locked == null || !locked.isAlive()) {
            return;
        }
        RadarUnit.DetectedObject detected = radar.getDetectedEntities().get(locked.getId());
        Entity drawEntity = detected != null && detected.entity != null && detected.entity.isAlive()
                ? detected.entity : locked;
        double x = Mth.lerp(partialTick, drawEntity.xo, drawEntity.getX());
        double y = Mth.lerp(partialTick, drawEntity.yo, drawEntity.getY());
        double z = Mth.lerp(partialTick, drawEntity.zo, drawEntity.getZ());
        Vec3 center = new Vec3(x, y, z).add(drawEntity.getBoundingBox().getCenter().subtract(drawEntity.position()));
        Vec3 screenPos = VectorUtil.worldToScreen(center);
        if (screenPos.z < 0) {
            return;
        }
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(screenPos.x, screenPos.y, 0);
        RenderHelper.drawSquare(guiGraphics, 0, 0, 15, Color.GREEN);
        RenderHelper.drawSquare(guiGraphics, 0, 0, 10, Color.GREEN);
        poseStack.popPose();
    }
}
