package org.ywzj.rvp.client.laser.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.mojang.blaze3d.vertex.BufferBuilder;
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
import org.ywzj.rvp.client.light.RVP_LightingCompatibility;
import org.ywzj.rvp.weapon.laser.RVP_LaserBeam;

import java.util.Map;
import java.util.UUID;

/**
 * 在世界空间绘制自发光光芯及半透明光晕，使用命中判定给出的同一射段。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_LaserBeamRenderer {

    /** 复用原版信标渲染层，光影可处理其自发光、透明度和深度测试。 */
    private static final RenderType BEAM = RenderType.beaconBeam(
            RVP_MOD.modLocation("textures/white/white.png"), true);
    /** 光束自己的缓冲，避免提前提交其他实体的批次。 */
    private static final MultiBufferSource.BufferSource BEAM_BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(4096));
    /** 光芯用八边柱，斜视时仍保有宽度。 */
    private static final int CORE_SIDES = 8;

    private RVP_LaserBeamRenderer() {}

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        if (RVP_ClientLaserState.view().isEmpty() || RVP_LightingCompatibility.shadowPass()) {
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
            // 光束灯光归属发射载具：与干扰机玻片灯同归属，整车清灯已按类型分离。
            Entity ownerEntity = level.getEntity(entry.getKey().vehicleId());
            UUID owner = ownerEntity == null ? null : ownerEntity.getUUID();
            renderBeam(poseStack, cameraPos, resolved, beam, entry.getKey(), owner, worldTime, partialTick);
        }

        BEAM_BUFFERS.endBatch();
        poseStack.popPose();
    }

    private static void renderBeam(PoseStack poseStack, Vec3 camera, ResolvedBeam ctx, RVP_LaserBeam beam,
                                   LaserBeamKey beamKey, UUID owner, long worldTime, float partialTick) {
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
        // 沿用屏幕灯光的炮口光和命中光，真实落点独立于光芯表面留缝。
        RVP_LaserBeamLights.updateLaser(beamKey, owner, start, beam.impactPoint(), r, g, b, a);

        Vec3 axis = beam.beamDirection();
        Vec3 reference = Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 side = reference.cross(axis).normalize();
        Vec3 across = axis.cross(side);

        // 保留载具包明确指定的光束模型，默认光芯不再复用曳光弹模型。
        RVP_LaserDisplayHelper.ResolvedDisplay display = RVP_LaserDisplayHelper.resolve(ctx.data().getWeaponId());
        if (display != null) {
            BedrockModel model = display.model();
            ResourceLocation texture = display.texture();
            poseStack.pushPose();
            RVP_LaserBeamPose.applyBeamTransform(poseStack, start, end, width);
            model.renderToBuffer(poseStack, BEAM_BUFFERS.getBuffer(RenderType.entityTranslucentEmissive(texture)),
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, r, g, b, a);
            poseStack.popPose();
        }

        VertexConsumer vertices = BEAM_BUFFERS.getBuffer(BEAM);
        PoseStack.Pose pose = poseStack.last();
        // 外晕面向相机，顶点透明度由中心向外渐隐。
        Vec3 haloSide = axis.cross(camera.subtract(start)).normalize();
        if (haloSide.lengthSqr() > 0.0) {
            Vec3 haloNormal = haloSide.cross(axis);
            Vec3 outer = haloSide.scale(width * 0.85);
            Vec3 inner = haloSide.scale(width * 0.25);
            drawStrip(vertices, pose, start, end, outer.reverse(), inner.reverse(), haloNormal, r, g, b, 0f, a * 0.25f);
            drawStrip(vertices, pose, start, end, inner.reverse(), inner, haloNormal, r, g, b, a * 0.25f, a * 0.25f);
            drawStrip(vertices, pose, start, end, inner, outer, haloNormal, r, g, b, a * 0.25f, 0f);
        }
        if (display == null) {
            double radius = width * 0.13;
            for (int i = 0; i < CORE_SIDES; i++) {
                double lo = i * (Math.PI * 2.0 / CORE_SIDES);
                double hi = (i + 1) * (Math.PI * 2.0 / CORE_SIDES);
                Vec3 from = side.scale(Math.cos(lo) * radius).add(across.scale(Math.sin(lo) * radius));
                Vec3 to = side.scale(Math.cos(hi) * radius).add(across.scale(Math.sin(hi) * radius));
                drawStrip(vertices, pose, start, end, from, to, from.add(to).normalize(),
                        Mth.lerp(0.65f, r, 1f), Mth.lerp(0.65f, g, 1f), Mth.lerp(0.65f, b, 1f), a, a);
            }
        }
    }

    /** 用信标的顶点格式提交一段光带，两个边缘各自带透明度。 */
    private static void drawStrip(VertexConsumer vertices, PoseStack.Pose pose, Vec3 start, Vec3 end,
                                  Vec3 from, Vec3 to, Vec3 normal, float r, float g, float b, float aFrom, float aTo) {
        vertex(vertices, pose, start.add(from), normal, r, g, b, aFrom);
        vertex(vertices, pose, start.add(to), normal, r, g, b, aTo);
        vertex(vertices, pose, end.add(to), normal, r, g, b, aTo);
        vertex(vertices, pose, end.add(from), normal, r, g, b, aFrom);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 point, Vec3 normal,
                               float r, float g, float b, float a) {
        consumer.vertex(pose.pose(), (float) point.x, (float) point.y, (float) point.z)
                .color(r, g, b, a).uv(0.5f, 0.5f).uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.normal(), (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
    }
}
