package org.ywzj.rvp.client.visual.thermobaric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 可区分“字段缺失”和“字段显式填写”的温压预设覆盖。 */
public final class RVP_ThermobaricPresetPatch {
    /** 日志记录器。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 合法的温压预设字段集合。 */
    private static final Set<String> KNOWN_FIELDS = Set.of(
            "core_color", "flame_color", "smoke_color",
            "show_core", "show_pressure_wave", "show_condensation_cloud",
            "show_condensation_cloud_particles", "condensation_cloud_particle_max_count",
            "condensation_cloud_particle_scale",
            "show_dust_ring", "show_cloud",
            "max_clouds", "max_fireball_clouds", "max_dust_segments",
            "dust_ground_radial_samples",
            "pressure_rings", "pressure_segments",
            "core_start_tick", "core_full_tick", "core_fade_duration_ticks",
            "pressure_wave_start_tick", "pressure_wave_full_tick", "pressure_wave_fade_duration_ticks",
            "dust_ring_start_tick", "dust_ring_full_tick",
            "cloud_start_tick", "cloud_full_tick", "cloud_fade_duration_ticks",
            "cloud_color_change_start_tick", "cloud_color_change_end_tick",
            "pressure_radius_factor", "dust_radius_factor", "cloud_radius_factor", "cloud_rise_factor",
            "cloud_rise_speed_factor", "cloud_roll_speed_factor",
            "near_sound", "far_sound", "tail_sound");
    /** 已记录的字段警告键，避免连续爆炸反复刷屏。 */
    private static final Set<String> WARNED_FIELDS = ConcurrentHashMap.newKeySet();

    /** 点火核心颜色覆盖。 */
    private final Integer coreColor;
    /** 主火球颜色覆盖。 */
    private final Integer flameColor;
    /** 烟云颜色覆盖。 */
    private final Integer smokeColor;
    /** 点火核心与主火球显示开关覆盖。 */
    private final Boolean showCore;
    /** 压力波光学球壳显示开关覆盖。 */
    private final Boolean showPressureWave;
    /** 使用纯白贴图球壳实现的压力波凝结云墙显示开关覆盖。 */
    private final Boolean showCondensationCloud;
    /** 仅使用方块团粒子贴图实现的压力波凝结云显示开关覆盖。 */
    private final Boolean showCondensationCloudParticles;
    /** 粒子凝结云最大显示粒子数量覆盖。 */
    private final Integer condensationCloudParticleMaxCount;
    /** 粒子凝结云贴图尺寸缩放倍率覆盖。 */
    private final Float condensationCloudParticleScale;
    /** 贴地尘环显示开关覆盖。 */
    private final Boolean showDustRing;
    /** 后燃烟云显示开关覆盖。 */
    private final Boolean showCloud;
    /** 完整视觉密度下后燃烟云最大云团数量覆盖。 */
    private final Integer maxClouds;
    /** 完整视觉密度下温压火球最大团状云数量覆盖。 */
    private final Integer maxFireballClouds;
    /** 完整视觉密度下贴地尘环最大环段数量覆盖。 */
    private final Integer maxDustSegments;
    /** 尘环沿扩散半径预采样的地表层数覆盖。 */
    private final Integer dustGroundRadialSamples;
    /** 压力波及凝结云球壳纬向细分数覆盖。 */
    private final Integer pressureRings;
    /** 压力波及凝结云球壳经向细分数覆盖。 */
    private final Integer pressureSegments;
    /** 主火球开始 tick 覆盖。 */
    private final Integer coreStartTick;
    /** 主火球完整成形 tick 覆盖。 */
    private final Integer coreFullTick;
    /** 主火球完整成形后消失持续 tick 覆盖。 */
    private final Integer coreFadeDurationTicks;
    /** 压力波开始 tick 覆盖。 */
    private final Integer pressureWaveStartTick;
    /** 压力波到达最大半径 tick 覆盖。 */
    private final Integer pressureWaveFullTick;
    /** 压力波到达最大半径后消失持续 tick 覆盖。 */
    private final Integer pressureWaveFadeDurationTicks;
    /** 尘环开始 tick 覆盖。 */
    private final Integer dustRingStartTick;
    /** 尘环到达最大半径 tick 覆盖。 */
    private final Integer dustRingFullTick;
    /** 烟云开始 tick 覆盖。 */
    private final Integer cloudStartTick;
    /** 烟云开始消散的绝对 tick 覆盖。 */
    private final Integer cloudFullTick;
    /** 烟云开始消散后到完全消失的持续 tick 覆盖。 */
    private final Integer cloudFadeDurationTicks;
    /** 烟云从火焰色向烟色变化的绝对开始 tick 覆盖。 */
    private final Integer cloudColorChangeStartTick;
    /** 烟云完全变为烟色的绝对结束 tick 覆盖。 */
    private final Integer cloudColorChangeEndTick;
    /** 压力波半径倍率覆盖。 */
    private final Float pressureRadiusFactor;
    /** 尘环半径倍率覆盖。 */
    private final Float dustRadiusFactor;
    /** 烟云横向半径倍率覆盖。 */
    private final Float cloudRadiusFactor;
    /** 烟云最终最高升起高度倍率覆盖，配置值本身不做上限钳制。 */
    private final Float cloudRiseFactor;
    /** 烟云竖直升起速度倍率覆盖。 */
    private final Float cloudRiseSpeedFactor;
    /** 烟云翻滚、卷吸、湍流与水平平流速度倍率覆盖。 */
    private final Float cloudRollSpeedFactor;
    /** 近距离音效覆盖。 */
    private final ResourceLocation nearSound;
    /** 远距离音效覆盖。 */
    private final ResourceLocation farSound;
    /** 尾音覆盖。 */
    private final ResourceLocation tailSound;

