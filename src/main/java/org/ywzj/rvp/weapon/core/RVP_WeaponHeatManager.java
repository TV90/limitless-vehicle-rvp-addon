package org.ywzj.rvp.weapon.core;

import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.config.RVP_VehicleWeaponHeatConfig;
import org.ywzj.rvp.config.RVP_VehicleWeaponHeatConfigCache;
import org.ywzj.rvp.weapon.data.RVP_FireData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class RVP_WeaponHeatManager {

    private static final Map<AbstractVehicle, Map<RVP_VehicleWeaponHeatConfigCache.SlotKey, HeatState>> VEHICLE_HEAT = new WeakHashMap<>();

    private RVP_WeaponHeatManager() {}

    public static void tick(RVP_WeaponBase weapon, HeatState weaponState) {
        HeatSpec spec = resolveSpec(weapon);
        if (!spec.enabled()) {
            return;
        }
        tickState(spec.state(), spec.maxHeatCount(), weapon.getVehicle().tickCount);
    }

    public static boolean canShoot(RVP_WeaponBase weapon, HeatState weaponState) {
        HeatSpec spec = resolveSpec(weapon);
        if (!spec.enabled()) {
            return true;
        }
        HeatState state = spec.state();
        tickState(state, spec.maxHeatCount(), weapon.getVehicle().tickCount);
        return state.currentHeat < spec.maxHeatCount();
    }

    public static void onShotFired(RVP_WeaponBase weapon, HeatState weaponState) {
        HeatSpec spec = resolveSpec(weapon);
        if (!spec.enabled()) {
            return;
        }
        HeatState state = spec.state();
        tickState(state, spec.maxHeatCount(), weapon.getVehicle().tickCount);
        state.cooldownSpeed = 1;
        state.currentHeat += spec.heatCount();
        if (state.currentHeat >= spec.maxHeatCount()) {
            state.currentHeat += spec.overheatExtraHeat();
        }
    }

    public static int currentHeat(RVP_WeaponBase weapon, HeatState weaponState) {
        HeatSpec spec = resolveSpec(weapon);
        if (!spec.enabled()) {
            return 0;
        }
        HeatState state = spec.state();
        tickState(state, spec.maxHeatCount(), weapon.getVehicle().tickCount);
        return Math.max(state.currentHeat, 0);
    }

    public static int maxHeat(RVP_WeaponBase weapon, HeatState weaponState) {
        HeatSpec spec = resolveSpec(weapon);
        return spec.enabled() ? spec.maxHeatCount() : 0;
    }

    public static float heatRatio(RVP_WeaponBase weapon, HeatState weaponState) {
        int max = maxHeat(weapon, weaponState);
        if (max <= 0) {
            return 0f;
        }
        return Math.min(currentHeat(weapon, weaponState) / (float) max, 1.5f);
    }

    public static boolean hasHeat(RVP_WeaponBase weapon, HeatState weaponState) {
        return maxHeat(weapon, weaponState) > 0;
    }

    public static boolean isOverheated(RVP_WeaponBase weapon, HeatState weaponState) {
        int max = maxHeat(weapon, weaponState);
        return max > 0 && currentHeat(weapon, weaponState) >= max;
    }

    private static HeatSpec resolveSpec(RVP_WeaponBase weapon) {
        RVP_VehicleWeaponHeatConfigCache.Resolved vehicleResolved = RVP_VehicleWeaponHeatConfigCache.resolve(weapon);
        if (vehicleResolved != null) {
            HeatState state = VEHICLE_HEAT
                    .computeIfAbsent(weapon.getVehicle(), ignored -> new HashMap<>())
                    .computeIfAbsent(vehicleResolved.key(), ignored -> new HeatState());
            RVP_VehicleWeaponHeatConfig config = vehicleResolved.config();
            return new HeatSpec(state, config.heatCount(), config.maxHeatCount(), config.overheatExtraHeat());
        }

        RVP_FireData fire = weapon.getData().getFireData();
        return new HeatSpec(weapon.getLocalHeatState(), fire.getHeatCount(), fire.getMaxHeatCount(), fire.getOverheatExtraHeat());
    }

    private static void tickState(HeatState state, int maxHeatCount, int tickCount) {
        if (state.lastTick == Integer.MIN_VALUE) {
            state.lastTick = tickCount;
            return;
        }
        int elapsed = Math.max(0, tickCount - state.lastTick);
        state.lastTick = tickCount;
        for (int i = 0; i < elapsed && state.currentHeat > 0; i++) {
            if (state.currentHeat < maxHeatCount) {
                state.cooldownSpeed++;
            }
            state.currentHeat -= state.cooldownSpeed / 20 + 1;
            if (state.currentHeat < 0) {
                state.currentHeat = 0;
            }
        }
    }

    public static final class HeatState {
        private int currentHeat;
        private int cooldownSpeed = 1;
        private int lastTick = Integer.MIN_VALUE;
    }

    private record HeatSpec(
            HeatState state,
            int heatCount,
            int maxHeatCount,
            int overheatExtraHeat
    ) {
        boolean enabled() {
            return maxHeatCount > 0;
        }
    }
}
