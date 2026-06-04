package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.vehicle.client.render.entity.weapon.BulletEntityRenderer;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.resource.BedrockModelLoader;

import java.util.Optional;

/**
 * Same draw rules as {@link org.ywzj.vehicle.client.render.entity.weapon} projectile renderers,
 * for {@link AmmoEntity} / {@link RVP_BulletEntity} (no cast to {@link org.ywzj.vehicle.entity.weapon.BulletEntity}).
 */
final class VehicleProjectileRenderLogic {

    private VehicleProjectileRenderLogic() {}

    static void renderBullet(RVP_BulletEntity bullet, float partialTicks, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight) {
        BedrockModel model = BedrockModelLoader.getModel(BulletEntityRenderer.DEFAULT_BULLET_MODEL);
        poseStack.pushPose();
        float width = Math.min(0.04f * bullet.getCaliber() / 7.62f, 0.2f);
        Vec3 bulletPosition = bullet.getPosition(partialTicks);
        double trailLength = 0.3 * bullet.getDeltaMovement().length();
        double disToEye = bulletPosition.distanceTo(bullet.getStartPos());
        trailLength = Math.min(trailLength, disToEye * 0.8);
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, bullet.yRotO, bullet.getYRot()) - 180.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partialTicks, bullet.xRotO, bullet.getXRot())));
        poseStack.translate(0, 0, trailLength / 2.0);
        poseStack.scale(width, width, (float) trailLength);
        if (disToEye > 1.0) {
            RenderType type = RenderType.energySwirl(BulletEntityRenderer.DEFAULT_BULLET_TEXTURE, 15, 15);
            VertexConsumer builder = bufferSource.getBuffer(type);
            model.renderToBuffer(poseStack, builder, packedLight, OverlayTexture.NO_OVERLAY,
                    bullet.getTracerR(), bullet.getTracerG(), bullet.getTracerB(), 1f);
        }
        poseStack.popPose();
    }

    static void renderBedrockProjectile(AmmoEntity ammo, float entityYaw, float partialTick, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight,
                                        ResourceLocation fallbackModel, ResourceLocation fallbackTexture) {
        poseStack.pushPose();
        Vec3 root = Vec3.ZERO;
        poseStack.rotateAround(Axis.YP.rotationDegrees(-entityYaw),
                (float) root.x, (float) root.y, (float) root.z);
        poseStack.rotateAround(Axis.XP.rotationDegrees(Mth.lerp(partialTick, ammo.xRotO, ammo.getXRot())),
                (float) root.x, (float) root.y, (float) root.z);

        BedrockModel model = null;
        ResourceLocation texture = null;
        ResourceLocation weaponId = ammo.getWeaponId();
        if (weaponId != null) {
            Optional<BaseDisplay> weaponDisplayOptional = ClientAssetsManager.INSTANCE.getWeaponDisplay(weaponId);
            if (weaponDisplayOptional.isPresent()) {
                BaseDisplay weaponDisplay = weaponDisplayOptional.get();
                if (weaponDisplay.getModel() != null) {
                    model = weaponDisplay.getModel();
                }
                if (weaponDisplay.getTexture() != null) {
                    texture = weaponDisplay.getTexture();
                }
            }
        }
        if (model == null) {
            model = BedrockModelLoader.getModel(fallbackModel);
        }
        if (texture == null) {
            texture = fallbackTexture;
        }
        model.renderToBuffer(poseStack, bufferSource,
                RenderType.entityCutout(texture),
                BedrockModelRenderTypes.polyMeshCutout(texture),
                packedLight,
                OverlayTexture.pack(0f, false));
        poseStack.popPose();
    }
}
