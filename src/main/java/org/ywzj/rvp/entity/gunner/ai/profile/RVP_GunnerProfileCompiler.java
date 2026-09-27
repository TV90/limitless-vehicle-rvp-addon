package org.ywzj.rvp.entity.gunner.ai.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_IGunnerBehavior;
import org.ywzj.rvp.entity.gunner.behavior.builtin.RVP_BuiltinGunnerBehaviors;
import org.ywzj.rvp.entity.gunner.behavior.config.RVP_GunnerBehaviorPlan;
import org.ywzj.rvp.entity.gunner.behavior.config.RVP_GunnerBehaviorType;
import org.ywzj.rvp.entity.gunner.behavior.runtime.RVP_GunnerBehaviorRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 将当前 schema v2 JSON 严格编译为不可变 Profile 与行为计划。 */
public final class RVP_GunnerProfileCompiler {
    /** 当前唯一接受的 Profile schema。 */
    public static final int SCHEMA_VERSION = 2;
    /** Profile 根对象允许的字段集。 */
    private static final Set<String> ROOT_KEYS = Set.of("schema_version", "name", "faction", "behaviors");
    /** 单个行为实例允许的字段集。 */
    private static final Set<String> SPEC_KEYS = Set.of("id", "type", "priority", "config");

    private RVP_GunnerProfileCompiler() {}

    public static GunnerProfile compile(ResourceLocation profileId, JsonElement source) {
        String path = profileId + "";
        JsonObject root = object(source, path);
        rejectUnknown(root, ROOT_KEYS, path);
        int schema = integer(root, "schema_version", path);
        if (schema != SCHEMA_VERSION) fail(path + ".schema_version", "只接受当前版本 2");
        String name = optionalString(root, "name", profileId.getPath(), path);
        String factionName = optionalString(root, "faction", "friendly", path);
        if (!Set.of("friendly", "enemy", "team").contains(factionName)) {
            fail(path + ".faction", "只允许 friendly、enemy 或 team");
        }
        RVP_EnumGunnerFaction faction = RVP_EnumGunnerFaction.parse(factionName);
        JsonArray behaviors = array(root, "behaviors", path);
        int ciwsTargetCooldown = inheritedInteger(behaviors, "rvp:ciws_targeting",
                "target_cooldown_tick", 100);
        boolean gpsPreferFarthest = inheritedBoolean(behaviors, "rvp:primary_targeting",
                "gps_prefer_farthest", true);
        int engagementWindowTick = inheritedInteger(behaviors, "rvp:primary_targeting",
                "engagement_net_cooldown_tick", 100);

        GunnerProfile base = new GunnerProfile(name, faction, RVP_GunnerBehaviorPlan.empty());
        List<RVP_IGunnerBehavior> compiled = new ArrayList<>();
        Set<String> instanceIds = new HashSet<>();
        for (int index = 0; index < behaviors.size(); index++) {
            String specPath = path + ".behaviors[" + index + "]";
            JsonObject spec = object(behaviors.get(index), specPath);
            rejectUnknown(spec, SPEC_KEYS, specPath);
            String instanceId = string(spec, "id", specPath);
            if (!instanceId.matches("[a-z0-9_.-]+")) fail(specPath + ".id", "只能包含小写字母、数字、_、-、.");
            if (!instanceIds.add(instanceId)) fail(specPath + ".id", "行为实例 ID 重复: " + instanceId);
            ResourceLocation typeId = ResourceLocation.tryParse(string(spec, "type", specPath));
            if (typeId == null) fail(specPath + ".type", "不是合法资源 ID");
            if ("minecraft".equals(typeId.getNamespace())) {
                typeId = ResourceLocation.fromNamespaceAndPath("rvp", typeId.getPath());
            }
            RVP_GunnerBehaviorType type = RVP_GunnerBehaviorRegistry.INSTANCE.get(typeId);
            if (type == null) fail(specPath + ".type", "未知行为类型: " + typeId);
            int priority = spec.has("priority") ? integer(spec, "priority", specPath) : type.defaultPriority();
            if (priority < type.minPriority() || priority > type.maxPriority()) {
                fail(specPath + ".priority", "必须在 " + type.minPriority() + ".." + type.maxPriority() + " 范围内");
            }
            JsonObject config = spec.has("config") ? object(spec.get("config"), specPath + ".config") : new JsonObject();
            rejectUnknown(config, type.configKeys(), specPath + ".config");
            GunnerProfile view = configure(base.behaviorView(), typeId.getPath(), config, specPath + ".config",
                    ciwsTargetCooldown, gpsPreferFarthest, engagementWindowTick);
            // 调用内建行为工厂：实例只持有自身强类型参数视图，不共享可变 JSON。
            compiled.add(RVP_BuiltinGunnerBehaviors.create(typeId.getPath(), instanceId, priority, view));
        }
        return new GunnerProfile(name, faction, new RVP_GunnerBehaviorPlan(compiled));
    }

