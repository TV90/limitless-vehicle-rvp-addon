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

    private record State(boolean targeting, @Nullable Vec3 point) {}

    private static final State DEFAULT = new State(true, null);
    private static final Map<UUID, State> SESSIONS = new ConcurrentHashMap<>();

    private RVP_SaclosOperatorSession() {}

    public static void setDesignation(UUID playerId, boolean targeting, @Nullable Vec3 point) {
        if (!targeting) {
            SESSIONS.put(playerId, new State(false, null));
            return;
        }
        if (point != null) {
            SESSIONS.put(playerId, new State(true, point));
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

    public static void clear(UUID playerId) {
        SESSIONS.remove(playerId);
    }
}
