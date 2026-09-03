package org.ywzj.rvp.client.compat.distanthorizons.realvehicleprotect;

import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 为真实载具 DH 保护层维护与正常实体模型完全隔离的姿态副本。 */
public final class RVP_DhTrackedVehicleModelCache {
    /** 实体 ID 到保护层模型实例的缓存。 */
    private static final Map<Integer, CacheEntry> ENTRIES = new HashMap<>();
    /** 当前准备帧实际访问过的实体 ID，用于帧末淘汰旧引用。 */
    private static final Set<Integer> TOUCHED_ENTITY_IDS = new HashSet<>();

    private RVP_DhTrackedVehicleModelCache() {
    }

    /** 开始一次候选收集，重置本帧访问标记。 */
    public static void beginFrame() {
        TOUCHED_ENTITY_IDS.clear();
    }

    /**
     * 取得保护层独立实例，并从正常实体实例复制当前已提交的姿态与骨骼可见性。
     *
     * @return display、模型或源实例不可用时返回 {@code null}
     */
    public static BakedModelInstance snapshot(AbstractVehicle vehicle,
                                              VehicleBedrockModel model,
                                              ResourceLocation displayId) {
        if (vehicle == null || model == null || displayId == null || !model.hasBakedModel()) {
            return null;
        }
        BakedModelInstance source = vehicle.getVehicleModelInstance();
        if (source == null) {
            return null;
        }
        int entityId = vehicle.getId();
        CacheEntry entry = ENTRIES.get(entityId);
        if (entry == null || entry.model() != model || !entry.displayId().equals(displayId)) {
            // 调用本体模型实例工厂，为保护层创建不共享姿态与可见性状态的独立实例。
            entry = new CacheEntry(displayId, model, model.createBakedInstance());
            ENTRIES.put(entityId, entry);
        }
        BakedModelInstance target = entry.instance();
        // 调用模型运行时姿态 API，只复制正常渲染器已经提交的骨骼姿态，不推进动画状态机。
        target.applyPose(source.getPose());
        copyBoneFlags(source, target);
        TOUCHED_ENTITY_IDS.add(entityId);
        return target;
    }

    /** 淘汰本帧未出现的实体，防止实体移除、换维度或 display 切换后保留旧实例。 */
    public static void endFrame() {
        ENTRIES.keySet().removeIf(entityId -> !TOUCHED_ENTITY_IDS.contains(entityId));
        TOUCHED_ENTITY_IDS.clear();
    }

    /** 资源重载、换维度或退出世界时释放所有模型实例引用。 */
    public static void clear() {
        ENTRIES.clear();
        TOUCHED_ENTITY_IDS.clear();
    }

    /** 同步可见性与自发光标记，保留正常实体通道已经决定的隐藏骨骼状态。 */
    private static void copyBoneFlags(BakedModelInstance source, BakedModelInstance target) {
        BoneState[] sourceBones = source.getBoneIndexes();
        BoneState[] targetBones = target.getBoneIndexes();
        int count = Math.min(sourceBones.length, targetBones.length);
        for (int index = 0; index < count; index++) {
            targetBones[index].visible = sourceBones[index].visible;
            targetBones[index].illuminated = sourceBones[index].illuminated;
        }
    }

    /** 一辆实体对应的 display、模型身份与保护层实例。 */
    private record CacheEntry(ResourceLocation displayId,
                              VehicleBedrockModel model,
                              BakedModelInstance instance) {
    }
}
