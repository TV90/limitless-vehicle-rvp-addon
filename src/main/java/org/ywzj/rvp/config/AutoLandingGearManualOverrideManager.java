package org.ywzj.rvp.config;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动收放起落架的覆盖状态管理。
 * <p>
 * 玩家一旦手动操作起落架（toggleLandingGear），该载具即进入手动覆盖模式：
 * 自动收放逻辑完全停用，直到载具销毁（实体移除时由 tick 逻辑清理标记）。
 * </p>
 */
public final class AutoLandingGearManualOverrideManager {

    private static final Set<Integer> MANUAL_OVERRIDE = ConcurrentHashMap.newKeySet();

    private AutoLandingGearManualOverrideManager() {}

    public static void markManualOverride(int entityId) {
        MANUAL_OVERRIDE.add(entityId);
    }

    public static boolean isManualOverride(int entityId) {
        return MANUAL_OVERRIDE.contains(entityId);
    }

    public static void clearOverride(int entityId) {
        MANUAL_OVERRIDE.remove(entityId);
    }
}
