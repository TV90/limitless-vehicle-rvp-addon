package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_ClientMarkedBlockState;
import org.ywzj.rvp.client.state.RVP_ClientTacticalRevealState;
import org.ywzj.rvp.network.S2CMarkedBlockSync;
import org.ywzj.vehicle.client.render.util.GuiHelper;
import org.ywzj.vehicle.util.VectorUtil;

import java.util.List;
import java.util.Map;

/**
 * 目标指示吊舱标记渲染器：
 * <ul>
 *   <li>实体头顶倒三角（3D 世界空间）：红(敌对)/白(中立)/蓝(友方)</li>
 *   <li>方块标记红圈+玩家名（2D 屏幕空间，抄 GPS 炸弹 overlay 渲染）</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class RVP_TargetingPodMarkerRenderer {

    private static final int COLOR_FRIENDLY = 0xFF2B6CFF;
    private static final int COLOR_HOSTILE   = 0xFFFF2B2B;
    private static final int COLOR_NEUTRAL   = 0xFFFFFFFF;
    private static final int COLOR_BLOCK_RING = 0xFFFF2B2B;

    private RVP_TargetingPodMarkerRenderer() {}

    /** 实体头顶倒三角：3D 世界空间渲染 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        ResourceLocation dim = mc.level.dimension().location();
        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();

        renderEntityMarkers(mc, dim, poseStack, cameraPos);
    }

    /** 方块标记红圈+玩家名：2D 屏幕空间渲染（参考 GPS 炸弹 overlay） */
    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        // RenderGuiOverlayEvent.Post 每帧对每个已注册 overlay 各触发一次（40+ 次），
        // 只锚定每帧必渲染的原生 CHAT_PANEL 层执行一次，其余事件忽略（修复多弹/多实体时帧率腰斩）
        if (event.getOverlay().id() != VanillaGuiOverlay.CHAT_PANEL.id()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (mc.options.hideGui) {
            return;
        }
        ResourceLocation dim = mc.level.dimension().location();
        List<S2CMarkedBlockSync.MarkedBlockEntry> blocks =
                RVP_ClientMarkedBlockState.getMarkedBlocks(dim);
        if (blocks.isEmpty()) {
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();

        for (S2CMarkedBlockSync.MarkedBlockEntry block : blocks) {
            Vec3 worldPos = new Vec3(block.x(), block.y(), block.z());
            double dist = cameraPos.distanceTo(worldPos);
            if (dist > 1024.0) {
                continue;
            }
            Vec3 screenPos = VectorUtil.worldToScreen(worldPos);
            if (screenPos.z <= 0) {
                continue;
            }

            // 红圈（固定像素半径，参考 GPS 炸弹）
            PoseStack pose = gg.pose();
            pose.pushPose();
            pose.translate(screenPos.x, screenPos.y, 0);
            GuiHelper.drawCircle(pose, 0, 0, 6, COLOR_BLOCK_RING, 0.05f, 0f, 1f);
            pose.popPose();

            // 玩家名（红圈下方）
            String markerName = block.markerName();
            if (markerName != null && !markerName.isEmpty()) {
                int textWidth = mc.font.width(markerName);
                gg.drawString(mc.font, markerName,
                        (int) (screenPos.x - textWidth / 2f),
                        (int) (screenPos.y + 10),
                        0xFFFF4444, true);
            }
        }
    }

    private static void renderEntityMarkers(Minecraft mc, ResourceLocation dim,
                                            PoseStack poseStack, Vec3 cameraPos) {
        Map<Integer, Integer> iffMap = RVP_ClientTacticalRevealState.getMarkedEntityIffMap(dim);
        if (iffMap.isEmpty()) {
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableDepthTest();

        for (Map.Entry<Integer, Integer> entry : iffMap.entrySet()) {
            Entity entity = mc.level.getEntity(entry.getKey());
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            int iffType = entry.getValue();
            int color = iffToColor(iffType);

            Vec3 markerPos = new Vec3(
                    entity.getX(),
                    entity.getBoundingBox().maxY + 0.8,
                    entity.getZ()
            );
            double dist = cameraPos.distanceTo(markerPos);
            if (dist > 512.0) {
                continue;
            }
            float scale = Mth.clamp(0.04f * (48.0f / (float) Math.max(1.0, dist)), 0.02f, 0.15f);

            int a = (color >>> 24) & 0xFF;
            int r = (color >>> 16) & 0xFF;
            int g = (color >>> 8) & 0xFF;
            int b = color & 0xFF;

            poseStack.pushPose();
            poseStack.translate(markerPos.x - cameraPos.x, markerPos.y - cameraPos.y, markerPos.z - cameraPos.z);
            poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            poseStack.scale(scale, scale, scale);

            // 倒三角形
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            Matrix4f mat = poseStack.last().pose();
            buffer.vertex(mat, 0.0f, 0.8f, 0.0f).color(r, g, b, a).endVertex();
            buffer.vertex(mat, -0.7f, -0.5f, 0.0f).color(r, g, b, a).endVertex();
            buffer.vertex(mat, 0.7f, -0.5f, 0.0f).color(r, g, b, a).endVertex();
            BufferUploader.drawWithShader(buffer.end());

            poseStack.popPose();
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static int iffToColor(int iffType) {
        return switch (iffType) {
            case 0 -> COLOR_FRIENDLY;
            case 1 -> COLOR_HOSTILE;
            default -> COLOR_NEUTRAL;
        };
    }
}
