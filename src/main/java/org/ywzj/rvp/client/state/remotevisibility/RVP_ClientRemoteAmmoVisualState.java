package org.ywzj.rvp.client.state.remotevisibility;

import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/** 客户端弹药超视距视觉完整集合状态。 */
public final class RVP_ClientRemoteAmmoVisualState {
    /** 当前完整集合所属维度。 */
    private static ResourceLocation dimension;
    /** 当前允许扩展绘制的弹药实体 ID。 */
    private static Set<Integer> entityIds = Set.of();
    /** 当前发动机仍在燃烧的弹药实体 ID。 */
    private static Set<Integer> motorBurningEntityIds = Set.of();

    private RVP_ClientRemoteAmmoVisualState() {
    }

    /** 使用最新服务端完整集合替换客户端弹药视觉状态。 */
    public static void replace(ResourceLocation newDimension, Set<Integer> newEntityIds,
                               Set<Integer> newMotorBurningEntityIds) {
        dimension = newDimension;
        entityIds = newEntityIds == null ? Set.of() : Set.copyOf(new HashSet<>(newEntityIds));
        motorBurningEntityIds = newMotorBurningEntityIds == null
                ? Set.of()
                : Set.copyOf(new HashSet<>(newMotorBurningEntityIds));
    }

    /** 判断指定维度和实体 ID 是否在服务端授权集合内。 */
    public static boolean contains(ResourceLocation currentDimension, int entityId) {
        return currentDimension != null && currentDimension.equals(dimension) && entityIds.contains(entityId);
    }

    /** 判断指定弹药实体的发动机是否仍在燃烧。 */
    public static boolean isMotorBurning(ResourceLocation currentDimension, int entityId) {
        return currentDimension != null
                && currentDimension.equals(dimension)
                && motorBurningEntityIds.contains(entityId);
    }

    /** 清除退出世界或切换维度后遗留的弹药视觉集合。 */
    public static void clear() {
        dimension = null;
        entityIds = Set.of();
        motorBurningEntityIds = Set.of();
    }
}