    private RVP_ThermobaricPresetPatch(JsonObject object) {
        coreColor = readColor(object, "core_color");
        flameColor = readColor(object, "flame_color");
        smokeColor = readColor(object, "smoke_color");
        showCore = readBoolean(object, "show_core");
        showPressureWave = readBoolean(object, "show_pressure_wave");
        showCondensationCloud = readBoolean(object, "show_condensation_cloud");
        showCondensationCloudParticles = readBoolean(object, "show_condensation_cloud_particles");
        condensationCloudParticleMaxCount = readInteger(object,
                "condensation_cloud_particle_max_count");
        condensationCloudParticleScale = readFloat(object,
                "condensation_cloud_particle_scale");
        showDustRing = readBoolean(object, "show_dust_ring");
        showCloud = readBoolean(object, "show_cloud");
        maxClouds = readInteger(object, "max_clouds");
        maxFireballClouds = readInteger(object, "max_fireball_clouds");
        maxDustSegments = readInteger(object, "max_dust_segments");
        dustGroundRadialSamples = readInteger(object, "dust_ground_radial_samples");
        pressureRings = readInteger(object, "pressure_rings");
        pressureSegments = readInteger(object, "pressure_segments");
        coreStartTick = readInteger(object, "core_start_tick");
        coreFullTick = readInteger(object, "core_full_tick");
        coreFadeDurationTicks = readInteger(object, "core_fade_duration_ticks");
        pressureWaveStartTick = readInteger(object, "pressure_wave_start_tick");
        pressureWaveFullTick = readInteger(object, "pressure_wave_full_tick");
        pressureWaveFadeDurationTicks = readInteger(object, "pressure_wave_fade_duration_ticks");
        dustRingStartTick = readInteger(object, "dust_ring_start_tick");
        dustRingFullTick = readInteger(object, "dust_ring_full_tick");
        cloudStartTick = readInteger(object, "cloud_start_tick");
        cloudFullTick = readInteger(object, "cloud_full_tick");
        cloudFadeDurationTicks = readInteger(object, "cloud_fade_duration_ticks");
        cloudColorChangeStartTick = readInteger(object, "cloud_color_change_start_tick");
        cloudColorChangeEndTick = readInteger(object, "cloud_color_change_end_tick");
        pressureRadiusFactor = readFloat(object, "pressure_radius_factor");
        dustRadiusFactor = readFloat(object, "dust_radius_factor");
        cloudRadiusFactor = readFloat(object, "cloud_radius_factor");
        cloudRiseFactor = readFloat(object, "cloud_rise_factor");
        cloudRiseSpeedFactor = readFloat(object, "cloud_rise_speed_factor");
        cloudRollSpeedFactor = readFloat(object, "cloud_roll_speed_factor");
        nearSound = readResourceLocation(object, "near_sound");
        farSound = readResourceLocation(object, "far_sound");
        tailSound = readResourceLocation(object, "tail_sound");
        for (String field : object.keySet()) {
            if (!KNOWN_FIELDS.contains(field)) {
                warnOnce(field, "温压视觉 preset_data 含未知字段 {}，已忽略", field);
            }
        }
    }

    /** 解析规范化 JSON；结构或字段错误只回退对应字段，不中断客户端。 */
    public static RVP_ThermobaricPresetPatch parse(String canonicalJson) {
        try {
            JsonElement root = JsonParser.parseString(canonicalJson);
            if (!root.isJsonObject()) {
                warnOnce("root", "温压视觉 preset_data 不是 JSON 对象，已使用默认预设", "root");
                return new RVP_ThermobaricPresetPatch(new JsonObject());
            }
            return new RVP_ThermobaricPresetPatch(root.getAsJsonObject());
        } catch (RuntimeException exception) {
            warnOnce("json", "温压视觉 preset_data 无法解析，已使用默认预设：{}", exception.getMessage());
            return new RVP_ThermobaricPresetPatch(new JsonObject());
        }
    }

