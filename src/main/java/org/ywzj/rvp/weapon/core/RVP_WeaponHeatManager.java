package org.ywzj.rvp.weapon.core;

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
        tickState(spec.state(), weapon.getVehicle().tickCount);
    }

    public static boolean canShoot(RVP_WeaponBase weapon, HeatState weaponState) {
        HeatSpec spec = resolveSpec(weapon);
        if (!spec.enabled()) {
            return true;
        }
        HeatState state = spec.state();
        tickState(state, weapon.getVehicle().tickCount);
        return state.currentHeat < spec.maxHeatCount();
    }

    public static void onShotFired(RVP_WeaponBase weapon, HeatState weaponState) {
        HeatSpec spec = resolveSpec(weapon);
        if (!spec.enabled()) {
            return;
        }
        HeatState state = spec.state();
        tickState(state, weapon.getVehicle().tickCount);
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
        // 读取时按车辆 tickCount 推进冷却：恒定速率下同一游戏 tick 内多次读取 elapsed=0，
        // 不会加速冷却。保证客户端 HUD 在没有射击事件驱动时也能实时反映冷却进度。
        tickState(state, weapon.getVehicle().tickCount);
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

    private static void tickState(HeatState state, int tickCount) {
        if (state.lastTick == Integer.MIN_VALUE) {
            state.lastTick = tickCount;
            return;
        }
        int elapsed = Math.max(0, tickCount - state.lastTick);
        state.lastTick = tickCount;
        if (elapsed > 0) {
            // 恒定冷却速率：每 tick 固定减 1，避免旧算法（cooldownSpeed 累积加速）导致越冷越快。
            state.currentHeat = Math.max(0, state.currentHeat - elapsed);
        }
    }

    public static final class HeatState {
        private int currentHeat;
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
