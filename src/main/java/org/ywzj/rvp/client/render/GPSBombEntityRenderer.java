package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.weapon.GPSBombEntity;
import org.ywzj.vehicle.YwzjVehicle;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.resource.BedrockModelLoader;

import java.util.Optional;

public class GPSBombEntityRenderer extends EntityRenderer<GPSBombEntity> {

    public GPSBombEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(GPSBombEntity entity, Frustum camera, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public void render(GPSBombEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        {
            Vec3 root = new Vec3(0, 0, 0);
            poseStack.rotateAround(Axis.YP.rotationDegrees(-entityYaw), (float) root.x, (float) root.y, (float) root.z);
            poseStack.rotateAround(Axis.XP.rotationDegrees(Mth.lerp(partialTick, entity.xRotO, entity.getXRot())), (float) root.x, (float) root.y, (float) root.z);

            BedrockModel model = null;
            ResourceLocation texture = null;
            Optional<BaseDisplay> weaponDisplayOptional = ClientAssetsManager.INSTANCE.getWeaponDisplay(entity.getWeaponId());
            if (weaponDisplayOptional.isPresent()) {
                BaseDisplay weaponDisplay = weaponDisplayOptional.get();
                if (weaponDisplay.getModel() != null) {
                    model = weaponDisplay.getModel();
                }
                if (weaponDisplay.getTexture() != null) {
                    texture = weaponDisplay.getTexture();
                }
            }
            if (model == null) {
                model = BedrockModelLoader.getModel(YwzjVehicle.modLocation("entity/aerial_bomb"));
            }
            if (texture == null) {
                texture = YwzjVehicle.modLocation("textures/entity/aerial_bomb.png");
            }
            model.renderToBuffer(poseStack, bufferSource,
                    RenderType.entityCutout(texture),
                    BedrockModelRenderTypes.polyMeshCutout(texture),
                    packedLight,
                    OverlayTexture.pack(0f, false)
            );
        }
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(GPSBombEntity entity) {
        return null;
    }
}
