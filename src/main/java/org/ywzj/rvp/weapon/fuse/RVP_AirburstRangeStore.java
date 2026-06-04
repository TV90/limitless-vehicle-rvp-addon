package org.ywzj.rvp.weapon.fuse;

import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每载具炮塔、每武器槽位的可编程空爆测距结果（米，与 MCH {@code airburstDist} 一致）。
 */
public final class RVP_AirburstRangeStore {

    private static final Map<Long, Integer> RANGES = new ConcurrentHashMap<>();

    private RVP_AirburstRangeStore() {}

    public static long key(AbstractVehicle vehicle, WeaponUnit unit, int weaponIndex) {
        return ((long) vehicle.getId() << 32)
                | ((long) unit.getIndex() << 16)
                | (weaponIndex & 0xFFFF);
    }

    public static int get(AbstractVehicle vehicle, WeaponUnit unit, int weaponIndex) {
        return RANGES.getOrDefault(key(vehicle, unit, weaponIndex), 0);
    }

    public static void set(AbstractVehicle vehicle, WeaponUnit unit, int weaponIndex, int distanceMeters) {
        long key = key(vehicle, unit, weaponIndex);
        if (distanceMeters <= 0) {
            RANGES.remove(key);
        } else {
            RANGES.put(key, distanceMeters);
        }
    }

    public static void clearVehicle(int vehicleId) {
        RANGES.keySet().removeIf(k -> (int) (k >> 32) == vehicleId);
    }
}
