package org.ywzj.rvp.guidance.saclos;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-operator SACLOS laser designator state (server). Mirrors MCH weapon {@code MCH_LaserGuidanceSystem}.
 */
public final class RVP_SaclosOperatorSession {

    /**
     * 玩家照射会话状态：targeting=是否正在照射，point=实时照射点，
     * stablePIPAssist=客户端是否请求STABLE PIP辅助，updatedMs=最近刷新时刻（防陈旧条目）。
     */
    private record State(boolean targeting, @Nullable Vec3 point,
                         boolean stablePIPAssist, long updatedMs) {}

    /** 无会话玩家的默认状态：保留历史默认照射开启，但不允许STABLE PIP辅助。 */
    private static final State DEFAULT = new State(true, null, false, 0L);

    /** 各操作手UUID对应的服务端SACLOS照射会话。 */
    private static final Map<UUID, State> SESSIONS = new ConcurrentHashMap<>();

    private RVP_SaclosOperatorSession() {}

    public static void setDesignation(UUID playerId, boolean targeting, @Nullable Vec3 point) {
        setDesignation(playerId, targeting, point, false);
    }

    /**
     * 更新操作手照射点与STABLE PIP辅助请求。
     *
     * @param playerId 操作手UUID
     * @param targeting 是否保持照射
     * @param point 当前世界照射点
     * @param stablePIPAssist 是否请求STABLE模式PIP辅助；服务端制导源还会再次校验
     */
    public static void setDesignation(UUID playerId, boolean targeting, @Nullable Vec3 point,
                                      boolean stablePIPAssist) {
        long now = System.currentTimeMillis();
        if (!targeting) {
            SESSIONS.put(playerId, new State(false, null, false, now));
            return;
        }
        if (point != null) {
            SESSIONS.put(playerId, new State(true, point, stablePIPAssist, now));
        }
    }

    public static boolean isLaserEnabled(UUID playerId) {
        return SESSIONS.getOrDefault(playerId, DEFAULT).targeting();
    }

    public static boolean isLaserEnabled(RVP_BaseBullet projectile) {
        if (projectile.getOwner() == null) {
            return true;
        }
        return isLaserEnabled(projectile.getOwner().getUUID());
    }

    @Nullable
    public static Vec3 getDesignationPoint(UUID playerId) {
        State state = SESSIONS.getOrDefault(playerId, DEFAULT);
        return state.targeting() ? state.point() : null;
    }

    /**
     * 判断操作手是否具有仍然新鲜的STABLE PIP辅助请求。
     *
     * @param playerId 操作手UUID
     * @param maxAgeMs 请求允许的最大年龄，单位毫秒
     * @return 会话正在照射、具有有效点且请求未过期时返回true
     */
    public static boolean isStablePIPAssistRequested(UUID playerId, long maxAgeMs) {
        State state = SESSIONS.getOrDefault(playerId, DEFAULT);
        long ageMs = System.currentTimeMillis() - state.updatedMs();
        return state.targeting()
                && state.point() != null
                && state.stablePIPAssist()
                && ageMs >= 0L
                && ageMs <= Math.max(maxAgeMs, 0L);
    }

    /** 正在照射的活跃会话快照：玩家 UUID → 实时照射点。
     * 仅含 targeting 且 maxAgeMs 内刷新过的条目（客户端每 tick 同步照射点，超时视为已停止照射），
     * 供 gunner 检测"被激光照射"。 */
    public static Map<UUID, Vec3> activeDesignations(long maxAgeMs) {
        long now = System.currentTimeMillis();
        Map<UUID, Vec3> out = new java.util.HashMap<>();
        SESSIONS.forEach((id, s) -> {
            if (s.targeting() && s.point() != null && now - s.updatedMs() <= maxAgeMs) {
                out.put(id, s.point());
            }
        });
        return out;
    }

    public static void clear(UUID playerId) {
        SESSIONS.remove(playerId);
    }
}
