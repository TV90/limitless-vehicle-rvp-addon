package org.ywzj.rvp.client.state;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class RVP_RocketCcipScreenState {

    private static final Map<Integer, State> STATES = new HashMap<>();
    private static final double PIXEL_DEADBAND_FAR = 1.25D;
    private static final double PIXEL_DEADBAND_NEAR = 3.25D;
    private static final double NEAR_DISTANCE_METERS = 96.0D;
    private static final double NANOS_TO_SECONDS = 1.0D / 1_000_000_000.0D;

    private RVP_RocketCcipScreenState() {}

    @Nullable
    public static Vec3 smooth(int vehicleId, @Nullable ResourceLocation weaponId, int nowTick,
                              @Nullable Vec3 screenPos, double hitDistanceMeters) {
        if (screenPos == null) {
            return null;
        }
        long nowNanos = System.nanoTime();
        State state = STATES.get(vehicleId);
        String weaponKey = weaponId == null ? "" : weaponId.toString();
        if (state == null) {
            state = new State();
            STATES.put(vehicleId, state);
        }
        if (!state.initialized || !weaponKey.equals(state.weaponKey) || nowTick - state.lastSeenTick > 8) {
            state.x = screenPos.x;
            state.y = screenPos.y;
            state.z = screenPos.z;
            state.initialized = true;
            state.weaponKey = weaponKey;
            state.lastUpdateNanos = nowNanos;
        } else {
            double dx = screenPos.x - state.x;
            double dy = screenPos.y - state.y;
            double dz = screenPos.z - state.z;
            double err = Math.sqrt(dx * dx + dy * dy);
            double nearFactor = Mth.clamp((NEAR_DISTANCE_METERS - hitDistanceMeters) / NEAR_DISTANCE_METERS, 0.0D, 1.0D);
            double deadband = Mth.lerp(nearFactor, PIXEL_DEADBAND_FAR, PIXEL_DEADBAND_NEAR);
            double elapsedSeconds = state.lastUpdateNanos <= 0L
                    ? 1.0D / 60.0D
                    : Mth.clamp((nowNanos - state.lastUpdateNanos) * NANOS_TO_SECONDS, 1.0D / 240.0D, 0.1D);
            if (err > deadband) {
                double excessRatio = (err - deadband) / err;
                double targetX = state.x + dx * excessRatio;
                double targetY = state.y + dy * excessRatio;
                double response = Mth.clamp((err - deadband) / 24.0D, 0.0D, 1.0D);
                double timeConstant = Mth.lerp(response, Mth.lerp(nearFactor, 0.16D, 0.24D), 0.055D);
                double alpha = 1.0D - Math.exp(-elapsedSeconds / timeConstant);
                state.x += (targetX - state.x) * alpha;
                state.y += (targetY - state.y) * alpha;
            }
            double depthAlpha = 1.0D - Math.exp(-elapsedSeconds / 0.08D);
            state.z += dz * depthAlpha;
            state.lastUpdateNanos = nowNanos;
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
        long lastUpdateNanos;
    }
}
