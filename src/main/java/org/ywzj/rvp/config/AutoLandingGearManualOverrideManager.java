package org.ywzj.rvp.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AutoLandingGearManualOverrideManager {

    private static final int MANUAL_OVERRIDE_TICKS = 100;
    private static final Map<Integer, Long> MANUAL_OVERRIDE_UNTIL = new ConcurrentHashMap<>();

    private AutoLandingGearManualOverrideManager() {}

    public static void markManualOverride(int entityId, long currentGameTime) {
        MANUAL_OVERRIDE_UNTIL.put(entityId, currentGameTime + MANUAL_OVERRIDE_TICKS);
    }

    public static Long getOverrideUntil(int entityId) {
        return MANUAL_OVERRIDE_UNTIL.get(entityId);
    }

    public static void removeOverride(int entityId) {
        MANUAL_OVERRIDE_UNTIL.remove(entityId);
    }
}
