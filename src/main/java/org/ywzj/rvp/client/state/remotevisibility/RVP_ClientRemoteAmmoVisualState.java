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
    /** 各实体最近一次出现在服务端授权集合中的客户端世界 Tick。 */
    private static Map<Integer, Long> lastSeenEntityGameTimeById = Map.of();
    /** 各实体最近一次出现在服务端燃烧映射中的客户端世界 Tick。 */
    private static Map<Integer, Long> lastSeenMotorBurnGameTimeById = Map.of();

    private RVP_ClientRemoteAmmoVisualState() {
    }

    /** 使用最新服务端完整集合替换客户端弹药视觉状态。 */
    public static void replace(ResourceLocation newDimension, Set<Integer> newEntityIds,
                               Map<Integer, Integer> newMotorBurnRemainingTicksByEntityId,
                               long clientGameTime) {
        boolean sameDimension = newDimension != null && newDimension.equals(dimension);
        dimension = newDimension;
        entityIds = newEntityIds == null ? Set.of() : Set.copyOf(new HashSet<>(newEntityIds));
        motorBurnRemainingTicksByEntityId = newMotorBurnRemainingTicksByEntityId == null
                ? Map.of()
                : Map.copyOf(new HashMap<>(newMotorBurnRemainingTicksByEntityId));

        Map<Integer, Long> entitySeen = sameDimension
                ? new HashMap<>(lastSeenEntityGameTimeById) : new HashMap<>();
        for (Integer entityId : entityIds) {
            entitySeen.put(entityId, clientGameTime);
        }
        lastSeenEntityGameTimeById = Map.copyOf(entitySeen);

        Map<Integer, Long> motorSeen = sameDimension
                ? new HashMap<>(lastSeenMotorBurnGameTimeById) : new HashMap<>();
        for (Integer entityId : motorBurnRemainingTicksByEntityId.keySet()) {
            motorSeen.put(entityId, clientGameTime);
        }
        lastSeenMotorBurnGameTimeById = Map.copyOf(motorSeen);
    }

    /** 判断指定维度和实体 ID 是否在服务端授权集合内。 */
    public static boolean contains(ResourceLocation currentDimension, int entityId) {
        return currentDimension != null && currentDimension.equals(dimension) && entityIds.contains(entityId);
    }

    /**
     * 判断尾迹是否仍可使用指定弹药；服务端完整快照短暂漏掉实体时保留 10 Tick 的视觉宽限。
     *
     * @param currentDimension 当前客户端维度
     * @param entityId 弹药实体 ID
     * @param clientGameTime 当前客户端世界 Tick
     * @return 当前授权或处于尾迹宽限期
     */
    public static boolean containsForTrail(ResourceLocation currentDimension, int entityId,
                                           long clientGameTime) {
        if (contains(currentDimension, entityId)) {
            return true;
        }
        return withinTrailGrace(currentDimension, entityId, clientGameTime,
                lastSeenEntityGameTimeById);
    }

    /** 判断指定弹药实体的发动机是否仍在燃烧。 */
    public static boolean isMotorBurning(ResourceLocation currentDimension, int entityId) {
        return currentDimension != null
                && currentDimension.equals(dimension)
                && motorBurnRemainingTicksByEntityId.containsKey(entityId);
    }

    /**
     * 判断尾迹发动机是否仍应视为燃烧；仅在实体连同完整快照一起暂时缺失时使用宽限。
     * 当前快照明确包含实体但燃烧映射已移除时，视为真实燃尽，不延长尾焰。
     *
     * @param currentDimension 当前客户端维度
     * @param entityId 弹药实体 ID
     * @param clientGameTime 当前客户端世界 Tick
     * @return 当前燃烧或处于授权快照缺失宽限期
     */
    public static boolean isMotorBurningForTrail(ResourceLocation currentDimension, int entityId,
                                                 long clientGameTime) {
        if (isMotorBurning(currentDimension, entityId)) {
            return true;
        }
        if (contains(currentDimension, entityId)) {
            return false;
        }
        return withinTrailGrace(currentDimension, entityId, clientGameTime,
                lastSeenMotorBurnGameTimeById);
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
        lastSeenEntityGameTimeById = Map.of();
        lastSeenMotorBurnGameTimeById = Map.of();
    }

    /** 判断指定实体的最近授权时间是否仍在尾迹宽限窗口内。 */
    private static boolean withinTrailGrace(ResourceLocation currentDimension, int entityId,
                                            long clientGameTime, Map<Integer, Long> lastSeenById) {
        if (currentDimension == null || !currentDimension.equals(dimension)
                || clientGameTime == Long.MIN_VALUE) {
            return false;
        }
        Long lastSeenGameTime = lastSeenById.get(entityId);
        if (lastSeenGameTime == null || clientGameTime < lastSeenGameTime) {
            return false;
        }
        return clientGameTime - lastSeenGameTime <= TRAIL_AUTHORIZATION_GRACE_TICKS;
    }

    /** 尾迹授权快照缺失后的视觉宽限时间，单位 Tick。 */
    private static final long TRAIL_AUTHORIZATION_GRACE_TICKS = 10L;
}