    /** 按字段把武器覆盖合并到给定基础预设。 */
    public RVP_ThermobaricPreset apply(RVP_ThermobaricPreset base) {
        int mergedCoreStartTick = value(coreStartTick, base.coreStartTick());
        int mergedPressureStartTick = value(pressureWaveStartTick, base.pressureWaveStartTick());
        int mergedDustStartTick = value(dustRingStartTick, base.dustRingStartTick());
        int mergedCloudStartTick = value(cloudStartTick, base.cloudStartTick());
        int mergedCloudColorChangeStartTick = value(
                cloudColorChangeStartTick, base.cloudColorChangeStartTick());
        return new RVP_ThermobaricPreset(
                value(coreColor, base.coreColor()),
                value(flameColor, base.flameColor()),
                value(smokeColor, base.smokeColor()),
                value(showCore, base.showCore()),
                value(showPressureWave, base.showPressureWave()),
                value(showCondensationCloud, base.showCondensationCloud()),
                value(showCondensationCloudParticles, base.showCondensationCloudParticles()),
                value(condensationCloudParticleMaxCount,
                        base.condensationCloudParticleMaxCount()),
                value(condensationCloudParticleScale, base.condensationCloudParticleScale()),
                value(showDustRing, base.showDustRing()),
                value(showCloud, base.showCloud()),
                value(maxClouds, base.maxClouds()),
                value(maxFireballClouds, base.maxFireballClouds()),
                value(maxDustSegments, base.maxDustSegments()),
                value(dustGroundRadialSamples, base.dustGroundRadialSamples()),
                value(pressureRings, base.pressureRings()),
                value(pressureSegments, base.pressureSegments()),
                mergedCoreStartTick,
                Math.max(mergedCoreStartTick, value(coreFullTick, base.coreFullTick())),
                value(coreFadeDurationTicks, base.coreFadeDurationTicks()),
                mergedPressureStartTick,
                Math.max(mergedPressureStartTick, value(pressureWaveFullTick, base.pressureWaveFullTick())),
                value(pressureWaveFadeDurationTicks, base.pressureWaveFadeDurationTicks()),
                mergedDustStartTick,
                Math.max(mergedDustStartTick, value(dustRingFullTick, base.dustRingFullTick())),
                mergedCloudStartTick,
                Math.max(mergedCloudStartTick, value(cloudFullTick, base.cloudFullTick())),
                value(cloudFadeDurationTicks, base.cloudFadeDurationTicks()),
                mergedCloudColorChangeStartTick,
                Math.max(mergedCloudColorChangeStartTick,
                        value(cloudColorChangeEndTick, base.cloudColorChangeEndTick())),
                value(pressureRadiusFactor, base.pressureRadiusFactor()),
                value(dustRadiusFactor, base.dustRadiusFactor()),
                value(cloudRadiusFactor, base.cloudRadiusFactor()),
                value(cloudRiseFactor, base.cloudRiseFactor()),
                value(cloudRiseSpeedFactor, base.cloudRiseSpeedFactor()),
                value(cloudRollSpeedFactor, base.cloudRollSpeedFactor()),
                value(nearSound, base.nearSound()),
                value(farSound, base.farSound()),
                value(tailSound, base.tailSound()));
    }

    private static Integer readColor(JsonObject object, String field) {
        String text = readString(object, field);
        if (text == null) {
            return null;
        }
        if (!text.matches("#[0-9a-fA-F]{6}")) {
            warnOnce(field, "温压视觉字段 {} 不是 #RRGGBB 颜色，已回退", field);
            return null;
        }
        return Integer.parseInt(text.substring(1), 16);
    }

    private static Integer readInteger(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null) {
            return null;
        }
        try {
            double numericValue = element.getAsDouble();
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(numericValue) || numericValue != Math.rint(numericValue)
                    || numericValue < 0.0D || numericValue > Integer.MAX_VALUE) {
                throw new IllegalArgumentException();
            }
            return (int) numericValue;
        } catch (RuntimeException exception) {
            warnOnce(field, "温压视觉字段 {} 类型错误或越界，已回退", field);
            return null;
        }
    }

    private static Float readFloat(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null) {
            return null;
        }
        try {
            float value = element.getAsFloat();
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()
                    || !Float.isFinite(value) || value < 0.0F) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException exception) {
            warnOnce(field, "温压视觉字段 {} 类型错误或越界，已回退", field);
            return null;
        }
    }

    private static Boolean readBoolean(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null) {
            return null;
        }
        try {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException();
            }
            return element.getAsBoolean();
        } catch (RuntimeException exception) {
            warnOnce(field, "温压视觉字段 {} 类型错误，已回退", field);
            return null;
        }
    }

    private static ResourceLocation readResourceLocation(JsonObject object, String field) {
        String text = readString(object, field);
        if (text == null) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(text);
        if (location == null) {
            warnOnce(field, "温压视觉字段 {} 不是合法资源 ID，已回退", field);
        }
        return location;
    }

    private static String readString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null) {
            return null;
        }
        try {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException();
            }
            return element.getAsString();
        } catch (RuntimeException exception) {
            warnOnce(field, "温压视觉字段 {} 类型错误，已回退", field);
            return null;
        }
    }

    private static <T> T value(T override, T fallback) {
        return override == null ? fallback : override;
    }

    private static void warnOnce(String key, String message, Object argument) {
        if (WARNED_FIELDS.add(key)) {
            LOGGER.warn(message, argument);
        }
    }
}
