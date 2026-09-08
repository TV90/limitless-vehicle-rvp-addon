package org.ywzj.rvp.client.firesupport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 客户端只读的当前 schema profile 视图；不创建服务端 pattern 或 delivery 对象。 */
public record RVP_ClientFireSupportProfile(
        /** 服务端同步的 profile 资源 ID。 */ ResourceLocation id,
        /** profile 显示翻译键。 */ String translationKey,
        /** profile 变体物品名称翻译键。 */ String itemTranslationKey,
        /** 允许发起请求的手，值为 main/off。 */ Set<String> allowedHands,
        /** 呼叫基础时长，单位 Tick。 */ int baseCallDurationTicks,
        /** 客户端弹药方案选择表。 */ List<Munition> munitions,
        /** 客户端打击模式选择表。 */ List<FireMode> fireModes,
        /** 客户端几何预设选择表。 */ List<Pattern> patterns) {

    public RVP_ClientFireSupportProfile {
        allowedHands = Set.copyOf(allowedHands);
        munitions = List.copyOf(munitions);
        fireModes = List.copyOf(fireModes);
        patterns = List.copyOf(patterns);
    }

    /** 从阶段 C 同步的规范化 JSON 创建 UI 视图。 */
    public static RVP_ClientFireSupportProfile parse(ResourceLocation id, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.get("schema_version").getAsInt() != 3) throw new IllegalArgumentException("不支持的炮火 profile schema");
        String translation = root.getAsJsonObject("display").get("translation_key").getAsString();
        String itemTranslation = root.getAsJsonObject("item").get("translation_key").getAsString();
        JsonObject holder = root.getAsJsonObject("holder_policy");
        java.util.LinkedHashSet<String> hands = new java.util.LinkedHashSet<>();
        holder.getAsJsonArray("allowed_hands").forEach(value -> hands.add(value.getAsString()));
        int baseCall = root.getAsJsonObject("call_stage").get("base_duration_ticks").getAsInt();
        return new RVP_ClientFireSupportProfile(id, translation, itemTranslation, hands, baseCall,
                parseMunitions(root.getAsJsonArray("munitions")),
                parseModes(root.getAsJsonArray("fire_modes")),
                parsePatterns(root.getAsJsonArray("patterns")));
    }

    private static List<Munition> parseMunitions(JsonArray array) {
        List<Munition> out = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject value = element.getAsJsonObject();
            out.add(new Munition(value.get("id").getAsString(), value.get("translation_key").getAsString(),
                    value.get("rounds_per_unit").getAsInt(),
                    !value.has("registration_phase_enabled") || value.get("registration_phase_enabled").getAsBoolean()));
        }
        return out;
    }

    private static List<FireMode> parseModes(JsonArray array) {
        List<FireMode> out = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject value = element.getAsJsonObject();
            List<Phase> phases = new ArrayList<>();
            for (JsonElement phaseElement : value.getAsJsonArray("phases")) {
                JsonObject phase = phaseElement.getAsJsonObject();
                JsonObject rounds = phase.getAsJsonObject("rounds");
                int fixed = rounds.has("fixed") ? rounds.get("fixed").getAsInt() : -1;
                double multiplier = rounds.has("base_multiplier") ? rounds.get("base_multiplier").getAsDouble() : -1.0;
                int randomMin = rounds.has("random_min") ? rounds.get("random_min").getAsInt() : -1;
                int randomMax = rounds.has("random_max") ? rounds.get("random_max").getAsInt() : -1;
                Integer interval = phase.has("interval_ticks") ? phase.get("interval_ticks").getAsInt() : null;
                Integer duration = phase.has("duration_ticks") ? phase.get("duration_ticks").getAsInt() : null;
                phases.add(new Phase(phase.has("registration_phase") && phase.get("registration_phase").getAsBoolean(),
                        phase.get("start_delay_ticks").getAsInt(), fixed, multiplier,
                        randomMin, randomMax, interval, duration));
            }
            out.add(new FireMode(value.get("id").getAsString(), value.get("translation_key").getAsString(),
                    value.get("call_duration_multiplier").getAsDouble(),
                    value.get("dispersion_multiplier").getAsDouble(), phases));
        }
        return out;
    }

    private static List<Pattern> parsePatterns(JsonArray array) {
        List<Pattern> out = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject value = element.getAsJsonObject();
            Map<String, Parameter> parameters = new LinkedHashMap<>();
            JsonObject specs = value.getAsJsonObject("data").getAsJsonObject("parameters");
            for (Map.Entry<String, JsonElement> entry : specs.entrySet()) {
                JsonObject spec = entry.getValue().getAsJsonObject();
                parameters.put(entry.getKey(), new Parameter(entry.getKey(), spec.get("default").getAsDouble(),
                        spec.get("min").getAsDouble(), spec.get("max").getAsDouble(),
                        spec.get("step").getAsDouble(), spec.get("unit").getAsString()));
            }
            out.add(new Pattern(value.get("id").getAsString(), value.get("translation_key").getAsString(),
                    ResourceLocation.parse(value.get("type").getAsString()), parameters));
        }
        return out;
    }

    /** 客户端弹药方案摘要。 */
    public record Munition(
            /** profile 内弹药方案 ID。 */ String id,
            /** 弹药方案显示翻译键。 */ String translationKey,
            /** 一基数顶层弹数。 */ int roundsPerUnit,
            /** 是否执行标记为试射的阶段。 */ boolean registrationPhaseEnabled) {}

    /** 客户端数据驱动打击模式。 */
    public record FireMode(
            /** profile 内模式 ID。 */ String id,
            /** 模式显示翻译键。 */ String translationKey,
            /** 呼叫时长倍率。 */ double callDurationMultiplier,
            /** 预览几何尺寸倍率。 */ double dispersionMultiplier,
            /** 通用计时阶段。 */ List<Phase> phases) {
        public FireMode { phases = List.copyOf(phases); }
    }

    /** 客户端非权威计划预览所需的阶段摘要。 */
    public record Phase(
            /** 是否为可由弹药方案关闭的试射阶段。 */ boolean registrationPhase,
            /** 相对阶段起点延迟，单位 Tick。 */ int startDelayTicks,
            /** 固定弹数；不使用时为 -1。 */ int fixedRounds,
            /** 基数倍率；不使用时为负数。 */ double baseMultiplier,
            /** 随机弹数下界；不使用时为 -1。 */ int randomMin,
            /** 随机弹数上界；不使用时为 -1。 */ int randomMax,
            /** 固定间隔，单位 Tick；不用时为空。 */ Integer intervalTicks,
            /** 首末发跨度，单位 Tick；不用时为空。 */ Integer durationTicks) {}

    /** 客户端几何预设。 */
    public record Pattern(
            /** profile 内预设 ID。 */ String id,
            /** 预设显示翻译键。 */ String translationKey,
            /** 已注册几何类型 ID。 */ ResourceLocation type,
            /** 按服务端规范化顺序保存的动态参数。 */ Map<String, Parameter> parameters) {
        public Pattern { parameters = Map.copyOf(parameters); }
    }

    /** 一个由 JSON 规格生成的数值控件定义。 */
    public record Parameter(
            /** 网络参数键。 */ String key,
            /** 默认值。 */ double defaultValue,
            /** 最小值。 */ double min,
            /** 最大值。 */ double max,
            /** 单次调整步长。 */ double step,
            /** UI 单位。 */ String unit) {}
}
