package org.ywzj.rvp.client.compat.distanthorizons.realvehicleprotect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;

/** 把真实载具主体的只读几何副本提交到当前 DH 保护离屏目标。 */
public final class RVP_DhTrackedVehicleProtectionRenderer {
    /** 保护层独立批次的初始缓冲容量，避免提交其它世界渲染器遗留顶点。 */
    private static final int BUFFER_INITIAL_CAPACITY = 256;
    /** 真实载具保护层专用缓冲。 */
    private static final MultiBufferSource.BufferSource BUFFERS =
            MultiBufferSource.immediate(new BufferBuilder(BUFFER_INITIAL_CAPACITY));

    private RVP_DhTrackedVehicleProtectionRenderer() {
    }

    /** 使用计划保存的 Minecraft 投影绘制主体，并按脱落状态隐藏对应骨骼。 */
    public static void renderPrepared(RVP_DhTrackedVehicleFramePlan plan) {
        if (plan == null) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        // RenderSystem 的 backupProjectionMatrix 只有一个全局槽，DH 回调内不可嵌套占用；改为保存独立副本。
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        try {
            RenderSystem.setProjectionMatrix(plan.projection(), VertexSorting.DISTANCE_TO_ORIGIN);
            PoseStack poseStack = plan.poseStack();
            for (RVP_DhTrackedVehicleFramePlan.Candidate candidate : plan.selected()) {
                renderVehicle(candidate, plan, poseStack);
            }
        } finally {
            try {
                // 调用隔离缓冲提交入口，在恢复投影和 FBO 之前完成全部保护几何绘制。
                BUFFERS.endBatch();
            } finally {
                // 调用 RenderSystem 显式恢复进入保护层前的投影，避免覆盖 DH/世界渲染器的全局备份槽。
                RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            }
        }
    }

    /** 只绘制不会推进动画、粒子、声音或 lastRenderTime 的模型副本。 */
    private static void renderVehicle(RVP_DhTrackedVehicleFramePlan.Candidate candidate,
                                      RVP_DhTrackedVehicleFramePlan plan,
                                      PoseStack poseStack) {
        poseStack.pushPose();
        try {
            Vec3Offset offset = Vec3Offset.between(candidate.position(), plan.cameraPosition());
            poseStack.translate(offset.x(), offset.y(), offset.z());
            // 调用本体公开旋转辅助，使保护层与正常载具保持同一 Y-X-Z 枢轴旋转顺序。
            VehicleRender.applyVehicleRotation(candidate.vehicle(), plan.partialTick(), poseStack);
            // 调用本体脱落部件辅助，但只修改保护层独立实例的骨骼可见性。
            VehicleRender.applyDetachedPart(candidate.vehicle(), candidate.modelInstance());
            candidate.model().renderToBuffer(candidate.modelInstance(), poseStack, BUFFERS,
                    candidate.texture(), candidate.packedLight());
        } finally {
            poseStack.popPose();
        }
    }

    /** 相机相对坐标的轻量不可变表示。 */
    private record Vec3Offset(double x, double y, double z) {
        /** 计算世界位置相对相机的平移量。 */
        private static Vec3Offset between(net.minecraft.world.phys.Vec3 position,
                                          net.minecraft.world.phys.Vec3 cameraPosition) {
            return new Vec3Offset(position.x - cameraPosition.x,
                    position.y - cameraPosition.y,
                    position.z - cameraPosition.z);
        }
    }
}
