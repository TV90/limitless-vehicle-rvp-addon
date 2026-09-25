package org.ywzj.rvp.maintenance.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 维修顺序侧表（服务端）：载具 UUID → 玩家在辅助设备面板设置的两组维修优先级
 * （爆反 / 辅助设备，对应 {@code recoverModules} 的 ERA 配额与设备掷骰两条分支）。
 *
 * <p>只记录"顺序"，不校验目标当前是否失效——消费点
 * {@link RVP_MaintenanceRuntimeManager#recoverModules} 在快修触发时按队列头优先取用，
 * 未失效 / 不存在 / 不可修的条目自然跳过（服务端权威，无越权面）。</p>
 *
 * <p>车组共享：任何乘员设置后全车生效；无持久化需求，载具离开世界即清
 * （清理点在 {@code RVP_MaintenanceEventHandler} 的 EntityLeaveLevelEvent）。</p>
 */
public final class RVP_RepairOrderTable {

    /** 单车维修顺序：两队列均队首先修。 */
    public record RepairOrder(List<String> eraBones, List<String> deviceBones) {
    }

    /** 空顺序（未设置时的缺省返回，消费端行为与改动前完全一致）。 */
    public static final RepairOrder EMPTY = new RepairOrder(List.of(), List.of());

    /** 单队列长度上限：正常单车同型失效骨块远小于此，防异常长度。 */
    private static final int MAX_QUEUE = 64;

    private static final Map<UUID, RepairOrder> ORDERS = new HashMap<>();

    private RVP_RepairOrderTable() {
    }

    /** 记录一组维修顺序（保序去重 / 去空 / 截断；传空列表 = 清除该队列）。 */
    public static void setOrder(UUID vehicleId, List<String> eraBones, List<String> deviceBones) {
        ORDERS.put(vehicleId, new RepairOrder(sanitize(eraBones), sanitize(deviceBones)));
    }

    /** 读取维修顺序；未设置返回 {@link #EMPTY}。 */
    public static RepairOrder getOrder(UUID vehicleId) {
        return ORDERS.getOrDefault(vehicleId, EMPTY);
    }

    /** 载具离开世界时清理内存侧表。 */
    public static void onVehicleLeave(UUID vehicleId) {
        ORDERS.remove(vehicleId);
    }

    /** 去空 / 保序去重 / 截断到 {@link #MAX_QUEUE}。 */
    private static List<String> sanitize(List<String> bones) {
        if (bones == null || bones.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String bone : bones) {
            if (bone != null && !bone.isBlank() && dedup.size() < MAX_QUEUE) {
                dedup.add(bone);
            }
        }
        return new ArrayList<>(dedup);
    }
}
