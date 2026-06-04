package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.entity.projectile.RVP_BulletEntity;

/**
 * {@link org.ywzj.vehicle.client.render.entity.weapon.BulletEntityRenderer} for {@link RVP_BulletEntity}.
 */
public class RVP_BulletEntityRenderer extends EntityRenderer<RVP_BulletEntity> {

    public RVP_BulletEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(RVP_BulletEntity bullet, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        VehicleProjectileRenderLogic.renderBullet(bullet, partialTicks, poseStack, bufferSource, packedLight);
    }

    @Override
    protected int getBlockLightLevel(@NotNull RVP_BulletEntity entity, @NotNull BlockPos blockPos) {
        return 15;
    }

    @Override
    public boolean shouldRender(RVP_BulletEntity bullet, Frustum camera, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public ResourceLocation getTextureLocation(@NotNull RVP_BulletEntity entity) {
        return null;
    }
}
