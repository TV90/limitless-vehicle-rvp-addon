package org.ywzj.rvp.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientHitlCamera;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.client.state.RVP_ClientHitlUtil;
import org.ywzj.rvp.client.state.RVP_ClientSaclosState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.client.render.util.Color;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.util.VectorUtil;

import static org.ywzj.vehicle.util.RenderHelper.drawSquare;

/**
 * TV SACLOS (DESIGNATE): crosshair projected from the same 3D aim point as designation.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_HitlDesignateOverlay {

    private RVP_HitlDesignateOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!RVP_ClientHitlState.isDesignateMode()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }

        Entity entity = mc.level.getEntity(RVP_ClientHitlState.getActiveMissileId());
        if (!(entity instanceof RVP_MissileEntity missile)) {
            return;
        }

        Vec3 aimPoint = RVP_ClientHitlUtil.resolveAimPoint(
                mc, missile,
                RVP_ClientHitlCamera.getAimYaw(),
                RVP_ClientHitlCamera.getAimPitch(),
                RVP_ClientHitlState.DESIGNATE_AIM_RANGE,
                false);
        if (aimPoint == null) {
            return;
        }

        Vec3 crosshair = VectorUtil.worldToScreen(aimPoint);
        if (crosshair.z <= 0.0D) {
            return;
        }

        GuiGraphics gg = event.getGuiGraphics();
        PoseStack poseStack = gg.pose();

        poseStack.pushPose();
        poseStack.translate(crosshair.x, crosshair.y, 0.0D);
        drawSquare(gg, 0, 0, 5, Color.GREEN);
        gg.vLine(0, -24, -6, Color.GREEN);
        gg.vLine(0, 6, 24, Color.GREEN);
        gg.hLine(-24, -6, 0, Color.GREEN);
        gg.hLine(6, 24, 0, Color.GREEN);
        poseStack.popPose();

        Vec3 designated = RVP_ClientSaclosState.getLaserHudPos();
        if (designated == null) {
            return;
        }

        Vec3 lockScreen = VectorUtil.worldToScreen(designated);
        if (lockScreen.z <= 0.0D) {
            return;
        }

        int width = gg.guiWidth();
        int height = gg.guiHeight();
        if (lockScreen.x < 0 || lockScreen.y < 0 || lockScreen.x > width || lockScreen.y > height) {
            return;
        }
        if (lockScreen.distanceToSqr(crosshair.x, crosshair.y, 0.0D) < 64.0D) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(lockScreen.x, lockScreen.y, 0.0D);
        drawHollowDiagonalCross(gg, 0, 0, 28, 2, 5, Color.GREEN);
        poseStack.popPose();
    }

    private static void drawHollowDiagonalCross(GuiGraphics gg, int x, int y, int size, int thickness, int gap, int color) {
        int len = Math.max(2, size / 2);
        int halfThickness = thickness / 2;
        PoseStack pose = gg.pose();

        pose.pushPose();
        pose.translate(x, y - 0.5D, 0.0D);
        pose.mulPose(Axis.ZP.rotationDegrees(45.0F));
        gg.fill(gap, -halfThickness, gap + len, thickness - halfThickness, color);
        gg.fill(-gap - len, -halfThickness, -gap, thickness - halfThickness, color);
        pose.popPose();

        pose.pushPose();
        pose.translate(x, y - 0.5D, 0.0D);
        pose.mulPose(Axis.ZP.rotationDegrees(-45.0F));
        gg.fill(gap, -halfThickness, gap + len, thickness - halfThickness, color);
        gg.fill(-gap - len, -halfThickness, -gap, thickness - halfThickness, color);
        pose.popPose();
    }
}
