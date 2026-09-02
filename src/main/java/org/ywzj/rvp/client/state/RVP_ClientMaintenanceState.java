package org.ywzj.rvp.client.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 快速维修 HUD 客户端状态（服务端 {@code S2CMaintenanceSync} 推送，节流 10 tick）。
 *
 * <p>{@code hasMaintenance=false} 的同步包会 {@link #clear} 对应条目——干扰物 HUD 的
 * "缺省自动补位"规则据此收起维修行。</p>
 */
public final class RVP_ClientMaintenanceState {

    /** 单台载具的维修 HUD 快照。 */
    public record Snapshot(int cooldownRemain, int useRemain, int useTimeTotal) {
        /** 是否维修生效中。 */
        public boolean isUsing() {
            return useRemain > 0;
        }

        /** 是否冷却中。 */
        public boolean isCoolingDown() {
            return cooldownRemain > 0;
        }
    }

    private static final Map<Integer, Snapshot> BY_VEHICLE_ENTITY_ID = new ConcurrentHashMap<>();

    private RVP_ClientMaintenanceState() {
    }

    public static void update(int vehicleEntityId, int cooldownRemain, int useRemain, int useTimeTotal) {
        BY_VEHICLE_ENTITY_ID.put(vehicleEntityId,
                new Snapshot(Math.max(0, cooldownRemain), Math.max(0, useRemain), Math.max(0, useTimeTotal)));
    }

    public static void clear(int vehicleEntityId) {
        BY_VEHICLE_ENTITY_ID.remove(vehicleEntityId);
    }

    /** 取载具的维修状态快照；未同步过 / 未启用返回 null（HUD 据此不画行）。 */
    public static Snapshot get(int vehicleEntityId) {
        return BY_VEHICLE_ENTITY_ID.get(vehicleEntityId);
    }
}
