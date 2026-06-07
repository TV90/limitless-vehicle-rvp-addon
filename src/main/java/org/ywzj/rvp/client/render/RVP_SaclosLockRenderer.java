package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientSaclosState;

/**
 * World-space SACLOS lock marker at pod aim point (single green square).
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_SaclosLockRenderer {

    private RVP_SaclosLockRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        if (!RVP_ClientSaclosState.isGuiding() || !RVP_ClientSaclosState.isLaserEnabled()) {
            return;
        }
        Vec3 lockPos = RVP_ClientSaclosState.getLaserHudPos();
        if (lockPos == null) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        double distance = camera.distanceTo(lockPos);
        if (distance > 1000.0D) {
            return;
        }

        float minDistance = 50.0F;
        float size1 = 0.35F;
        float maxDistance = 300.0F;
        float maxSize = 1.75F;
        float size = size1 + (float) ((distance - minDistance) / (maxDistance - minDistance)) * (maxSize - size1);
        size = Mth.clamp(size, size1, maxSize);

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(
                lockPos.x - camera.x,
                lockPos.y - camera.y,
                lockPos.z - camera.z
        );
        poseStack.mulPose(event.getCamera().rotation());
        poseStack.scale(size, size, size);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        var pose = poseStack.last().pose();
        int r = 48;
        int g = 255;
        int b = 48;
        int a = 255;
        buffer.vertex(pose, -1.0F, -1.0F, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(pose, -1.0F, 1.0F, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(pose, 1.0F, 1.0F, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(pose, 1.0F, -1.0F, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(pose, -1.0F, -1.0F, 0.0F).color(r, g, b, a).endVertex();
        BufferUploader.drawWithShader(buffer.end());

        poseStack.popPose();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
