package org.ywzj.rvp.client.laser;

import net.minecraft.world.level.Level;
import org.ywzj.rvp.weapon.core.RVP_LaserWeapon;
import org.ywzj.rvp.weapon.data.RVP_LaserVisualData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active vehicle lasers on the client. Each fire pulse extends {@code duration_tick};
 * beam geometry is recomputed every frame from the live weapon aim (emitter follows the vehicle).
 */
public final class RVP_ClientLaserState {

    public record LaserBeamKey(int vehicleId, int partUnitIndex, int weaponIndex) {
        /**
         * Uses the {@link WeaponUnit} that actually lists {@code weapon} in {@code indexedWeapons}
         * (often the operator station), not {@link AbstractVehicleWeapon#getWeaponUnit()} (often the
         * gimbal / muzzle part such as {@code auto_cannon}).
         */
        public static LaserBeamKey of(AbstractVehicle vehicle, AbstractVehicleWeapon<?> weapon) {
            for (int i = 0; i < vehicle.getPartUnits().size(); i++) {
                if (vehicle.getPartUnits().get(i) instanceof WeaponUnit weaponUnit) {
                    int slot = weaponUnit.indexedWeapons.indexOf(weapon);
                    if (slot >= 0) {
                        return new LaserBeamKey(vehicle.getId(), i, slot);
                    }
                }
            }
            return new LaserBeamKey(vehicle.getId(), weapon.getWeaponUnit().getIndex(), weapon.getIndex());
        }
    }

    public record ActiveLaser(RVP_LaserVisualData visual, long expireGameTime, int operatorEntityId) {}

    private static final Map<LaserBeamKey, ActiveLaser> ACTIVE = new ConcurrentHashMap<>();

    private RVP_ClientLaserState() {}

    public static void pulse(LaserBeamKey key, RVP_LaserVisualData visual, long gameTime,
                             int operatorEntityId) {
        long expire = gameTime + visual.getDurationTick();
        ACTIVE.put(key, new ActiveLaser(visual, expire, operatorEntityId));
    }

    public static void clear(LaserBeamKey key) {
        if (ACTIVE.remove(key) != null) {
            RVP_LaserImpactEffects.clearKey(key);
            RVP_LaserBeamSmoothing.clear(key);
        }
    }

    public static void tick(Level level) {
        long gameTime = level.getGameTime();
        pruneSuspended(level);
        Iterator<Map.Entry<LaserBeamKey, ActiveLaser>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().expireGameTime < gameTime) {
                clear(entry.getKey());
            }
        }
    }

    private static void pruneSuspended(Level level) {
        for (LaserBeamKey key : ACTIVE.keySet().toArray(LaserBeamKey[]::new)) {
            if (!(level.getEntity(key.vehicleId()) instanceof AbstractVehicle vehicle)) {
                clear(key);
                continue;
            }
            WeaponUnit registry = RVP_LaserWeapons.registryUnit(vehicle, key);
            RVP_LaserWeapon laser = RVP_LaserWeapons.resolveLaser(registry, key.weaponIndex());
            if (laser == null || !RVP_LaserWeapons.canRenderBeam(laser)) {
                clear(key);
            }
        }
    }

    public static Map<LaserBeamKey, ActiveLaser> view() {
        return ACTIVE;
    }

    public static RVP_LaserVisualData visualOf(ActiveLaser active) {
        return active.visual;
    }

    public static int operatorIdOf(ActiveLaser active) {
        return active.operatorEntityId;
    }
}
