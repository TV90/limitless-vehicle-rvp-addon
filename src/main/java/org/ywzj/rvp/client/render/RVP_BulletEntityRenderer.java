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
        if (bullet.isParticleProjectileVisual()) {
            return;
        }
        // 观瞄视角射弹原点分离：伪装期把弹体/曳光平移渲染为"从炮口射出"的平行弹道并平滑合流
        RVP_SightFireDisguiseRender.applyDisguiseTranslate(bullet, poseStack, partialTicks);
        // 调用本项目通用 Bullet 绘制逻辑：非粒子模式继续绘制既有曳光弹体。
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
