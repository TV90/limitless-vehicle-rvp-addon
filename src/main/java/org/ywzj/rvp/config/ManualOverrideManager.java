package org.ywzj.rvp.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动覆盖冷却管理：玩家手动按 G 键切换起落架后，
 * 5 秒内自动逻辑不干预。
 * <p>
 * 由 {@link org.ywzj.rvp.mixin.AutoLandingGearManualOverrideMixin} 和
 * {@link org.ywzj.rvp.mixin.RotaryWingAutoLandingGearOverrideMixin} 写入，
 * 由 {@link org.ywzj.rvp.mixin.VehicleAutoLandingGearMixin} 查询。
 * </p>
 */
public final class ManualOverrideManager {

    /** 手动覆盖冷却：5 秒 = 100 tick */
    private static final int MANUAL_OVERRIDE_TICKS = 100;

    /**
     * 实体 ID → 手动覆盖结束时的 game time。
     * 玩家手动按 G 键时写入，自动逻辑每 tick 检查是否过期。
     */
    private static final Map<Integer, Long> MANUAL_OVERRIDE_UNTIL = new ConcurrentHashMap<>();

    private ManualOverrideManager() {}

    /** 标记手动覆盖：从当前 game time 起 5 秒内自动逻辑不干预 */
    public static void markManualOverride(int entityId, long currentGameTime) {
        MANUAL_OVERRIDE_UNTIL.put(entityId, currentGameTime + MANUAL_OVERRIDE_TICKS);
    }

    /** 供实体移除时清理 */
    public static void removeOverride(int entityId) {
        MANUAL_OVERRIDE_UNTIL.remove(entityId);
    }

    /**
     * 检查指定实体是否处于手动覆盖冷却期内。
     * 若已过期则自动清理条目。
     *
     * @return true 表示仍在冷却期内，自动逻辑应跳过
     */
    public static boolean isOverrideActive(int entityId, long currentGameTime) {
        Long overrideUntil = MANUAL_OVERRIDE_UNTIL.get(entityId);
        if (overrideUntil == null) return false;
        if (currentGameTime < overrideUntil) return true;
        MANUAL_OVERRIDE_UNTIL.remove(entityId);
        return false;
    }
}
