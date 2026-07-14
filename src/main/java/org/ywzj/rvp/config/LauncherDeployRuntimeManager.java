package org.ywzj.rvp.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LauncherDeployRuntimeManager {

    private static final Map<Integer, Map<String, Snapshot>> STATES = new ConcurrentHashMap<>();

    private LauncherDeployRuntimeManager() {}

    public static void put(int vehicleId, String ruleId, Snapshot snapshot) {
        STATES.computeIfAbsent(vehicleId, ignored -> new ConcurrentHashMap<>()).put(ruleId, snapshot);
    }

    public static Snapshot get(int vehicleId, String ruleId) {
        Map<String, Snapshot> byRule = STATES.get(vehicleId);
        if (byRule == null) {
            return null;
        }
        return byRule.get(ruleId);
    }

    public static void clearVehicle(int vehicleId) {
        STATES.remove(vehicleId);
    }

    public enum State {
        CLOSED,
        DEPLOYING,
        OPEN,
        RETRACTING
    }

    public record Snapshot(State state, int progressTick, float currentPitch, double speedKph) {}
}
