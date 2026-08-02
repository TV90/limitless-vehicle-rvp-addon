package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 线导导弹视觉线缆（原版钓鱼线风格黑色细线）。
 *
 * <p>服务端经 {@link RVP_BaseBullet#DATA_WIRE_ENABLED}/{@code DATA_WIRE_PIVOT_*}/{@code DATA_WIRE_ACTIVE}
 * 同步线缆状态：线从导弹当前位置连到发射武器站枢轴世界坐标。导弹失去制导
 * （{@code DATA_WIRE_ACTIVE=false}）时线立即消失；导弹实体消失（爆炸/销毁）后线缆
 * 保留最后位置并做 20 tick 渐隐后消失。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_WireLinkRenderer {

    private static final int FADE_TICK = 20;

    private static final Map<Integer, GhostWire> GHOSTS = new HashMap<>();

    private static Level lastLevel;
    private RVP_WireLinkRenderer() {}

    private record GhostWire(Vec3 pivot, Vec3 missile, int fadeTick) {}

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.getCameraEntity() == null) {
            return;
        }
        if (level != lastLevel) {
            // 切换世界/维度时清空残留渐隐线
            GHOSTS.clear();
            lastLevel = level;
        }

        float partialTick = event.getPartialTick();
        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        Set<Integer> present = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof RVP_BaseBullet bullet)) {
                continue;
            }
            if (!bullet.getEntityData().get(RVP_BaseBullet.DATA_WIRE_ENABLED)) {
                continue;
            }
            present.add(entity.getId());
            boolean active = bullet.getEntityData().get(RVP_BaseBullet.DATA_WIRE_ACTIVE);
            Vec3 pivot = new Vec3(
                    bullet.getEntityData().get(RVP_BaseBullet.DATA_WIRE_PIVOT_X),
                    bullet.getEntityData().get(RVP_BaseBullet.DATA_WIRE_PIVOT_Y),
                    bullet.getEntityData().get(RVP_BaseBullet.DATA_WIRE_PIVOT_Z));
            Vec3 missilePos = entity.getPosition(partialTick);
            if (active) {
                // 线缆激活：保存最新端点并重置渐隐计时
                GHOSTS.put(entity.getId(), new GhostWire(pivot, missilePos, FADE_TICK));
            } else {
                // 失去制导：线缆立即消失
                GHOSTS.remove(entity.getId());
            }
        }

        // 实体已消失（爆炸/销毁）：线缆保留最后端点并逐 tick 渐隐
        GHOSTS.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey())) {
                return false;
            }
            GhostWire ghost = entry.getValue();
            int fade = ghost.fadeTick() - 1;
            if (fade <= 0) {
                return true;
            }
            entry.setValue(new GhostWire(ghost.pivot(), ghost.missile(), fade));
            return false;
        });

        if (GHOSTS.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        for (Map.Entry<Integer, GhostWire> entry : GHOSTS.entrySet()) {
            GhostWire ghost = entry.getValue();
            float alpha = present.contains(entry.getKey())
                    ? 1.0f
                    : ghost.fadeTick() / (float) FADE_TICK;
            renderCurvedWire(level, consumer, matrix, ghost.pivot(), ghost.missile(), alpha);
        }
        bufferSource.endBatch();
        poseStack.popPose();
    }

    /** 曲线分段数（越高越平滑）。 */
    private static final int CURVE_SEGMENTS = 40;

    /** 线缆中段下垂量 = SAG_RATIO × 线长，钳制在 MIN_SAG ~ MAX_SAG（格）。 */
    private static final float SAG_RATIO = 0.05f;
    private static final float MIN_SAG = 0.25f;
    private static final float MAX_SAG = 3.0f;

    /** 微晃参数：横向摆动/垂向抖动的幅度（格）、沿线的波数、相位推进速度（弧度/秒）。 */
    private static final double SWAY_AMPLITUDE = 0.05;
    private static final double BOB_AMPLITUDE = 0.03;
    private static final double SWAY_WAVE_COUNT = 1.6;
    private static final double BOB_WAVE_COUNT = 1.1;
    private static final double TIME_SCALE = 20.0;

    /**
     * 以二次贝塞尔曲线绘制线缆：中段相对两端连线向下弯曲，模拟钓鱼线/线导线的重力垂坠，
     * 并叠加随时间变化的微小横向摆动与垂向抖动，避免僵硬。端点仍精确连接导弹与发射枢轴；
     * 曲线任意点不得低于地表上方 {@link #GROUND_MIN_Y_OFFSET} 格。
     */
    private static void renderCurvedWire(Level level, VertexConsumer consumer, Matrix4f matrix,
                                         Vec3 start, Vec3 end, float alpha) {
        float sag = (float) Mth.clamp(start.distanceTo(end) * SAG_RATIO, MIN_SAG, MAX_SAG);
        Vec3 control = start.add(end).scale(0.5).subtract(0, sag, 0);

        // 晃动坐标系：横向（垂直于弦且平行于地面）与垂向
        Vec3 chord = end.subtract(start);
        Vec3 side = chord.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1.0E-8) {
            side = new Vec3(1, 0, 0);
        }
        side = side.normalize();
        Vec3 vertical = chord.cross(side).normalize();

        double phase = System.currentTimeMillis() / 1000.0 * TIME_SCALE;
        Vec3 prev = clampToGround(level, start);
        for (int i = 1; i <= CURVE_SEGMENTS; i++) {
            float t = i / (float) CURVE_SEGMENTS;
            // 两端固定、中段摆动（envelope 0→1）
            double envelope = t * (1 - t) * 4;
            double sway = Math.sin(t * Math.PI * 2 * SWAY_WAVE_COUNT + phase) * SWAY_AMPLITUDE * envelope;
            double bob = Math.cos(t * Math.PI * 2 * BOB_WAVE_COUNT + phase * 0.6) * BOB_AMPLITUDE * envelope;
            Vec3 point = bezierPoint(start, control, end, t)
                    .add(side.scale(sway))
                    .add(vertical.scale(bob));
            point = clampToGround(level, point);
            consumer.vertex(matrix, (float) prev.x, (float) prev.y, (float) prev.z)
                    .color(0.0f, 0.0f, 0.0f, alpha)
                    .normal(0.0f, 0.0f, 1.0f)
                    .endVertex();
            consumer.vertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                    .color(0.0f, 0.0f, 0.0f, alpha)
                    .normal(0.0f, 0.0f, 1.0f)
                    .endVertex();
            prev = point;
        }
    }

    /** 线缆最低离地高度：地表上方 1 格。 */
    private static final double GROUND_MIN_Y_OFFSET = 1.0;

    private static Vec3 clampToGround(Level level, Vec3 pos) {
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(pos.x), Mth.floor(pos.z));
        double minY = groundY + GROUND_MIN_Y_OFFSET;
        if (pos.y < minY) {
            return new Vec3(pos.x, minY, pos.z);
        }
        return pos;
    }

    private static Vec3 bezierPoint(Vec3 p0, Vec3 p1, Vec3 p2, float t) {
        float u = 1.0f - t;
        return new Vec3(
                u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x,
                u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y,
                u * u * p0.z + 2 * u * t * p1.z + t * t * p2.z);
    }
}
