package org.ywzj.rvp.entity.gunner.behavior.runtime;

import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.entity.gunner.behavior.config.RVP_GunnerBehaviorType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * schema v2 内建行为注册表。
 *
 * <p>注册表只接受稳定资源 ID，不接收 Java 类名。重复注册会立即失败，防止加载顺序覆盖类型定义。</p>
 */
public final class RVP_GunnerBehaviorRegistry {
    /** 全局只读注册表。 */
    public static final RVP_GunnerBehaviorRegistry INSTANCE = createBuiltins();

    /** 类型 ID 到元数据，保持注册顺序便于诊断。 */
    private final Map<ResourceLocation, RVP_GunnerBehaviorType> types;

    private RVP_GunnerBehaviorRegistry(Map<ResourceLocation, RVP_GunnerBehaviorType> types) {
        this.types = Map.copyOf(types);
    }

    /** 查找已注册行为类型；未知类型返回 null，由编译器补充资源路径。 */
    public RVP_GunnerBehaviorType get(ResourceLocation id) {
        return types.get(id);
    }

    /** 返回全部已注册类型的不可变视图。 */
    public Map<ResourceLocation, RVP_GunnerBehaviorType> types() {
        return types;
    }

    private static RVP_GunnerBehaviorRegistry createBuiltins() {
        Map<ResourceLocation, RVP_GunnerBehaviorType> entries = new LinkedHashMap<>();
        register(entries, "ciws_targeting", Set.of("scan_interval_tick", "target_cooldown_tick"), 850);
        register(entries, "primary_targeting", Set.of("target_types", "gps_prefer_farthest", "search_radius",
                "scan_interval_tick", "engagement_net_cooldown_tick"), 500);
        register(entries, "driver_supply", Set.of(), 100);
        register(entries, "weapon_countermeasure", Set.of("range", "cooldown_tick"), 800);
        register(entries, "rvp_countermeasure", Set.of("scan_interval_tick", "cooldown_tick",
                "missile_threat_range", "radar_lock_threat_range"), 850);
        register(entries, "active_ecm", Set.of("threat_range"), 850);
        register(entries, "smoke_evasion", Set.of("scan_interval_tick", "hold_tick", "look_radius"), 820);
        register(entries, "ownship_radar", Set.of(), 500);
        register(entries, "external_radar", Set.of(), 510);
        register(entries, "guided_weapon_support", Set.of(), 520);
        register(entries, "sead_revenge", Set.of("threat_scan_interval_tick", "radar_lock_range",
                "fly_away_tick", "reversal_tick", "lock_fire_tick", "timeout_tick", "cooldown_tick"), 950);
        register(entries, "fixed_wing_combat_flight", Set.of("cruise_altitude_min", "cruise_altitude_max",
                "combat_radius_min", "combat_radius_max", "attack_phase_tick", "disengage_phase_tick",
                "initial_disengage_tick_min", "initial_disengage_tick_max"), 500);
        register(entries, "rotary_wing_combat_flight", Set.of("cruise_altitude_min", "cruise_altitude_max",
                "attack_phase_tick", "disengage_phase_tick", "initial_disengage_tick_min",
                "initial_disengage_tick_max"), 500);
        register(entries, "launcher_positioning", Set.of("stop_distance"), 500);
        register(entries, "stuck_recovery", Set.of("check_interval_tick", "stuck_distance", "recovery_tick"), 800);
        register(entries, "ground_engagement_move", Set.of("stop_distance", "hold_tick", "evade_tick_min",
                "evade_tick_max", "evade_yaw_deg"), 500);
        register(entries, "ground_patrol", Set.of("big_turn_interval_tick_min", "big_turn_interval_tick_max",
                "big_turn_angle_deg_min", "big_turn_angle_deg_max", "big_turn_duration_tick"), 200);
        register(entries, "weapon_engagement", Set.of("fire_window_deg", "lead_scale", "burst_fire_tick",
                "burst_rest_tick", "guided_weapon_cooldown_tick", "ciws_target_cooldown_tick"), 500);
        return new RVP_GunnerBehaviorRegistry(entries);
    }

    private static void register(Map<ResourceLocation, RVP_GunnerBehaviorType> entries, String path,
                                 Set<String> keys, int defaultPriority) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("rvp", path);
        RVP_GunnerBehaviorType previous = entries.putIfAbsent(id,
                new RVP_GunnerBehaviorType(id, keys, defaultPriority, 0, 1000));
        if (previous != null) throw new IllegalStateException("重复注册 Gunner 行为类型: " + id);
    }
}
