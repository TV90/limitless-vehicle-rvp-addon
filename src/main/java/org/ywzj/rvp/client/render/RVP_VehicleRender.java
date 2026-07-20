package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Math;
import org.joml.Quaternionf;
import org.ywzj.rvp.client.debug.RVP_SbmProbeDebug;
import org.ywzj.vehicle.api.animation.IAnimationEntity;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import static org.ywzj.vehicle.client.render.animation.util.PoseBlenders.BLENDER;

public class RVP_VehicleRender<T extends AbstractVehicle> extends EntityRenderer<T> {

    public RVP_VehicleRender(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(T vehicle, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        ResourceLocation displayId = vehicle.getDisplayId();
        var display = ClientAssetsManager.INSTANCE.getVehicleDisplay(displayId).orElse(null);
        if (display == null) {
            return;
        }
        VehicleBedrockModel model = display.getModel();
        if (model == null) {
            return;
        }
        RVP_SbmProbeDebug.noteRvpVehicleRender(vehicle, model);

        poseStack.pushPose();
        try {
            super.render(vehicle, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            Vec3 root = vehicle.centerOffset;
            Quaternionf rot = new Quaternionf()
                    .rotateY(Math.toRadians(-vehicle.yRotO))
                    .rotateX(Math.toRadians(vehicle.xRotO))
                    .rotateZ(Math.toRadians(vehicle.zRotO))
                    .slerp(vehicle.rotYXZ(), partialTick);
            poseStack.rotateAround(rot, (float) root.x, (float) root.y, (float) root.z);

            if (vehicle instanceof IAnimationEntity<?, ?> animationEntity) {
                var instance = animationEntity.getAnimationInstance();
                if (instance != null) {
                    instance.getContext().setPartialTick(partialTick);
                    instance.tick();
                    model.applyPose(BLENDER.blend(model.getBindPose(), instance.getCurrentPose()));
                }
            }

            int actualLight = vehicle.isDestroyed() ? 64 : packedLight;
            boolean localPlayerVehicle = vehicle == LocalVehiclePlayer.instance.getVehicle();
            RVP_CockpitPassengerRenderContext.begin(vehicle, partialTick);
            try {
                model.renderToBuffer(poseStack, bufferSource, display.getTexture(), actualLight);
                model.renderSpecialBones(poseStack, bufferSource, actualLight, OverlayTexture.NO_OVERLAY, localPlayerVehicle);
            } finally {
                RVP_CockpitPassengerRenderContext.end();
            }

            RVP_CustomMountRenderLogic.render(vehicle, model, poseStack, bufferSource, packedLight);
            vehicle.getPartUnits().forEach(partUnit -> {
                if (partUnit instanceof WeaponUnit weaponUnit
                        && RVP_CustomMountRenderLogic.shouldSuppressDefaultWeaponDisplay(weaponUnit)) {
                    return;
                }
                partUnit.render(poseStack, bufferSource, packedLight);
            });
            vehicle.getDecorationUnits().values().forEach(decorationUnit -> decorationUnit.render(poseStack, bufferSource, packedLight));
            vehicle.getBulletHoleParticles().forEach(bulletHoleParticle -> bulletHoleParticle.renderOnVehicle(partialTick, poseStack, bufferSource));
            model.applyPose(model.getBindPose());
            vehicle.lastRenderTime = System.currentTimeMillis();
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    public ResourceLocation getTextureLocation(@NotNull T entity) {
        return null;
    }
}
