package org.ywzj.rvp.client.state;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 干扰物 HUD 客户端状态（剩余 / 总数 / 装填剩余，按载具实体 id 存）。
 */
public final class RVP_CountermeasureHudState {

    public record Snapshot(int flareRemain, int flareTotal, int flareReloadRemain,
                           int chaffRemain, int chaffTotal, int chaffReloadRemain) {
    }

    private static final Map<Integer, Snapshot> STATES = new ConcurrentHashMap<>();

    public static void update(int vehicleEntityId,
                              int flareRemain, int flareTotal, int flareReloadRemain,
                              int chaffRemain, int chaffTotal, int chaffReloadRemain) {
        if (vehicleEntityId <= 0) {
            return;
        }
        STATES.put(vehicleEntityId, new Snapshot(
                Math.max(0, flareRemain), Math.max(0, flareTotal), Math.max(0, flareReloadRemain),
                Math.max(0, chaffRemain), Math.max(0, chaffTotal), Math.max(0, chaffReloadRemain)
        ));
    }

    @Nullable
    public static Snapshot get(int vehicleEntityId) {
        return STATES.get(vehicleEntityId);
    }

    public static void remove(int vehicleEntityId) {
        STATES.remove(vehicleEntityId);
    }

    private RVP_CountermeasureHudState() {
    }
}
