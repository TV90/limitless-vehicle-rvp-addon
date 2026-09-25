package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端维修顺序侧表（辅助设备面板用，会话内记忆）：载具实体 id →
 * 面板里编辑的"爆反维修顺序 / 辅助设备维修顺序"两条有序骨名列表。
 *
 * <p>面板编辑时先更新本表（界面即时反馈）再经 {@code C2SSetRepairOrder} 上行同步；
 * 服务端权威副本在 {@code RVP_RepairOrderTable}（按 UUID，车组共享）。</p>
 *
 * <p>清理惯例与 {@code RVP_ClientGunnerVehicleState} 一致：维度切换 / 登出全清，
 * 每 tick 剔除已卸载实体；挂载点在 {@code RVP_ClientEvents.onClientTick}。</p>
 */
public final class RVP_ClientRepairOrderState {

    /** 单车维修顺序：两队列均队首先修。 */
    public record Order(List<String> eraBones, List<String> deviceBones) {
    }

    /** 空顺序缺省值。 */
    public static final Order EMPTY = new Order(List.of(), List.of());

    /** 单队列长度上限（与服务端 RVP_RepairOrderTable 一致）。 */
    private static final int MAX_QUEUE = 64;

    private static final Map<Integer, Order> ORDERS = new LinkedHashMap<>();

    private RVP_ClientRepairOrderState() {
    }

    /** 读取载具维修顺序；未编辑过返回 {@link #EMPTY}。 */
    public static Order get(int vehicleEntityId) {
        return ORDERS.getOrDefault(vehicleEntityId, EMPTY);
    }

    /** 面板编辑后更新本表（仅保留非空骨名、保序去重、截断）。 */
    public static void set(int vehicleEntityId, List<String> eraBones, List<String> deviceBones) {
        if (eraBones.isEmpty() && deviceBones.isEmpty()) {
            ORDERS.remove(vehicleEntityId);
            return;
        }
        ORDERS.put(vehicleEntityId, new Order(sanitize(eraBones), sanitize(deviceBones)));
    }

    /** 每 tick 清理：维度切换 / 登出全清，剔除已卸载载具实体。 */
    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clear();
            return;
        }
        ORDERS.keySet().removeIf(entityId -> mc.level.getEntity(entityId) == null);
    }

    /** 登出 / 清空。 */
    public static void clear() {
        ORDERS.clear();
    }

    /** 去空 / 保序去重 / 截断。 */
    private static List<String> sanitize(List<String> bones) {
        List<String> out = new ArrayList<>();
        for (String bone : bones) {
            if (bone != null && !bone.isBlank() && !out.contains(bone) && out.size() < MAX_QUEUE) {
                out.add(bone);
            }
        }
        return out;
    }
}
