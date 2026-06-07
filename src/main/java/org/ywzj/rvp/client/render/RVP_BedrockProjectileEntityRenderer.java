package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;

/**
 * Bedrock projectile draw for RVP {@link AmmoEntity} subclasses; fallback IDs match
 * {@link org.ywzj.vehicle.client.render.entity.weapon.MissileEntityRenderer} /
 * {@link org.ywzj.vehicle.client.render.entity.weapon.RocketEntityRenderer} /
 * {@link org.ywzj.vehicle.client.render.entity.weapon.AerialBombEntityRenderer}.
 */
public class RVP_BedrockProjectileEntityRenderer<T extends AmmoEntity> extends EntityRenderer<T> {

    private final ResourceLocation fallbackModel;
    private final ResourceLocation fallbackTexture;

    public RVP_BedrockProjectileEntityRenderer(EntityRendererProvider.Context context,
                                               ResourceLocation fallbackModel,
                                               ResourceLocation fallbackTexture) {
        super(context);
        this.fallbackModel = fallbackModel;
        this.fallbackTexture = fallbackTexture;
    }

    @Override
    public void render(T ammo, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        VehicleProjectileRenderLogic.renderBedrockProjectile(
                ammo, entityYaw, partialTick, poseStack, bufferSource, packedLight, fallbackModel, fallbackTexture);
    }

    @Override
    public boolean shouldRender(T entity, Frustum camera, double camX, double camY, double camZ) {
        if (entity instanceof RVP_MissileEntity && RVP_ClientHitlState.shouldHideActiveMissileVfx(entity)) {
            return false;
        }
        return true;
    }

    @Override
    protected int getBlockLightLevel(@NotNull T entity, @NotNull BlockPos blockPos) {
        return 15;
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return null;
    }
}
