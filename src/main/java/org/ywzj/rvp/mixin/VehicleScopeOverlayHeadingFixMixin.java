package org.ywzj.rvp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.vehicle.client.gui.VehicleScopeOverlay;
import org.ywzj.vehicle.util.RenderHelper;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

@Mixin(value = VehicleScopeOverlay.class, remap = false)
public class VehicleScopeOverlayHeadingFixMixin {

    @Inject(method = "renderCubeOBB", at = @At("HEAD"), cancellable = true, remap = false)
    private void ywzj_rvp$renderCubeObbWithRealHeading(
            VehicleCubeOBB cubeOBB,
            Vec3 vehiclePos,
            Vector3f axisX,
            Vector3f axisZ,
            float rot,
            PoseStack poseStack,
            GuiGraphics guiGraphics,
            float partialTick,
            int color,
            CallbackInfo ci
    ) {
        if (LocalVehiclePlayer.instance == null || LocalVehiclePlayer.instance.getVehicle() == null) {
            return;
        }
        ResourceLocation vehicleId = LocalVehiclePlayer.instance.getVehicle().getVehicleId();
        if (vehicleId == null || !"rvp".equals(vehicleId.getNamespace())) {
            return;
        }

        Vec3 cubePos = new Vec3(Mth.lerp(partialTick, cubeOBB.positionO.x, cubeOBB.position.x),
                Mth.lerp(partialTick, cubeOBB.positionO.y, cubeOBB.position.y),
                Mth.lerp(partialTick, cubeOBB.positionO.z, cubeOBB.position.z));
        Vec3 offset = cubePos.subtract(vehiclePos);
        float offsetX = (float) ((offset.x * axisX.x() + offset.y * axisX.y() + offset.z * axisX.z()) * 10.0);
        float offsetZ = (float) ((offset.x * axisZ.x() + offset.y * axisZ.y() + offset.z * axisZ.z()) * 10.0);

        Quaternionf rotation = null;
        if (cubeOBB.rotationO != null && cubeOBB.rotation != null) {
            rotation = new Quaternionf(cubeOBB.rotationO).slerp(cubeOBB.rotation, partialTick);
        } else if (cubeOBB.rotation != null) {
            rotation = new Quaternionf(cubeOBB.rotation);
        } else if (cubeOBB.selfRot() != null) {
            rotation = new Quaternionf(cubeOBB.selfRot());
        }

        float headingDeg = (float) (rot - Math.toDegrees(cubeOBB.group.baseRotation.getEulerAnglesYXZ(new Vector3f()).y));
        if (rotation != null) {
            Vector3f obbXAxis = rotation.transform(new Vector3f(1, 0, 0));
            float projectedX = obbXAxis.dot(axisX);
            float projectedZ = obbXAxis.dot(axisZ);
            if (Math.abs(projectedX) >= 1.0e-5f || Math.abs(projectedZ) >= 1.0e-5f) {
                headingDeg = (float) Math.toDegrees(Math.atan2(projectedZ, projectedX));
            }
        }

        poseStack.pushPose();
        poseStack.translate(offsetX, offsetZ, 0f);
        poseStack.mulPose(Axis.ZP.rotationDegrees(headingDeg));
        int hw = (int) (cubeOBB.width * 5.0);
        int hd = (int) (cubeOBB.depth * 5.0);
        RenderHelper.drawRectByCorner(guiGraphics, -hw, hw, -hd, hd, color, 1);
        poseStack.popPose();
        ci.cancel();
    }
}
