package org.ywzj.rvp.countermeasure.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_SmokeEntity;

/**
 * 烟雾云实体渲染：多层半透明白色 billboard（复用本体烟雾粒子贴图），按当前半径缩放，
 * 自发光呈现白色烟幕；本体同款的不透明大粒子负责主要体积遮蔽。
 */
public class RVP_SmokeRenderer extends EntityRenderer<RVP_SmokeEntity> {

    private static final ResourceLocation SMOKE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ywzj_vehicle", "textures/particle/smoke/smoke_8.png");

    public RVP_SmokeRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(RVP_SmokeEntity entity, Frustum camera,
                                double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public void render(RVP_SmokeEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        float radius = entity.getCurrentRadius();
        if (radius <= 0.1F) {
            return;
        }
        poseStack.pushPose();
        // 把 billboard 锚定到 AABB 中心（逻辑位置）：渲染器默认画在插值渲染位置上，
        // 弹道飞行停止后插值残留会把云拉向旧位置、与 AABB 禁视区不一致
        Vec3 aabbCenter = entity.getBoundingBox().getCenter();
        poseStack.translate(
                aabbCenter.x - Mth.lerp(partialTick, entity.xo, entity.getX()),
                aabbCenter.y - Mth.lerp(partialTick, entity.yo, entity.getY()),
                aabbCenter.z - Mth.lerp(partialTick, entity.zo, entity.getZ()));
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityTranslucent(SMOKE_TEXTURE));
        // 三层叠放模拟体积感（中央 / 上 / 下），白色自发光
        for (int layer = 0; layer < 3; layer++) {
            poseStack.pushPose();
            poseStack.translate(0.0, (layer - 1) * radius * 0.25F, 0.0);
            float s = radius * (1.0F - layer * 0.15F);
            poseStack.scale(s, s, s);
            float alpha = layer == 0 ? 0.45F : 0.32F;
            renderQuad(poseStack.last(), buffer, alpha);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private static void renderQuad(PoseStack.Pose pose, VertexConsumer buffer, float alpha) {
        buffer.vertex(pose.pose(), -0.5f, -0.5f, 0.0f).color(1f, 1f, 1f, alpha)
                .uv(0.0f, 1.0f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), 0, 0, 1).endVertex();
        buffer.vertex(pose.pose(), 0.5f, -0.5f, 0.0f).color(1f, 1f, 1f, alpha)
                .uv(1.0f, 1.0f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), 0, 0, 1).endVertex();
        buffer.vertex(pose.pose(), 0.5f, 0.5f, 0.0f).color(1f, 1f, 1f, alpha)
                .uv(1.0f, 0.0f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), 0, 0, 1).endVertex();
        buffer.vertex(pose.pose(), -0.5f, 0.5f, 0.0f).color(1f, 1f, 1f, alpha)
                .uv(0.0f, 0.0f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), 0, 0, 1).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(RVP_SmokeEntity entity) {
        return SMOKE_TEXTURE;
    }
}
