package org.ywzj.rvp.firesupport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

/** 把已校验 profile 编码为供客户端 UI 使用的规范化当前 schema JSON。 */
public final class RVP_FireSupportProfileNetworkCodec {
    private RVP_FireSupportProfileNetworkCodec() {}

    /** 序列化完整 UI 与计划输入；不包含服务端运行时 pattern/delivery 对象。 */
    public static String encode(RVP_FireSupportProfile profile) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", profile.schemaVersion());
        JsonObject display = new JsonObject();
        display.addProperty("translation_key", profile.translationKey());
        root.add("display", display);
        JsonObject holder = new JsonObject();
        holder.addProperty("required_item", profile.holderPolicy().requiredItem().toString());
        JsonArray hands = new JsonArray();
        profile.holderPolicy().allowedHands().stream().sorted().forEach(hands::add);
        holder.add("allowed_hands", hands);
        root.add("holder_policy", holder);
        JsonObject call = new JsonObject();
        call.addProperty("base_duration_ticks", profile.callStage().baseDurationTicks());
        call.addProperty("cancel_on_player_death", profile.callStage().cancelOnPlayerDeath());
        call.addProperty("cancel_on_terminal_lost", profile.callStage().cancelOnTerminalLost());
        call.addProperty("cancel_on_disconnect", profile.callStage().cancelOnDisconnect());
        root.add("call_stage", call);
        JsonObject strike = new JsonObject();
        strike.addProperty("cease_fire_delay_ticks", profile.strikeStage().ceaseFireDelayTicks());
        root.add("strike_stage", strike);
        root.add("limits", encodeLimits(profile.limits()));

        JsonArray munitions = new JsonArray();
        profile.munitions().values().forEach(munition -> munitions.add(encodeMunition(munition)));
        root.add("munitions", munitions);
        JsonArray modes = new JsonArray();
        profile.fireModes().values().forEach(mode -> modes.add(encodeMode(mode)));
        root.add("fire_modes", modes);
        JsonArray patterns = new JsonArray();
        profile.patterns().values().forEach(pattern -> patterns.add(encodePattern(pattern)));
        root.add("patterns", patterns);
        return root.toString();
    }

    private static JsonObject encodeLimits(RVP_FireSupportProfile.Limits limits) {
        JsonObject out = new JsonObject();
        out.addProperty("min_target_distance_m", limits.minTargetDistanceMeters());
        out.addProperty("max_target_distance_m", limits.maxTargetDistanceMeters());
        out.addProperty("max_rounds_per_mission", limits.maxRoundsPerMission());
        out.addProperty("max_active_missions_per_player", limits.maxActiveMissionsPerPlayer());
        out.addProperty("max_active_missions_global", limits.maxActiveMissionsGlobal());
        out.addProperty("request_cooldown_ticks", limits.requestCooldownTicks());
        out.addProperty("max_mission_duration_ticks", limits.maxMissionDurationTicks());
        out.addProperty("max_loaded_chunks_per_mission", limits.maxLoadedChunksPerMission());
        out.addProperty("max_parameter_count", limits.maxParameterCount());
        return out;
    }

    private static JsonObject encodeMunition(RVP_FireSupportProfile.Munition munition) {
        JsonObject out = new JsonObject();
        out.addProperty("id", munition.id());
        out.addProperty("translation_key", munition.translationKey());
        out.addProperty("weapon", munition.weaponId().toString());
        out.addProperty("rounds_per_unit", munition.roundsPerUnit());
        JsonObject delivery = new JsonObject();
        delivery.addProperty("type", munition.deliveryType().toString());
        JsonObject data = new JsonObject();
        if (munition.deliveryData() instanceof RVP_FireSupportDeliveryTypes.VerticalProjectileData vertical) {
            data.addProperty("spawn_height_above_impact_m", vertical.spawnHeightAboveImpactMeters());
            data.addProperty("entry_speed_m_per_tick", vertical.entrySpeedMetersPerTick());
            data.addProperty("preload_ticks", vertical.preloadTicks());
            data.addProperty("heading_jitter_deg", vertical.headingJitterDegrees());
        }
        delivery.add("data", data);
        out.add("delivery", delivery);
        return out;
    }

    private static JsonObject encodeMode(RVP_FireSupportProfile.FireMode mode) {
        JsonObject out = new JsonObject();
        out.addProperty("id", mode.id());
        out.addProperty("translation_key", mode.translationKey());
        out.addProperty("call_duration_multiplier", mode.callDurationMultiplier());
        out.addProperty("dispersion_multiplier", mode.dispersionMultiplier());
        JsonArray phases = new JsonArray();
        mode.phases().forEach(phase -> {
            JsonObject value = new JsonObject();
            value.addProperty("id", phase.id());
            value.addProperty("translation_key", phase.translationKey());
            value.addProperty("start_delay_ticks", phase.startDelayTicks());
            JsonObject rounds = new JsonObject();
            if (phase.rounds().baseMultiplier() != null) {
                rounds.addProperty("base_multiplier", phase.rounds().baseMultiplier());
                rounds.addProperty("rounding", "ceil");
            } else if (phase.rounds().fixed() != null) {
                rounds.addProperty("fixed", phase.rounds().fixed());
            } else {
                rounds.addProperty("random_min", phase.rounds().randomMin());
                rounds.addProperty("random_max", phase.rounds().randomMax());
            }
            value.add("rounds", rounds);
            if (phase.intervalTicks() != null) value.addProperty("interval_ticks", phase.intervalTicks());
            else value.addProperty("duration_ticks", phase.durationTicks());
            phases.add(value);
        });
        out.add("phases", phases);
        return out;
    }

    private static JsonObject encodePattern(RVP_FireSupportProfile.PatternPreset pattern) {
        JsonObject out = new JsonObject();
        out.addProperty("id", pattern.id());
        out.addProperty("translation_key", pattern.translationKey());
        out.addProperty("type", pattern.type().toString());
        JsonObject data = new JsonObject();
        JsonObject parameters = new JsonObject();
        for (Map.Entry<String, RVP_FireSupportProfile.ParameterSpec> entry : pattern.parameters().entrySet()) {
            RVP_FireSupportProfile.ParameterSpec spec = entry.getValue();
            JsonObject value = new JsonObject();
            value.addProperty("type", "double");
            value.addProperty("default", spec.defaultValue());
            value.addProperty("min", spec.min());
            value.addProperty("max", spec.max());
            value.addProperty("step", spec.step());
            value.addProperty("unit", spec.unit());
            parameters.add(entry.getKey(), value);
        }
        data.add("parameters", parameters);
        out.add("data", data);
        return out;
    }
}
