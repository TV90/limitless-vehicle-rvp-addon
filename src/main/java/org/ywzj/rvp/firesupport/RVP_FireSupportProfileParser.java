package org.ywzj.rvp.firesupport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 当前 schema 的严格、非迁移 profile 解析器。 */
public final class RVP_FireSupportProfileParser {
    /** 当前且唯一接受的 schema 版本。 */ public static final int SCHEMA_VERSION = 1;
    /** 单任务弹数不可配置的保险上限。 */ public static final int ABSOLUTE_MAX_ROUNDS = 512;
    /** 全局任务数不可配置的保险上限。 */ public static final int ABSOLUTE_MAX_GLOBAL_MISSIONS = 64;
    /** 动态参数数不可配置的保险上限。 */ public static final int ABSOLUTE_MAX_PARAMETERS = 32;
    /** 目标距离不可配置的保险上限，单位格。 */ public static final double ABSOLUTE_MAX_TARGET_DISTANCE = 16384.0;

    private RVP_FireSupportProfileParser() {}

    /**
     * 解析一整个候选快照。任一文件失败即抛出，调用方必须保留旧快照。
     */
    public static Map<ResourceLocation, RVP_FireSupportProfile> parseAll(
            Map<ResourceLocation, JsonElement> candidates, RVP_FireSupportWeaponResolver weaponResolver) {
        RVP_FireSupportProblemCollector problems = new RVP_FireSupportProblemCollector();
        Map<ResourceLocation, RVP_FireSupportProfile> result = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : candidates.entrySet()) {
            String path = entry.getKey().toString();
            if (!entry.getValue().isJsonObject()) {
                problems.add(path, "profile 根必须是对象");
                continue;
            }
            result.put(entry.getKey(), parseProfile(entry.getValue().getAsJsonObject(), weaponResolver, problems, path));
        }
        problems.throwIfAny();
        return Map.copyOf(result);
    }

    private static RVP_FireSupportProfile parseProfile(JsonObject root, RVP_FireSupportWeaponResolver resolver,
                                                        RVP_FireSupportProblemCollector problems, String path) {
        RVP_FireSupportJson.keys(root, Set.of("schema_version", "display", "holder_policy", "call_stage",
                "strike_stage", "limits", "munitions", "fire_modes", "patterns"), path, problems);
        int schema = RVP_FireSupportJson.integer(root, "schema_version", -1, path, problems);
        if (schema != SCHEMA_VERSION) problems.add(path + ".schema_version", "只接受当前版本 1");

        JsonObject display = RVP_FireSupportJson.object(root, "display", path, problems, true);
        RVP_FireSupportJson.keys(display, Set.of("translation_key"), path + ".display", problems);
        String translation = RVP_FireSupportJson.string(display, "translation_key", path + ".display", problems);

        RVP_FireSupportProfile.HolderPolicy holder = parseHolder(root, problems, path);
        RVP_FireSupportProfile.CallStage call = parseCall(root, problems, path);
        RVP_FireSupportProfile.StrikeStage strike = parseStrike(root, problems, path);
        RVP_FireSupportProfile.Limits limits = parseLimits(root, problems, path);
        Map<String, RVP_FireSupportProfile.Munition> munitions = parseMunitions(root, resolver, limits, problems, path);
        Map<String, RVP_FireSupportProfile.FireMode> modes = parseModes(root, problems, path);
        Map<String, RVP_FireSupportProfile.PatternPreset> patterns = parsePatterns(root, limits, problems, path);
        return new RVP_FireSupportProfile(schema, translation, holder, call, strike, limits, munitions, modes, patterns);
    }

    private static RVP_FireSupportProfile.HolderPolicy parseHolder(JsonObject root,
            RVP_FireSupportProblemCollector problems, String path) {
        JsonObject object = RVP_FireSupportJson.object(root, "holder_policy", path, problems, true);
        String p = path + ".holder_policy";
        RVP_FireSupportJson.keys(object, Set.of("required_item", "allowed_hands"), p, problems);
        ResourceLocation item = RVP_FireSupportJson.resource(RVP_FireSupportJson.string(object, "required_item", p, problems), p + ".required_item", problems);
        Set<String> hands = new LinkedHashSet<>();
        JsonElement rawHands = object.get("allowed_hands");
        if (rawHands == null) hands.addAll(List.of("main", "off"));
        else if (!rawHands.isJsonArray()) problems.add(p + ".allowed_hands", "必须是数组");
        else for (JsonElement element : rawHands.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || !(element.getAsString().equals("main") || element.getAsString().equals("off"))) {
                problems.add(p + ".allowed_hands", "只允许 main/off 字符串");
            } else if (!hands.add(element.getAsString())) problems.add(p + ".allowed_hands", "手不能重复");
        }
        if (hands.isEmpty()) problems.add(p + ".allowed_hands", "至少允许一只手");
        return new RVP_FireSupportProfile.HolderPolicy(item, hands);
    }

    private static RVP_FireSupportProfile.CallStage parseCall(JsonObject root,
            RVP_FireSupportProblemCollector problems, String path) {
        JsonObject o = RVP_FireSupportJson.object(root, "call_stage", path, problems, false);
        String p = path + ".call_stage";
        RVP_FireSupportJson.keys(o, Set.of("base_duration_ticks", "cancel_on_player_death",
                "cancel_on_terminal_lost", "cancel_on_disconnect"), p, problems);
        int duration = RVP_FireSupportJson.integer(o, "base_duration_ticks", 800, p, problems);
        boolean death = RVP_FireSupportJson.bool(o, "cancel_on_player_death", true, p, problems);
        boolean lost = RVP_FireSupportJson.bool(o, "cancel_on_terminal_lost", true, p, problems);
        boolean disconnect = RVP_FireSupportJson.bool(o, "cancel_on_disconnect", true, p, problems);
        if (duration <= 0 || duration > 72000) problems.add(p + ".base_duration_ticks", "必须在 [1, 72000] 内");
        if (!death) problems.add(p + ".cancel_on_player_death", "首版必须为 true");
        if (!lost) problems.add(p + ".cancel_on_terminal_lost", "首版必须为 true");
        return new RVP_FireSupportProfile.CallStage(duration, death, lost, disconnect);
    }

    private static RVP_FireSupportProfile.StrikeStage parseStrike(JsonObject root,
            RVP_FireSupportProblemCollector problems, String path) {
        JsonObject o = RVP_FireSupportJson.object(root, "strike_stage", path, problems, false);
        String p = path + ".strike_stage";
        RVP_FireSupportJson.keys(o, Set.of("cease_fire_delay_ticks"), p, problems);
        int delay = RVP_FireSupportJson.integer(o, "cease_fire_delay_ticks", 80, p, problems);
        if (delay < 0 || delay > 72000) problems.add(p + ".cease_fire_delay_ticks", "必须在 [0, 72000] 内");
        return new RVP_FireSupportProfile.StrikeStage(delay);
    }

    private static RVP_FireSupportProfile.Limits parseLimits(JsonObject root,
            RVP_FireSupportProblemCollector problems, String path) {
        JsonObject o = RVP_FireSupportJson.object(root, "limits", path, problems, true);
        String p = path + ".limits";
        RVP_FireSupportJson.keys(o, Set.of("min_target_distance_m", "max_target_distance_m", "max_rounds_per_mission",
                "max_active_missions_per_player", "max_active_missions_global", "request_cooldown_ticks",
                "max_mission_duration_ticks", "max_loaded_chunks_per_mission", "max_parameter_count"), p, problems);
        double min = RVP_FireSupportJson.number(o, "min_target_distance_m", 0, p, problems);
        double max = RVP_FireSupportJson.number(o, "max_target_distance_m", 2048, p, problems);
        int rounds = RVP_FireSupportJson.integer(o, "max_rounds_per_mission", 96, p, problems);
        int perPlayer = RVP_FireSupportJson.integer(o, "max_active_missions_per_player", 1, p, problems);
        int global = RVP_FireSupportJson.integer(o, "max_active_missions_global", 16, p, problems);
        int cooldown = RVP_FireSupportJson.integer(o, "request_cooldown_ticks", 100, p, problems);
        int duration = RVP_FireSupportJson.integer(o, "max_mission_duration_ticks", 2400, p, problems);
        int chunks = RVP_FireSupportJson.integer(o, "max_loaded_chunks_per_mission", 8, p, problems);
        int parameters = RVP_FireSupportJson.integer(o, "max_parameter_count", 16, p, problems);
        if (min < 0 || max < min || max > ABSOLUTE_MAX_TARGET_DISTANCE) problems.add(p, "目标距离范围非法或超过 16384 格保险上限");
        if (rounds < 1 || rounds > ABSOLUTE_MAX_ROUNDS) problems.add(p + ".max_rounds_per_mission", "必须在 [1, 512] 内");
        if (perPlayer < 1 || perPlayer > global) problems.add(p + ".max_active_missions_per_player", "必须为正且不大于全局上限");
        if (global < 1 || global > ABSOLUTE_MAX_GLOBAL_MISSIONS) problems.add(p + ".max_active_missions_global", "必须在 [1, 64] 内");
        if (cooldown < 0 || duration < 1 || chunks < 1) problems.add(p, "冷却不得为负，任务时长和区块上限必须为正");
        if (parameters < 1 || parameters > ABSOLUTE_MAX_PARAMETERS) problems.add(p + ".max_parameter_count", "必须在 [1, 32] 内");
        return new RVP_FireSupportProfile.Limits(min, max, rounds, perPlayer, global, cooldown, duration, chunks, parameters);
    }

    private static Map<String, RVP_FireSupportProfile.Munition> parseMunitions(JsonObject root,
            RVP_FireSupportWeaponResolver resolver, RVP_FireSupportProfile.Limits limits,
            RVP_FireSupportProblemCollector problems, String path) {
        JsonArray array = RVP_FireSupportJson.array(root, "munitions", path, problems);
        if (array.size() < 1 || array.size() > 64) problems.add(path + ".munitions", "数量必须在 [1, 64] 内");
        Map<String, RVP_FireSupportProfile.Munition> out = new LinkedHashMap<>();
        for (int i = 0; i < array.size(); i++) {
            String p = path + ".munitions[" + i + "]";
            JsonObject o = asObject(array.get(i), p, problems);
            RVP_FireSupportJson.keys(o, Set.of("id", "translation_key", "weapon", "rounds_per_unit",
                    "registration_phase_enabled", "delivery"), p, problems);
            String id = localId(o, p, problems);
            String key = RVP_FireSupportJson.string(o, "translation_key", p, problems);
            ResourceLocation weapon = RVP_FireSupportJson.resource(RVP_FireSupportJson.string(o, "weapon", p, problems), p + ".weapon", problems);
            int rounds = RVP_FireSupportJson.integer(o, "rounds_per_unit", -1, p, problems);
            boolean registrationPhaseEnabled = RVP_FireSupportJson.bool(
                    o, "registration_phase_enabled", true, p, problems);
            if (rounds < 1 || rounds > ABSOLUTE_MAX_ROUNDS) problems.add(p + ".rounds_per_unit", "必须在 [1, 512] 内");
            JsonObject delivery = RVP_FireSupportJson.object(o, "delivery", p, problems, true);
            RVP_FireSupportJson.keys(delivery, Set.of("type", "data"), p + ".delivery", problems);
            ResourceLocation type = RVP_FireSupportJson.resource(RVP_FireSupportJson.string(delivery, "type", p + ".delivery", problems), p + ".delivery.type", problems);
            RVP_FireSupportDeliveryFactory factory = RVP_FireSupportDeliveryTypes.get(type);
            Object data = null;
            if (factory == null) problems.add(p + ".delivery.type", "未知投送工厂 " + type);
            else {
                data = factory.parse(RVP_FireSupportJson.object(delivery, "data", p + ".delivery", problems, true), problems, p + ".delivery.data");
                // 调用本体实际武器索引解析器，禁止仅按资源路径猜测武器类型。
                RVP_FireSupportResolvedWeapon weaponData = resolver.resolve(weapon);
                if (weaponData == null) problems.add(p + ".weapon", "武器不存在或不是 RVP_WeaponData: " + weapon);
                else {
                    factory.validateWeapon(weapon, weaponData, problems, p + ".weapon");
                    // 调用本项目炮火预算器：按 profile 最大顶层弹数审计生命期、毁伤和最坏实体展开。
                    RVP_FireSupportWeaponBudget.validate(weaponData, limits.maxRoundsPerMission(),
                            problems, p + ".weapon");
                }
            }
            putUnique(out, id, new RVP_FireSupportProfile.Munition(
                    id, key, weapon, rounds, registrationPhaseEnabled, type, data), p, problems);
        }
        return Map.copyOf(out);
    }

    private static Map<String, RVP_FireSupportProfile.FireMode> parseModes(JsonObject root,
            RVP_FireSupportProblemCollector problems, String path) {
        JsonArray array = RVP_FireSupportJson.array(root, "fire_modes", path, problems);
        if (array.size() < 1 || array.size() > 32) problems.add(path + ".fire_modes", "数量必须在 [1, 32] 内");
        Map<String, RVP_FireSupportProfile.FireMode> out = new LinkedHashMap<>();
        for (int i = 0; i < array.size(); i++) {
            String p = path + ".fire_modes[" + i + "]";
            JsonObject o = asObject(array.get(i), p, problems);
            RVP_FireSupportJson.keys(o, Set.of("id", "translation_key", "call_duration_multiplier", "dispersion_multiplier", "phases"), p, problems);
            String id = localId(o, p, problems);
            String key = RVP_FireSupportJson.string(o, "translation_key", p, problems);
            double call = RVP_FireSupportJson.number(o, "call_duration_multiplier", 1, p, problems);
            double dispersion = RVP_FireSupportJson.number(o, "dispersion_multiplier", 1, p, problems);
            if (call <= 0 || call > 100) problems.add(p + ".call_duration_multiplier", "必须在 (0, 100] 内");
            if (dispersion <= 0 || dispersion > 100) problems.add(p + ".dispersion_multiplier", "必须在 (0, 100] 内");
            List<RVP_FireSupportProfile.Phase> phases = parsePhases(o, problems, p);
            putUnique(out, id, new RVP_FireSupportProfile.FireMode(id, key, call, dispersion, phases), p, problems);
        }
        return Map.copyOf(out);
    }

    private static List<RVP_FireSupportProfile.Phase> parsePhases(JsonObject mode,
            RVP_FireSupportProblemCollector problems, String path) {
        JsonArray array = RVP_FireSupportJson.array(mode, "phases", path, problems);
        if (array.size() < 1 || array.size() > 16) problems.add(path + ".phases", "数量必须在 [1, 16] 内");
        List<RVP_FireSupportProfile.Phase> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String p = path + ".phases[" + i + "]";
            JsonObject o = asObject(array.get(i), p, problems);
            RVP_FireSupportJson.keys(o, Set.of("id", "translation_key", "registration_phase",
                    "start_delay_ticks", "rounds", "interval_ticks", "duration_ticks"), p, problems);
            String id = localId(o, p, problems);
            if (!ids.add(id)) problems.add(p + ".id", "阶段 ID 重复");
            String key = RVP_FireSupportJson.string(o, "translation_key", p, problems);
            boolean registrationPhase = RVP_FireSupportJson.bool(o, "registration_phase", false, p, problems);
            int start = RVP_FireSupportJson.integer(o, "start_delay_ticks", 0, p, problems);
            if (start < 0) problems.add(p + ".start_delay_ticks", "不得为负");
            RVP_FireSupportProfile.RoundRule rule = parseRoundRule(RVP_FireSupportJson.object(o, "rounds", p, problems, true), problems, p + ".rounds");
            Integer interval = o.has("interval_ticks") ? RVP_FireSupportJson.integer(o, "interval_ticks", -1, p, problems) : null;
            Integer duration = o.has("duration_ticks") ? RVP_FireSupportJson.integer(o, "duration_ticks", -1, p, problems) : null;
            if ((interval == null) == (duration == null)) problems.add(p, "interval_ticks 与 duration_ticks 必须且只能配置一个");
            if (interval != null && interval <= 0) problems.add(p + ".interval_ticks", "必须为正");
            if (duration != null && duration < 0) problems.add(p + ".duration_ticks", "不得为负");
            out.add(new RVP_FireSupportProfile.Phase(
                    id, key, registrationPhase, start, rule, interval, duration));
        }
        return List.copyOf(out);
    }

    private static RVP_FireSupportProfile.RoundRule parseRoundRule(JsonObject o,
            RVP_FireSupportProblemCollector problems, String path) {
        RVP_FireSupportJson.keys(o, Set.of("base_multiplier", "rounding", "random_min", "random_max", "fixed"), path, problems);
        boolean base = o.has("base_multiplier"), random = o.has("random_min") || o.has("random_max"), fixed = o.has("fixed");
        if ((base ? 1 : 0) + (random ? 1 : 0) + (fixed ? 1 : 0) != 1) problems.add(path, "基数倍率、随机区间、固定弹数必须且只能选择一种");
        Double multiplier = base ? RVP_FireSupportJson.number(o, "base_multiplier", -1, path, problems) : null;
        if (multiplier != null && multiplier <= 0) problems.add(path + ".base_multiplier", "必须为正");
        if (base && (!o.has("rounding") || !"ceil".equals(RVP_FireSupportJson.string(o, "rounding", path, problems)))) problems.add(path + ".rounding", "首版只允许 ceil");
        if (!base && o.has("rounding")) problems.add(path + ".rounding", "仅基数倍率规则可配置 rounding");
        Integer min = random ? RVP_FireSupportJson.integer(o, "random_min", -1, path, problems) : null;
        Integer max = random ? RVP_FireSupportJson.integer(o, "random_max", -1, path, problems) : null;
        if (random && (!o.has("random_min") || !o.has("random_max") || min < 1 || max < min)) problems.add(path, "随机闭区间必须完整且为正");
        Integer fixedCount = fixed ? RVP_FireSupportJson.integer(o, "fixed", -1, path, problems) : null;
        if (fixedCount != null && fixedCount < 1) problems.add(path + ".fixed", "必须为正");
        return new RVP_FireSupportProfile.RoundRule(multiplier, min, max, fixedCount);
    }

    private static Map<String, RVP_FireSupportProfile.PatternPreset> parsePatterns(JsonObject root,
            RVP_FireSupportProfile.Limits limits, RVP_FireSupportProblemCollector problems, String path) {
        JsonArray array = RVP_FireSupportJson.array(root, "patterns", path, problems);
        if (array.size() < 1 || array.size() > 32) problems.add(path + ".patterns", "数量必须在 [1, 32] 内");
        Map<String, RVP_FireSupportProfile.PatternPreset> out = new LinkedHashMap<>();
        for (int i = 0; i < array.size(); i++) {
            String p = path + ".patterns[" + i + "]";
            JsonObject o = asObject(array.get(i), p, problems);
            RVP_FireSupportJson.keys(o, Set.of("id", "translation_key", "type", "data"), p, problems);
            String id = localId(o, p, problems);
            String key = RVP_FireSupportJson.string(o, "translation_key", p, problems);
            ResourceLocation type = RVP_FireSupportJson.resource(RVP_FireSupportJson.string(o, "type", p, problems), p + ".type", problems);
            JsonObject data = RVP_FireSupportJson.object(o, "data", p, problems, true);
            RVP_FireSupportPatternFactory factory = RVP_FireSupportPatternTypes.get(type);
            RVP_FireSupportPattern pattern = null;
            if (factory == null) problems.add(p + ".type", "未知几何工厂 " + type);
            else pattern = factory.parseAndCreate(data, problems, p + ".data");
            Map<String, RVP_FireSupportProfile.ParameterSpec> specs = parseParameterSpecs(
                    RVP_FireSupportJson.object(data, "parameters", p + ".data", problems, true), limits, problems, p + ".data.parameters");
            putUnique(out, id, new RVP_FireSupportProfile.PatternPreset(id, key, type, specs, pattern), p, problems);
        }
        return Map.copyOf(out);
    }

    private static Map<String, RVP_FireSupportProfile.ParameterSpec> parseParameterSpecs(JsonObject o,
            RVP_FireSupportProfile.Limits limits, RVP_FireSupportProblemCollector problems, String path) {
        if (o.size() > limits.maxParameterCount() || o.size() > ABSOLUTE_MAX_PARAMETERS) problems.add(path, "参数数超过 profile 或绝对上限");
        Map<String, RVP_FireSupportProfile.ParameterSpec> out = new LinkedHashMap<>();
        o.entrySet().forEach(entry -> {
            String p = path + "." + entry.getKey();
            if (!RVP_FireSupportJson.simpleId(entry.getKey())) problems.add(p, "参数名不是合法小写标识");
            JsonObject spec = asObject(entry.getValue(), p, problems);
            RVP_FireSupportJson.keys(spec, Set.of("type", "default", "min", "max", "step", "unit"), p, problems);
            String type = RVP_FireSupportJson.string(spec, "type", p, problems);
            if (!"double".equals(type)) problems.add(p + ".type", "首版只允许 double");
            double min = RVP_FireSupportJson.number(spec, "min", Double.NaN, p, problems);
            double max = RVP_FireSupportJson.number(spec, "max", Double.NaN, p, problems);
            double def = RVP_FireSupportJson.number(spec, "default", Double.NaN, p, problems);
            double step = RVP_FireSupportJson.number(spec, "step", Double.NaN, p, problems);
            String unit = RVP_FireSupportJson.string(spec, "unit", p, problems);
            if (!(min > 0 && max >= min && def >= min && def <= max && step > 0 && step <= max - min + 1e-9)) problems.add(p, "必须满足 0 < min <= default <= max 且 step > 0");
            out.put(entry.getKey(), new RVP_FireSupportProfile.ParameterSpec(def, min, max, step, unit));
        });
        return Map.copyOf(out);
    }

    private static JsonObject asObject(JsonElement value, String path, RVP_FireSupportProblemCollector problems) {
        if (value == null || !value.isJsonObject()) {
            problems.add(path, "必须是对象");
            return new JsonObject();
        }
        return value.getAsJsonObject();
    }

    private static String localId(JsonObject o, String path, RVP_FireSupportProblemCollector problems) {
        String id = RVP_FireSupportJson.string(o, "id", path, problems);
        if (!RVP_FireSupportJson.simpleId(id)) problems.add(path + ".id", "必须是小写标识");
        return id;
    }

    private static <T> void putUnique(Map<String, T> map, String id, T value, String path,
                                      RVP_FireSupportProblemCollector problems) {
        if (map.putIfAbsent(id, value) != null) problems.add(path + ".id", "ID 重复: " + id);
    }
}
