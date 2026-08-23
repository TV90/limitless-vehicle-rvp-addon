package org.ywzj.rvp.client.laser.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.state.RVP_DircmHudState;

/**
 * DIRCM 激光照射光束渲染（从照射骨骼到目标弹体的连线，红/橙脉动）。
 *
 * <p>读取客户端 {@link RVP_DircmHudState}（服务端 {@code S2CDircmHudSync} 推送的通道状态），
 * 起点 = 该通道对应照射武器部件（{@code laserPart}）的骨骼世界位置，终点 = 目标弹体位置；
 * 照射期间持续跟踪（每 tick 重新取终点），光束宽度/透明度随时间脉动。
 * 纯客户端渲染，无服务端影响。</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_DircmBeamRenderer {

    private RVP_DircmBeamRenderer() {
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }
        Vec3 cameraPos = event.getCamera().getPosition();
        long worldTime = level.getGameTime();

        // 遍历所有有 DIRCM 通道状态的载具（不仅玩家自己驾驶的车）：
        // DIRCM 常由「敌方载具照射玩家发射的导弹」，须从旁观视角渲染光束
        for (int vehicleId : RVP_DircmHudState.vehicleIds()) {
            RVP_DircmHudState.Snapshot state = RVP_DircmHudState.get(vehicleId);
            if (state == null || state.channels() == null) {
                continue;
            }
            Entity vehicle = level.getEntity(vehicleId);
            if (!(vehicle instanceof org.ywzj.vehicle.entity.vehicle.AbstractVehicle rvpVehicle)) {
                continue;
            }
            for (RVP_DircmHudState.ChannelSnapshot channel : state.channels()) {
                if (!channel.irradiating()) {
                    continue;
                }
                Vec3 start = resolveLaserPartPos(rvpVehicle, channel.boneName());
                Entity target = level.getEntity(channel.targetId());
                if (start == null || !(target != null && target.isAlive())) {
                    continue;
                }
                Vec3 end = target.getBoundingBox().getCenter();
                if (start.distanceToSqr(end) < 1.0E-4) {
                    continue;
                }
                drawBeam(event, start.subtract(cameraPos), end.subtract(cameraPos), worldTime);
            }
        }
    }

    /** 解析照射武器部件世界位置：取部件结构骨的世界枢轴点（随载具移动，OBB 自动解算）。 */
    private static Vec3 resolveLaserPartPos(org.ywzj.vehicle.entity.vehicle.AbstractVehicle vehicle, String partId) {
        if (vehicle == null) {
            return null;
        }
        var opt = vehicle.getPartUnit(partId);
        if (opt.isEmpty()) {
            return null;
        }
        // 注意：worldVec() 返回的是瞄准方向单位向量，不是位置；
        // 光束起点必须用结构骨枢轴世界坐标 worldPivotPosition()（随载具移动）。
        return opt.get().worldPivotPosition();
    }

    /** 沿光束方向画一条 3D 薄四边形（红/橙，随 tick 脉动）。 */
    private static void drawBeam(RenderLevelStageEvent event, Vec3 startRel, Vec3 endRel, long worldTime) {
        Vec3 delta = endRel.subtract(startRel);
        double length = delta.length();
        if (length < 1.0E-4) {
            return;
        }
        float pulse = (float) (0.75 + 0.25 * Math.sin((worldTime + Minecraft.getInstance().getFrameTime()) * 0.35));
        float halfWidth = 0.18f * pulse;
        int alpha = (int) (220 * pulse);

        Vec3 dir = delta.normalize();
        // 找一个与光束垂直的横向单位向量
        Vec3 up = Math.abs(dir.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 side = up.cross(dir).normalize().scale(halfWidth);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f mat = event.getPoseStack().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        Vec3 a = startRel.subtract(side);
        Vec3 b = startRel.add(side);
        Vec3 c = endRel.add(side);
        Vec3 d = endRel.subtract(side);
        float rc = 1.0f, gc = 0.35f, bc = 0.1f;
        float af = alpha / 255.0f;
        bb.vertex(mat, (float) a.x, (float) a.y, (float) a.z).color(rc, gc, bc, af).endVertex();
        bb.vertex(mat, (float) b.x, (float) b.y, (float) b.z).color(rc, gc, bc, af).endVertex();
        bb.vertex(mat, (float) c.x, (float) c.y, (float) c.z).color(rc, gc, bc, af).endVertex();
        bb.vertex(mat, (float) d.x, (float) d.y, (float) d.z).color(rc, gc, bc, af).endVertex();

        Tesselator.getInstance().end();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}