    /** 对每种行为显式读取其强类型字段；未列出的 JSON 键已在进入此方法前拒绝。 */
    private static GunnerProfile configure(GunnerProfile view, String type, JsonObject config, String path,
                                           int inheritedCiwsCooldown, boolean inheritedGpsPreference,
                                           int inheritedEngagementWindow) {
        validateOrderedRanges(type, config, path);
        return switch (type) {
            case "ciws_targeting" -> view.ciws(
                    integer(config, "scan_interval_tick", 1, 1, 1200, path),
                    integer(config, "target_cooldown_tick", 100, 0, 72000, path));
            case "primary_targeting" -> view.targeting(
                    stringList(config, "target_types", List.of("rvp:missile", "vehicle", "monster", "player"), path),
                    bool(config, "gps_prefer_farthest", true, path),
                    decimal(config, "search_radius", 96.0, 1.0, 4096.0, path),
                    integer(config, "scan_interval_tick", 10, 1, 1200, path),
                    integer(config, "engagement_net_cooldown_tick", 100, 0, 1200, path));
            case "weapon_engagement" -> view
                    .targeting(List.of("rvp:missile", "vehicle", "monster", "player"),
                            inheritedGpsPreference, 96.0, 10, inheritedEngagementWindow)
                    .weapon(
                    (float) decimal(config, "fire_window_deg", 6.0, 0.1, 180.0, path),
                    decimal(config, "lead_scale", 1.0, 0.0, 10.0, path),
                    integer(config, "burst_fire_tick", 6, 0, 1200, path),
                    integer(config, "burst_rest_tick", 10, 0, 1200, path),
                    integer(config, "guided_weapon_cooldown_tick", 100, 0, 72000, path),
                    integer(config, "ciws_target_cooldown_tick", inheritedCiwsCooldown, 0, 72000, path));
            case "weapon_countermeasure" -> view.weaponCountermeasure(
                    decimal(config, "range", 36.0, 0.0, 4096.0, path),
                    integer(config, "cooldown_tick", 80, 0, 72000, path));
            case "ground_engagement_move" -> view.groundEngagement(
                    decimal(config, "stop_distance", 12.0, 0.0, 1024.0, path),
                    integer(config, "hold_tick", 100, 0, 72000, path),
                    integer(config, "evade_tick_min", 140, 0, 72000, path),
                    integer(config, "evade_tick_max", 280, 0, 72000, path),
                    (float) decimal(config, "evade_yaw_deg", 55.0, 0.0, 180.0, path));
            case "launcher_positioning" -> view.groundEngagement(
                    decimal(config, "stop_distance", 12.0, 0.0, 1024.0, path), 100, 140, 280, 55.0F);
            case "smoke_evasion" -> view.smoke(
                    integer(config, "scan_interval_tick", 10, 1, 1200, path),
                    integer(config, "hold_tick", 260, 1, 72000, path),
                    decimal(config, "look_radius", 48.0, 1.0, 4096.0, path));
            case "rvp_countermeasure" -> view.rvpCountermeasure(
                    integer(config, "scan_interval_tick", 5, 1, 1200, path),
                    integer(config, "cooldown_tick", 100, 0, 72000, path),
                    decimal(config, "missile_threat_range", 256.0, 1.0, 4096.0, path),
                    decimal(config, "radar_lock_threat_range", 1024.0, 1.0, 4096.0, path));
            case "active_ecm" -> view.activeEcm(
                    decimal(config, "threat_range", 200.0, 1.0, 4096.0, path));
            case "sead_revenge" -> view.sead(
                    integer(config, "threat_scan_interval_tick", 10, 1, 1200, path),
                    decimal(config, "radar_lock_range", 1024.0, 1.0, 4096.0, path),
                    integer(config, "fly_away_tick", 100, 1, 72000, path),
                    integer(config, "reversal_tick", 160, 1, 72000, path),
                    integer(config, "lock_fire_tick", 40, 1, 72000, path),
                    integer(config, "timeout_tick", 400, 1, 72000, path),
                    integer(config, "cooldown_tick", 400, 0, 72000, path));
            case "stuck_recovery" -> view.stuckRecovery(
                    integer(config, "check_interval_tick", 20, 5, 1200, path),
                    decimal(config, "stuck_distance", 1.0, 0.05, 128.0, path),
                    integer(config, "recovery_tick", 20, 5, 1200, path));
            case "ground_patrol" -> view.groundPatrol(
                    integer(config, "big_turn_interval_tick_min", 300, 1, 72000, path),
                    integer(config, "big_turn_interval_tick_max", 600, 1, 72000, path),
                    (float) decimal(config, "big_turn_angle_deg_min", 120.0, 0.0, 360.0, path),
                    (float) decimal(config, "big_turn_angle_deg_max", 180.0, 0.0, 360.0, path),
                    integer(config, "big_turn_duration_tick", 40, 1, 1200, path));
            case "fixed_wing_combat_flight" -> view.fixedWing(
                    decimal(config, "cruise_altitude_min", 150.0, 0.0, 4096.0, path),
                    decimal(config, "cruise_altitude_max", 500.0, 0.0, 4096.0, path),
                    decimal(config, "combat_radius_min", 40.0, 0.0, 4096.0, path),
                    decimal(config, "combat_radius_max", 550.0, 0.0, 4096.0, path),
                    integer(config, "attack_phase_tick", 200, 1, 72000, path),
                    integer(config, "disengage_phase_tick", 200, 1, 72000, path),
                    integer(config, "initial_disengage_tick_min", 300, 0, 72000, path),
                    integer(config, "initial_disengage_tick_max", 400, 0, 72000, path));
            case "rotary_wing_combat_flight" -> view.rotaryWing(
                    decimal(config, "cruise_altitude_min", 28.0, 0.0, 4096.0, path),
                    decimal(config, "cruise_altitude_max", 60.0, 0.0, 4096.0, path),
                    integer(config, "attack_phase_tick", 200, 1, 72000, path),
                    integer(config, "disengage_phase_tick", 200, 1, 72000, path),
                    integer(config, "initial_disengage_tick_min", 300, 0, 72000, path),
                    integer(config, "initial_disengage_tick_max", 400, 0, 72000, path));
            default -> view;
        };
    }

