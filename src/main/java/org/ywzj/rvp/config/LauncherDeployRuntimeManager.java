package org.ywzj.rvp.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LauncherDeployRuntimeManager {

    /** 按端分桶（外层 key = isClientSide）：单机下服务端线程与客户端主线程并发读写，按端隔离快照。 */
    private static final Map<Boolean, Map<Integer, Map<String, Snapshot>>> STATES = new ConcurrentHashMap<>();

    private LauncherDeployRuntimeManager() {}

    public static void put(int vehicleId, String ruleId, Snapshot snapshot, boolean clientSide) {
        STATES.computeIfAbsent(clientSide, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(vehicleId, ignored -> new ConcurrentHashMap<>())
                .put(ruleId, snapshot);
    }

    public static Snapshot get(int vehicleId, String ruleId, boolean clientSide) {
        Map<Integer, Map<String, Snapshot>> bySide = STATES.get(clientSide);
        if (bySide == null) {
            return null;
        }
        Map<String, Snapshot> byRule = bySide.get(vehicleId);
        if (byRule == null) {
            return null;
        }
        return byRule.get(ruleId);
    }

    public static void clearVehicle(int vehicleId, boolean clientSide) {
        Map<Integer, Map<String, Snapshot>> bySide = STATES.get(clientSide);
        if (bySide != null) {
            bySide.remove(vehicleId);
        }
    }

    public enum State {
        CLOSED,
        DEPLOYING,
        OPEN,
        RETRACTING
    }

    public record Snapshot(State state, int progressTick, float currentPitch, double speedKph) {}
}
