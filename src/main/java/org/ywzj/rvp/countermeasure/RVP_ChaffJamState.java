package org.ywzj.rvp.countermeasure;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 雷达箔条禁锁侧表（目标实体 UUID → 禁锁截止游戏 tick）。
 *
 * <p>双端安全：服务端写（锁定目标周围箔条超阈值脱锁时），锁定拒绝点
 * （{@code RVP_RadarRoleHelper} 的锁定获取路径、gunner 锁定）读取。
 * 禁锁期内目标仍可被扫描（探测表不受影响），仅不能被选中 / 锁定。</p>
 */
public final class RVP_ChaffJamState {

    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();

    private RVP_ChaffJamState() {
    }

    /** 写入目标禁锁期（游戏 tick）。 */
    public static void setCooldown(UUID targetUuid, long gameTime, int cooldownTick) {
        if (targetUuid == null || cooldownTick <= 0) {
            return;
        }
        COOLDOWN_UNTIL.put(targetUuid, gameTime + cooldownTick);
    }

    /** 目标是否处于箔条禁锁期（期间不可被雷达选中 / 锁定）。 */
    public static boolean isInCooldown(UUID targetUuid, long gameTime) {
        if (targetUuid == null) {
            return false;
        }
        Long until = COOLDOWN_UNTIL.get(targetUuid);
        if (until == null) {
            return false;
        }
        if (gameTime >= until) {
            COOLDOWN_UNTIL.remove(targetUuid);
            return false;
        }
        return true;
    }

    public static void clear(UUID targetUuid) {
        if (targetUuid != null) {
            COOLDOWN_UNTIL.remove(targetUuid);
        }
    }

    /** 服务端每 tick 清理过期条目，避免内存膨胀。 */
    public static void onServerTick(long gameTime) {
        COOLDOWN_UNTIL.entrySet().removeIf(entry -> gameTime >= entry.getValue());
    }
}
