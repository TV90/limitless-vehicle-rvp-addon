package org.ywzj.rvp.uav;

import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RVP_DeployableUavCooldownRegistry {
    private static final Map<UUID, Long> PARENT_REDEPLOY_END_TICK = new ConcurrentHashMap<>();

    private RVP_DeployableUavCooldownRegistry() {}

    public static void startCooldown(ServerLevel level, UUID parentUuid, int cooldownTick) {
        if (level == null || parentUuid == null || cooldownTick <= 0) {
            return;
        }
        long endTick = level.getServer().getTickCount() + Math.max(cooldownTick, 0);
        PARENT_REDEPLOY_END_TICK.merge(parentUuid, endTick, Math::max);
    }

    public static int getRemainingTick(ServerLevel level, UUID parentUuid) {
        if (level == null || parentUuid == null) {
            return 0;
        }
        Long endTick = PARENT_REDEPLOY_END_TICK.get(parentUuid);
        if (endTick == null) {
            return 0;
        }
        long remaining = endTick - level.getServer().getTickCount();
        if (remaining <= 0L) {
            PARENT_REDEPLOY_END_TICK.remove(parentUuid);
            return 0;
        }
        return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    public static void clear(UUID parentUuid) {
        if (parentUuid != null) {
            PARENT_REDEPLOY_END_TICK.remove(parentUuid);
        }
    }
}
