package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.BedrockModelRenderTypes;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;

/**
 * Renders data-driven RVP projectiles using weapon display bedrock models (or kind defaults).
 */
public class RVP_ProjectileEntityRenderer extends EntityRenderer<Projectile> {

    public RVP_ProjectileEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(Projectile entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        if (!(entity instanceof AmmoEntity ammo)) {
            return;
        }
        if (ammo instanceof RVP_BulletEntity bullet) {
            renderMachinegunTracer(bullet, partialTick, poseStack, bufferSource, packedLight);
            return;
        }
        renderBedrockProjectile(ammo, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    private void renderBedrockProjectile(AmmoEntity ammo, float entityYaw, float partialTick, PoseStack poseStack,
                                         MultiBufferSource bufferSource, int packedLight) {
        RVP_ProjectileDisplayHelper.ResolvedDisplay display = RVP_ProjectileDisplayHelper.resolve(ammo);
        if (display == null) {
            display = RVP_ProjectileDisplayHelper.fallbackMissile();
        }
        BedrockModel model = display.model();
        ResourceLocation texture = display.texture();
        if (model == null || texture == null) {
            return;
        }

        poseStack.pushPose();
        Vec3 root = Vec3.ZERO;
        poseStack.rotateAround(Axis.YP.rotationDegrees(-entityYaw),
                (float) root.x, (float) root.y, (float) root.z);
        poseStack.rotateAround(Axis.XP.rotationDegrees(Mth.lerp(partialTick, ammo.xRotO, ammo.getXRot())),
                (float) root.x, (float) root.y, (float) root.z);
        model.renderToBuffer(poseStack, bufferSource,
                RenderType.entityCutout(texture),
                BedrockModelRenderTypes.polyMeshCutout(texture),
                packedLight,
                OverlayTexture.pack(0f, false));
        poseStack.popPose();
    }

    /**
     * Matches {@link BulletEntityRenderer#renderTracerAmmo} transforms and texture (energySwirl tracer).
     */
    private void renderMachinegunTracer(RVP_BulletEntity bullet, float partialTicks, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight) {
        RVP_ProjectileDisplayHelper.ResolvedDisplay display = RVP_ProjectileDisplayHelper.resolve(bullet);
        if (display == null) {
            return;
        }
        BedrockModel model = display.model();
        ResourceLocation textureId = display.texture();
        if (model == null || textureId == null) {
            return;
        }

        boolean shotgun = RVP_ProjectileDisplayHelper.usesBuiltinShotgunCube(bullet);
        float width = Math.min((shotgun ? 0.032f : 0.028f) * bullet.getCaliber() / 7.62f, shotgun ? 0.14f : 0.16f);
        Vec3 bulletPosition = bullet.getPosition(partialTicks);
        double trailLength = 0.3 * bullet.getDeltaMovement().length();
        double disToEye = bulletPosition.distanceTo(bullet.getStartPos());
        trailLength = Math.min(trailLength, disToEye * 0.8);

        // 与 {@link org.ywzj.vehicle.client.render.entity.weapon.BulletEntityRenderer} 一致：用实体插值朝向
        float renderYaw = Mth.lerp(partialTicks, bullet.yRotO, bullet.getYRot()) - 180.0F;
        float renderPitch = Mth.lerp(partialTicks, bullet.xRotO, bullet.getXRot());

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(renderYaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(renderPitch));
        if (shotgun) {
            poseStack.scale(width, width, width);
        } else {
            poseStack.translate(0, 0, trailLength / 2.0);
            poseStack.scale(width, width, (float) trailLength);
        }

        double bulletDistance = bulletPosition.distanceTo(bullet.getStartPos());
        if (bulletDistance > 2) {
            RenderType type = RenderType.energySwirl(textureId, 15, 15);
            VertexConsumer builder = bufferSource.getBuffer(type);
            model.renderToBuffer(poseStack, builder, packedLight, OverlayTexture.NO_OVERLAY,
                    bullet.getTracerR(), bullet.getTracerG(), bullet.getTracerB(), 1f);
        }
        poseStack.popPose();
    }

    @Override
    public boolean shouldRender(@NotNull Projectile entity, Frustum camera, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    protected int getBlockLightLevel(@NotNull Projectile entity, @NotNull BlockPos blockPos) {
        return 15;
    }

    @Override
    public ResourceLocation getTextureLocation(Projectile entity) {
        return null;
    }
}
