package org.ywzj.rvp.client.state;

import org.ywzj.rvp.network.S2CBulletVehicleHitDebug;

/** 最近一次载具命中的调试数据（供 HUD 显示）。 */
public final class RVP_ClientBulletHitDebugState {

    private static final int DISPLAY_TICKS = 100;

    private static float incidenceAngleDeg;
    private static float distanceM;
    private static float totalMultiplier;
    private static float distanceMultiplier;
    private static float incidenceMultiplier;
    private static float penetrationMultiplier;
    private static float vehicleTypeMultiplier;
    private static int ticksRemaining;

    private RVP_ClientBulletHitDebugState() {}

    public static void push(S2CBulletVehicleHitDebug msg) {
        incidenceAngleDeg = msg.incidenceAngleDeg;
        distanceM = msg.distanceM;
        totalMultiplier = msg.totalMultiplier;
        distanceMultiplier = msg.distanceMultiplier;
        incidenceMultiplier = msg.incidenceMultiplier;
        penetrationMultiplier = msg.penetrationMultiplier;
        vehicleTypeMultiplier = msg.vehicleTypeMultiplier;
        ticksRemaining = DISPLAY_TICKS;
    }

    public static void clientTick() {
        if (ticksRemaining > 0) {
            ticksRemaining--;
        }
    }

    public static boolean isActive() {
        return ticksRemaining > 0;
    }

    public static float getIncidenceAngleDeg() {
        return incidenceAngleDeg;
    }

    public static float getDistanceM() {
        return distanceM;
    }

    public static float getTotalMultiplier() {
        return totalMultiplier;
    }

    public static float getDistanceMultiplier() {
        return distanceMultiplier;
    }

    public static float getIncidenceMultiplier() {
        return incidenceMultiplier;
    }

    public static float getPenetrationMultiplier() {
        return penetrationMultiplier;
    }

    public static float getVehicleTypeMultiplier() {
        return vehicleTypeMultiplier;
    }
}
