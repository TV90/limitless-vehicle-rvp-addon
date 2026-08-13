package org.ywzj.rvp.countermeasure.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.countermeasure.RVP_DecoyEntity;
import org.ywzj.rvp.countermeasure.RVP_EnumCountermeasureType;
import org.ywzj.vehicle.YwzjVehicle;
import org.ywzj.vehicle.all.AllSounds;

/**
 * 干扰物实体渲染（billboard 贴图四方形，对齐本体 {@code DecoyFlareEntityRenderer}）。
 *
 * <p>热焰弹红光 + 大光圈、箔条白光 + 小光圈（由 {@code glowColor} / {@code haloScale} 决定）；
 * 始终面向相机、自发光（FULL_BRIGHT）、脉动缩放。首帧渲染时播放热焰弹拖尾音。</p>
 */
public class RVP_DecoyRenderer extends EntityRenderer<RVP_DecoyEntity> {

    public RVP_DecoyRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(RVP_DecoyEntity entity, Frustum camera, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public void render(RVP_DecoyEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (entity.tickCount <= 1 && entity.rvp$decoyType() == RVP_EnumCountermeasureType.FLARE) {
            playTailSound(entity);
        }
        poseStack.pushPose();
        poseStack.translate(0.0, 0.1, 0.0);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        float age = entity.tickCount + partialTick;
        float scale = entity.getHaloScale() * (0.9f + (float) Math.sin(age * 0.5f) * 0.1f);
        poseStack.scale(scale, scale, scale);
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityTranslucent(getTextureLocation(entity)));
        PoseStack.Pose pose = poseStack.last();
        int color = entity.getGlowColor();
        float r = (color >> 16 & 255) / 255f;
        float g = (color >> 8 & 255) / 255f;
        float b = (color & 255) / 255f;
        buffer.vertex(pose.pose(), -0.5f, -0.5f, 0.0f).color(r, g, b, 1.0f)
                .uv(0.0f, 1.0f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), 0, 0, 1).endVertex();
        buffer.vertex(pose.pose(), 0.5f, -0.5f, 0.0f).color(r, g, b, 1.0f)
                .uv(1.0f, 1.0f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), 0, 0, 1).endVertex();
        buffer.vertex(pose.pose(), 0.5f, 0.5f, 0.0f).color(r, g, b, 1.0f)
                .uv(1.0f, 0.0f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), 0, 0, 1).endVertex();
        buffer.vertex(pose.pose(), -0.5f, 0.5f, 0.0f).color(r, g, b, 1.0f)
                .uv(0.0f, 0.0f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), 0, 0, 1).endVertex();
        poseStack.popPose();
    }

    private void playTailSound(RVP_DecoyEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 pos = entity.position();
        // 本地播放热焰弹拖尾音（复用本体声音资源，客户端一次性播放）
        mc.level.playLocalSound(pos.x, pos.y, pos.z,
                AllSounds.DECOY_FLARE_TAIL.get(), SoundSource.NEUTRAL, 0.8F, 1.0F, false);
    }

    @Override
    public ResourceLocation getTextureLocation(RVP_DecoyEntity entity) {
        // 复用本体 decoy_flare 贴图（RVP 不新增贴图资源）
        return YwzjVehicle.modLocation("textures/entity/decoy_flare.png");
    }
}
