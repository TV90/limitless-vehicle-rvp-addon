package org.ywzj.rvp.client.render.remotevisibility;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleProjection.ProjectionPlan;

import java.util.List;
import java.util.Map;

/**
 * 一帧远距载具的不可变绘制计划。
 *
 * <p>候选选择与最终目标缓冲解耦后，原版通道、DH 离屏通道和显式降级通道可复用同一结果。</p>
 */
public final class RVP_RemoteVehicleFramePlan {
    /** 建立计划时的客户端世界。 */
    private final ClientLevel level;
    /** 经过总量与高模预算筛选的候选。 */
    private final List<RVP_RemoteVehicleRenderBudget.Candidate> selected;
    /** 实体 ID 到完整渲染上下文的映射。 */
    private final Map<Integer, RVP_RemoteVehicleVisualRenderer.CandidateContext> contexts;
    /** 建立计划时的相机世界坐标。 */
    private final Vec3 cameraPosition;
    /** 建立计划时的相机朝向。 */
    private final Quaternionf cameraOrientation;
    /** 建立计划时世界渲染 PoseStack 的独立副本。 */
    private final PoseStack poseStack;
    /** 本帧动态远裁剪投影；null 表示当前投影无法安全解析。 */
    private final ProjectionPlan projectionPlan;
    /** 本帧局部 tick 插值。 */
    private final float partialTick;

    RVP_RemoteVehicleFramePlan(ClientLevel level,
                               List<RVP_RemoteVehicleRenderBudget.Candidate> selected,
                               Map<Integer, RVP_RemoteVehicleVisualRenderer.CandidateContext> contexts,
                               Vec3 cameraPosition,
                               Quaternionf cameraOrientation,
                               PoseStack poseStack,
                               ProjectionPlan projectionPlan,
                               float partialTick) {
        this.level = level;
        this.selected = List.copyOf(selected);
        this.contexts = Map.copyOf(contexts);
        this.cameraPosition = cameraPosition;
        this.cameraOrientation = new Quaternionf(cameraOrientation);
        this.poseStack = poseStack;
        this.projectionPlan = projectionPlan;
        this.partialTick = partialTick;
    }

    /** 返回建立计划时的客户端世界。 */
    ClientLevel level() {
        return level;
    }

    /** 返回预算筛选后的不可变候选列表。 */
    List<RVP_RemoteVehicleRenderBudget.Candidate> selected() {
        return selected;
    }

    /** 返回候选渲染上下文映射。 */
    Map<Integer, RVP_RemoteVehicleVisualRenderer.CandidateContext> contexts() {
        return contexts;
    }

    /** 返回相机世界坐标。 */
    Vec3 cameraPosition() {
        return cameraPosition;
    }

    /** 返回相机朝向副本。 */
    Quaternionf cameraOrientation() {
        return new Quaternionf(cameraOrientation);
    }

    /** 返回只由本计划拥有的世界姿态栈。 */
    PoseStack poseStack() {
        return poseStack;
    }

    /** 返回动态远裁剪投影计划；可能为 null。 */
    public ProjectionPlan projectionPlan() {
        return projectionPlan;
    }

    /** 返回本帧局部 tick 插值。 */
    public float partialTick() {
        return partialTick;
    }

    /** 返回预算筛选后的目标数量。 */
    public int selectedCount() {
        return selected.size();
    }
}
