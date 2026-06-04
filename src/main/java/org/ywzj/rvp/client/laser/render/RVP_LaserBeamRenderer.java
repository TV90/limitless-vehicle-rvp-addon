package org.ywzj.rvp.client.laser.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.laser.RVP_ClientLaserBeamResolver;
import org.ywzj.rvp.client.laser.RVP_ClientLaserBeamResolver.ResolvedBeam;
import org.ywzj.rvp.client.laser.RVP_ClientLaserState;
import org.ywzj.rvp.client.laser.RVP_ClientLaserState.LaserBeamKey;
import org.ywzj.rvp.client.laser.RVP_LaserBeamSmoothing;

import java.util.Map;

/**
 * Draws active laser beams as stretched bedrock models (same path as machinegun tracers).
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_LaserBeamRenderer {

    private RVP_LaserBeamRenderer() {}

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        if (RVP_ClientLaserState.view().isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Entity viewEntity = mc.getCameraEntity();
        if (level == null || viewEntity == null) {
            return;
        }

        long worldTime = level.getGameTime();
        float partialTick = event.getPartialTick();
        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        int packedLight = LightTexture.FULL_BRIGHT;

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        for (Map.Entry<LaserBeamKey, RVP_ClientLaserState.ActiveLaser> entry : RVP_ClientLaserState.view().entrySet()) {
            ResolvedBeam resolved = RVP_ClientLaserBeamResolver.resolve(
                    level, entry.getKey(), entry.getValue(), partialTick);
            if (resolved == null) {
                continue;
            }
            var beam = RVP_LaserBeamSmoothing.forRender(entry.getKey(), resolved.beam(), partialTick);
            if (!beam.isDrawable()) {
                continue;
            }
            renderBeamModel(poseStack, bufferSource, packedLight, resolved, beam, worldTime, partialTick);
        }

        bufferSource.endBatch();
        poseStack.popPose();
    }

    private static void renderBeamModel(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                                        ResolvedBeam ctx, org.ywzj.rvp.weapon.laser.RVP_LaserBeam beam,
                                        long worldTime, float partialTick) {
        RVP_LaserDisplayHelper.ResolvedDisplay display = RVP_LaserDisplayHelper.resolve(ctx.data().getWeaponId());
        if (display == null) {
            return;
        }
        BedrockModel model = display.model();
        ResourceLocation texture = display.texture();

        Vec3 start = beam.renderStart();
        Vec3 end = beam.renderEnd();
        if (start.distanceToSqr(end) < 1.0E-4) {
            return;
        }

        float width = ctx.visual().getWidth();
        if (ctx.visual().isPulsate()) {
            width *= (float) (0.7 + 0.3 * Math.sin((worldTime + partialTick) * 0.4));
        }
        width = Mth.clamp(width, 0.05f, 0.5f);

        int argb = ctx.visual().toArgb(ctx.visual().getChargeRatio());
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        if (a <= 0.01f) {
            return;
        }

        poseStack.pushPose();
        RVP_LaserBeamPose.applyBeamTransform(poseStack, start, end, width);

        RenderType type = RenderType.energySwirl(texture, 15, 15);
        VertexConsumer builder = bufferSource.getBuffer(type);
        model.renderToBuffer(poseStack, builder, packedLight, OverlayTexture.NO_OVERLAY, r, g, b, a);
        poseStack.popPose();
    }
}