    /** 校验成对区间，不用运行时 clamp 掩盖配置错误。 */
    private static void validateOrderedRanges(String type, JsonObject config, String path) {
        switch (type) {
            case "ground_engagement_move" -> orderedIntegers(config, "evade_tick_min", 140,
                    "evade_tick_max", 280, path);
            case "ground_patrol" -> {
                orderedIntegers(config, "big_turn_interval_tick_min", 300,
                        "big_turn_interval_tick_max", 600, path);
                orderedDecimals(config, "big_turn_angle_deg_min", 120.0,
                        "big_turn_angle_deg_max", 180.0, path);
            }
            case "fixed_wing_combat_flight" -> {
                orderedDecimals(config, "cruise_altitude_min", 150.0,
                        "cruise_altitude_max", 500.0, path);
                orderedDecimals(config, "combat_radius_min", 40.0,
                        "combat_radius_max", 550.0, path);
                orderedIntegers(config, "initial_disengage_tick_min", 300,
                        "initial_disengage_tick_max", 400, path);
            }
            case "rotary_wing_combat_flight" -> {
                orderedDecimals(config, "cruise_altitude_min", 28.0,
                        "cruise_altitude_max", 60.0, path);
                orderedIntegers(config, "initial_disengage_tick_min", 300,
                        "initial_disengage_tick_max", 400, path);
            }
            default -> { }
        }
    }

    private static void orderedIntegers(JsonObject config, String minKey, int minDefault,
                                        String maxKey, int maxDefault, String path) {
        int min = config.has(minKey) ? integer(config, minKey, path) : minDefault;
        int max = config.has(maxKey) ? integer(config, maxKey, path) : maxDefault;
        if (min > max) fail(path + "." + maxKey, "不得小于 " + minKey);
    }

    private static void orderedDecimals(JsonObject config, String minKey, double minDefault,
                                         String maxKey, double maxDefault, String path) {
        double min = decimal(config, minKey, minDefault, -Double.MAX_VALUE, Double.MAX_VALUE, path);
        double max = decimal(config, maxKey, maxDefault, -Double.MAX_VALUE, Double.MAX_VALUE, path);
        if (min > max) fail(path + "." + maxKey, "不得小于 " + minKey);
    }

