package org.ywzj.rvp.client.state;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端主动ECM HUD 状态（P1 占位，用于接收 S2CEcmActiveHudSync）。
 */
public final class RVP_EcmActiveHudState {

    /** 单载具主动ECM快照。 */
    public record Snapshot(int activeRemainTick, int cooldownRemainTick, int maxActiveTick, int maxCooldownTick) {
        /** 是否处于主动干扰中。 */
        public boolean isActive() {
            return activeRemainTick > 0;
        }

        /** 是否处于冷却中。 */
        public boolean isCoolingDown() {
            return cooldownRemainTick > 0;
        }

        /** 是否就绪。 */
        public boolean isReady() {
            return activeRemainTick <= 0 && cooldownRemainTick <= 0;
        }
    }

    private static final Map<Integer, Snapshot> STATES = new ConcurrentHashMap<>();

    public static void update(int vehicleEntityId, int activeRemainTick, int cooldownRemainTick,
                              int maxActiveTick, int maxCooldownTick) {
        if (vehicleEntityId <= 0) {
            return;
        }
        STATES.put(vehicleEntityId, new Snapshot(activeRemainTick, cooldownRemainTick, maxActiveTick, maxCooldownTick));
    }

    @Nullable
    public static Snapshot get(int vehicleEntityId) {
        return STATES.get(vehicleEntityId);
    }

    public static void remove(int vehicleEntityId) {
        STATES.remove(vehicleEntityId);
    }

    private RVP_EcmActiveHudState() {
    }
}
