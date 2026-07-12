package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.ywzj.rvp.client.resource.RVP_DisplayTransparentModeManager;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

public final class RVP_CockpitPassengerRenderer {

    private RVP_CockpitPassengerRenderer() {}

    public static boolean shouldCustomRenderLocalPassenger(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.getVehicle() != vehicle) {
            return false;
        }
        CameraType cameraType = mc.options.getCameraType();
        if (cameraType.isFirstPerson()) {
            return false;
        }
        return ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId())
                .map(display -> RVP_DisplayTransparentModeManager.INSTANCE.hasCockpitDepthFix(display.getModel()))
                .orElse(false);
    }

    public static void renderLocalPassengerBeforeCockpit(VehicleBedrockModel model,
                                                         PoseStack poseStack,
                                                         MultiBufferSource source,
                                                         int packedLight) {
        if (!RVP_DisplayTransparentModeManager.INSTANCE.hasCockpitDepthFix(model)) {
            return;
        }
        RVP_CockpitPassengerRenderContext.Context context = RVP_CockpitPassengerRenderContext.current();
        if (context == null) {
            return;
        }
        AbstractVehicle vehicle = context.vehicle();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.getVehicle() != vehicle || mc.options.getCameraType().isFirstPerson()) {
            return;
        }

        EntityRenderer<? super LocalPlayer> renderer = mc.getEntityRenderDispatcher().getRenderer(player);
        if (renderer == null) {
            return;
        }

        float partialTick = context.partialTick();
        Vec3 vehiclePos = new Vec3(
                Mth.lerp(partialTick, vehicle.xo, vehicle.getX()),
                Mth.lerp(partialTick, vehicle.yo, vehicle.getY()),
                Mth.lerp(partialTick, vehicle.zo, vehicle.getZ())
        );
        Vec3 playerPos = new Vec3(
                Mth.lerp(partialTick, player.xo, player.getX()),
                Mth.lerp(partialTick, player.yo, player.getY()),
                Mth.lerp(partialTick, player.zo, player.getZ())
        );

        float vehicleYRot = Mth.rotLerp(partialTick, vehicle.yRotO, vehicle.getYRot());
        float vehicleXRot = Mth.lerp(partialTick, vehicle.xRotO, vehicle.getXRot());
        float vehicleZRot = Mth.lerp(partialTick, vehicle.zRotO, vehicle.getZRot());

        Quaternionf inverseVehicleRot = new Quaternionf()
                .rotateY((float) Math.toRadians(-vehicleYRot))
                .rotateX((float) Math.toRadians(vehicleXRot))
                .rotateZ((float) Math.toRadians(vehicleZRot))
                .invert();

        Vec3 center = vehiclePos.add(vehicle.centerOffset);
        Vector3f local = inverseVehicleRot.transform(playerPos.subtract(center).toVector3f());
        Vec3 localOffset = new Vec3(local.x(), local.y(), local.z()).add(vehicle.centerOffset);

        float playerYaw = Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
        float relativeYaw = Mth.wrapDegrees(playerYaw - vehicleYRot);

        poseStack.pushPose();
        poseStack.translate(localOffset.x, localOffset.y, localOffset.z);
        renderer.render(player, relativeYaw, partialTick, poseStack, source, packedLight);
        poseStack.popPose();
    }
}
