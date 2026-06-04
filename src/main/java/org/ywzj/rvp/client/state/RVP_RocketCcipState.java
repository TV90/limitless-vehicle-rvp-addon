package org.ywzj.rvp.client.state;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class RVP_RocketCcipState {

    private static final Map<Integer, State> STATES = new HashMap<>();

    private RVP_RocketCcipState() {}

    @Nullable
    public static Vec3 smooth(int vehicleId, @Nullable ResourceLocation weaponId, int nowTick, @Nullable Vec3 impact) {
        if (impact == null) {
            return null;
        }
        State state = STATES.get(vehicleId);
        String weaponKey = weaponId == null ? "" : weaponId.toString();
        if (state == null) {
            state = new State();
            STATES.put(vehicleId, state);
        }
        if (!state.initialized || !weaponKey.equals(state.weaponKey) || nowTick - state.lastSeenTick > 8) {
            state.x = impact.x;
            state.y = impact.y;
            state.z = impact.z;
            state.initialized = true;
            state.weaponKey = weaponKey;
        } else {
            double dx = impact.x - state.x;
            double dy = impact.y - state.y;
            double dz = impact.z - state.z;
            double err = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double alpha = Math.max(0.16D, Math.min(0.62D, 0.20D + err * 0.08D));
            state.x += dx * alpha;
            state.y += dy * alpha;
            state.z += dz * alpha;
        }
        state.lastSeenTick = nowTick;
        if (STATES.size() > 24 && (nowTick & 31) == 0) {
            Iterator<Map.Entry<Integer, State>> it = STATES.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, State> entry = it.next();
                if (nowTick - entry.getValue().lastSeenTick > 80) {
                    it.remove();
                }
            }
        }
        return new Vec3(state.x, state.y, state.z);
    }

    public static void clear(int vehicleId) {
        STATES.remove(vehicleId);
    }

    private static final class State {
        double x;
        double y;
        double z;
        int lastSeenTick;
        String weaponKey = "";
        boolean initialized;
    }
}
