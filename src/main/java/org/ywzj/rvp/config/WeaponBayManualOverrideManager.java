package org.ywzj.rvp.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manual override state for weapon bay auto-open logic.
 * A manual toggle keeps priority until the weapon selection changes.
 */
public final class WeaponBayManualOverrideManager {

    private static final Map<Long, OverrideState> OVERRIDES = new ConcurrentHashMap<>();

    private WeaponBayManualOverrideManager() {}

    public static void markManualOverride(int vehicleId, int weaponUnitIndex, int primaryIndex, int secondaryIndex) {
        OVERRIDES.put(key(vehicleId, weaponUnitIndex), new OverrideState(primaryIndex, secondaryIndex));
    }

    public static boolean isOverrideActive(int vehicleId, int weaponUnitIndex, int primaryIndex, int secondaryIndex) {
        long key = key(vehicleId, weaponUnitIndex);
        OverrideState state = OVERRIDES.get(key);
        if (state == null) {
            return false;
        }
        if (state.primaryIndex == primaryIndex && state.secondaryIndex == secondaryIndex) {
            return true;
        }
        OVERRIDES.remove(key);
        return false;
    }

    public static void clear(int vehicleId, int weaponUnitIndex) {
        OVERRIDES.remove(key(vehicleId, weaponUnitIndex));
    }

    private static long key(int vehicleId, int weaponUnitIndex) {
        return ((long) vehicleId << 32) ^ (weaponUnitIndex & 0xFFFFFFFFL);
    }

    private record OverrideState(int primaryIndex, int secondaryIndex) {}
}
