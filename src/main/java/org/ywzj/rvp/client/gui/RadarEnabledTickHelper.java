package org.ywzj.rvp.client.gui;

import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.WeakHashMap;

/**
 * 记录每个雷达开启时的 tick，用于 phase 雷达首次扫描动画。
 */
public class RadarEnabledTickHelper {
    private static final WeakHashMap<RadarUnit, Integer> enabledTickMap = new WeakHashMap<>();

    public static void setEnabledTick(RadarUnit unit, int tick) {
        enabledTickMap.put(unit, tick);
    }

    public static int getEnabledTick(RadarUnit unit) {
        return enabledTickMap.getOrDefault(unit, 0);
    }
}
