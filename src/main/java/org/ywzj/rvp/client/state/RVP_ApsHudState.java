package org.ywzj.rvp.client.state;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RVP_ApsHudState {

    public record Snapshot(int ammoCurrent, int ammoMax, int reloadOneTick, int reloadProgressTick) {}

    private static final Map<Integer, Snapshot> STATES = new ConcurrentHashMap<>();

    public static void update(int vehicleEntityId, int ammoCurrent, int ammoMax, int reloadOneTick, int reloadProgressTick) {
        if (vehicleEntityId <= 0) {
            return;
        }
        STATES.put(vehicleEntityId, new Snapshot(
                Math.max(0, ammoCurrent),
                Math.max(0, ammoMax),
                Math.max(1, reloadOneTick),
                Math.max(0, reloadProgressTick)
        ));
    }

    public static @Nullable Snapshot get(int vehicleEntityId) {
        return STATES.get(vehicleEntityId);
    }

    public static void remove(int vehicleEntityId) {
        STATES.remove(vehicleEntityId);
    }

    private RVP_ApsHudState() {}
}

