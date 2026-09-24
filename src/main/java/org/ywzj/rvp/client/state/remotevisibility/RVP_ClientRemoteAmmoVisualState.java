package org.ywzj.rvp.client.state.remotevisibility;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 客户端弹药超视距视觉完整集合状态。 */
public final class RVP_ClientRemoteAmmoVisualState {
    /** 当前完整集合所属维度。 */
    private static ResourceLocation dimension;
    /** 当前允许扩展绘制的弹药实体 ID。 */
    private static Set<Integer> entityIds = Set.of();
    /** 当前发动机仍在燃烧的弹药实体 ID 到剩余燃烧 Tick 的服务端权威映射。 */
    private static Map<Integer, Integer> motorBurnRemainingTicksByEntityId = Map.of();

    private RVP_ClientRemoteAmmoVisualState() {
    }

    /** 使用最新服务端完整集合替换客户端弹药视觉状态。 */
    public static void replace(ResourceLocation newDimension, Set<Integer> newEntityIds,
                               Map<Integer, Integer> newMotorBurnRemainingTicksByEntityId) {
        dimension = newDimension;
        entityIds = newEntityIds == null ? Set.of() : Set.copyOf(new HashSet<>(newEntityIds));
        motorBurnRemainingTicksByEntityId = newMotorBurnRemainingTicksByEntityId == null
                ? Map.of()
                : Map.copyOf(new HashMap<>(newMotorBurnRemainingTicksByEntityId));
    }

    /** 判断指定维度和实体 ID 是否在服务端授权集合内。 */
    public static boolean contains(ResourceLocation currentDimension, int entityId) {
        return currentDimension != null && currentDimension.equals(dimension) && entityIds.contains(entityId);
    }

    /** 判断指定弹药实体的发动机是否仍在燃烧。 */
    public static boolean isMotorBurning(ResourceLocation currentDimension, int entityId) {
        return currentDimension != null
                && currentDimension.equals(dimension)
                && motorBurnRemainingTicksByEntityId.containsKey(entityId);
    }

    /** 返回指定燃烧中弹药距最后燃尽的剩余 Tick；不存在或维度不匹配时返回 0。 */
    public static int getMotorBurnRemainingTicks(ResourceLocation currentDimension, int entityId) {
        if (currentDimension == null || !currentDimension.equals(dimension)) {
            return 0;
        }
        return motorBurnRemainingTicksByEntityId.getOrDefault(entityId, 0);
    }

    /** 清除退出世界或切换维度后遗留的弹药视觉集合。 */
    public static void clear() {
        dimension = null;
        entityIds = Set.of();
        motorBurnRemainingTicksByEntityId = Map.of();
    }
}