    /** 从指定行为的 config 读取跨行为事务需要的整数；正式严格校验仍在主编译循环执行。 */
    private static int inheritedInteger(JsonArray behaviors, String type, String key, int fallback) {
        for (JsonElement element : behaviors) {
            if (!element.isJsonObject()) continue;
            JsonObject spec = element.getAsJsonObject();
            if (!isStringPrimitive(spec.get("type")) || !type.equals(spec.get("type").getAsString())
                    || !spec.has("config") || !spec.get("config").isJsonObject()) continue;
            JsonObject config = spec.getAsJsonObject("config");
            if (config.has(key) && config.get(key).isJsonPrimitive()
                    && config.getAsJsonPrimitive(key).isNumber()) return config.get(key).getAsInt();
        }
        return fallback;
    }

    private static boolean inheritedBoolean(JsonArray behaviors, String type, String key, boolean fallback) {
        for (JsonElement element : behaviors) {
            if (!element.isJsonObject()) continue;
            JsonObject spec = element.getAsJsonObject();
            if (!isStringPrimitive(spec.get("type")) || !type.equals(spec.get("type").getAsString())
                    || !spec.has("config") || !spec.get("config").isJsonObject()) continue;
            JsonObject config = spec.getAsJsonObject("config");
            if (config.has(key) && config.get(key).isJsonPrimitive()
                    && config.getAsJsonPrimitive(key).isBoolean()) return config.get(key).getAsBoolean();
        }
        return fallback;
    }

    /** 只在预取跨行为参数时安全判定字符串，真正错误路径交由主循环报告。 */
    private static boolean isStringPrimitive(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) fail(path, "必须是对象");
        return value.getAsJsonObject();
    }
    private static JsonArray array(JsonObject root, String key, String path) {
        if (!root.has(key) || !root.get(key).isJsonArray()) fail(path + "." + key, "必须是数组");
        return root.getAsJsonArray(key);
    }
    private static String string(JsonObject root, String key, String path) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive() || !root.getAsJsonPrimitive(key).isString())
            fail(path + "." + key, "必须是字符串");
        String value = root.get(key).getAsString();
        if (value.isBlank()) fail(path + "." + key, "不得为空");
        return value;
    }
    private static String optionalString(JsonObject root, String key, String fallback, String path) {
        return root.has(key) ? string(root, key, path) : fallback;
    }
    private static int integer(JsonObject root, String key, String path) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive() || !root.getAsJsonPrimitive(key).isNumber())
            fail(path + "." + key, "必须是整数");
        double value = root.get(key).getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)) fail(path + "." + key, "必须是整数");
        return root.get(key).getAsInt();
    }
    private static int integer(JsonObject root, String key, int fallback, int min, int max, String path) {
        int value = root.has(key) ? integer(root, key, path) : fallback;
        if (value < min || value > max) fail(path + "." + key, "必须在 " + min + ".." + max + " 范围内");
        return value;
    }
    private static double decimal(JsonObject root, String key, double fallback, double min, double max, String path) {
        if (!root.has(key)) return fallback;
        if (!root.get(key).isJsonPrimitive() || !root.getAsJsonPrimitive(key).isNumber())
            fail(path + "." + key, "必须是数字");
        double value = root.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max)
            fail(path + "." + key, "必须在 " + min + ".." + max + " 范围内");
        return value;
    }
    private static boolean bool(JsonObject root, String key, boolean fallback, String path) {
        if (!root.has(key)) return fallback;
        if (!root.get(key).isJsonPrimitive() || !root.getAsJsonPrimitive(key).isBoolean())
            fail(path + "." + key, "必须是布尔值");
        return root.get(key).getAsBoolean();
    }
    private static List<String> stringList(JsonObject root, String key, List<String> fallback, String path) {
        if (!root.has(key)) return fallback;
        if (!root.get(key).isJsonArray()) fail(path + "." + key, "必须是字符串数组");
        List<String> values = new ArrayList<>();
        int index = 0;
        for (JsonElement element : root.getAsJsonArray(key)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString() || element.getAsString().isBlank())
                fail(path + "." + key + "[" + index + "]", "必须是非空字符串");
            values.add(element.getAsString());
            index++;
        }
        if (values.isEmpty()) fail(path + "." + key, "不得为空");
        return List.copyOf(values);
    }
    private static void rejectUnknown(JsonObject object, Set<String> allowed, String path) {
        for (String key : object.keySet()) if (!allowed.contains(key)) fail(path + "." + key, "未知字段");
    }
    private static void fail(String path, String message) {
        throw new IllegalArgumentException(path + ": " + message);
    }
}
