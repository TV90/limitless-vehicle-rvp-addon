package org.ywzj.rvp.client.compat.distanthorizons.realvehicleprotect;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.List;

/** 一帧客户端真实载具 DH 保护层的不可变绘制计划。 */
public final class RVP_DhTrackedVehicleFramePlan {
    /** 建立计划时的客户端世界。 */
    private final ClientLevel level;
    /** 经过视锥与稳定预算筛选的真实载具。 */
    private final List<Candidate> selected;
    /** 建立计划时客户端已加载且满足基础条件的真实载具数。 */
    private final int loadedCount;
    /** 建立计划时的相机世界坐标。 */
    private final Vec3 cameraPosition;
    /** 世界渲染姿态栈的独立副本。 */
    private final PoseStack poseStack;
    /** 建立计划时的 Minecraft 投影矩阵。 */
    private final Matrix4f projection;
    /** 本帧局部 tick 插值。 */
    private final float partialTick;

    public RVP_DhTrackedVehicleFramePlan(ClientLevel level,
                                         List<Candidate> selected,
                                         int loadedCount,
                                         Vec3 cameraPosition,
                                         PoseStack poseStack,
                                         Matrix4f projection,
                                         float partialTick) {
        this.level = level;
        this.selected = List.copyOf(selected);
        this.loadedCount = loadedCount;
        this.cameraPosition = cameraPosition;
        this.poseStack = poseStack;
        this.projection = new Matrix4f(projection);
        this.partialTick = partialTick;
    }

    /** 返回建立计划时的客户端世界。 */
    public ClientLevel level() {
        return level;
    }

    /** 返回预算筛选后的真实载具。 */
    public List<Candidate> selected() {
        return selected;
    }

    /** 返回基础条件有效的已加载真实载具数。 */
    public int loadedCount() {
        return loadedCount;
    }

    /** 返回最终进入保护层的真实载具数。 */
    public int selectedCount() {
        return selected.size();
    }

    /** 返回建立计划时的相机世界坐标。 */
    public Vec3 cameraPosition() {
        return cameraPosition;
    }

    /** 返回只由本计划拥有的世界姿态栈。 */
    public PoseStack poseStack() {
        return poseStack;
    }

    /** 返回 Minecraft 投影矩阵的副本。 */
    public Matrix4f projection() {
        return new Matrix4f(projection);
    }

    /** 返回本帧局部 tick 插值。 */
    public float partialTick() {
        return partialTick;
    }

    /** 一辆真实载具的冻结位置、模型副本和光照上下文。 */
    public record Candidate(AbstractVehicle vehicle,
                            Vec3 position,
                            VehicleBedrockModel model,
                            BakedModelInstance modelInstance,
                            ResourceLocation texture,
                            int packedLight,
                            double distanceSquared,
                            double screenContribution) {
    }
}
