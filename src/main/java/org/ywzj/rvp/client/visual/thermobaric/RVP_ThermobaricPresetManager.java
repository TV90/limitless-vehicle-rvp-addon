package org.ywzj.rvp.client.visual.thermobaric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 客户端温压预设资源重载器，负责不可变快照、类型化校验与安全回退。 */
public final class RVP_ThermobaricPresetManager extends SimpleJsonResourceReloadListener {
    /** 日志记录器。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 资源 JSON 解析器。 */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** 客户端资源中的温压预设目录。 */
    private static final String PRESET_DIRECTORY = "visual_effects";
    /** 客户端资源重载监听器单例。 */
    public static final RVP_ThermobaricPresetManager INSTANCE =
            new RVP_ThermobaricPresetManager();
    /** 标准温压预设 ID。 */
    public static final ResourceLocation STANDARD_PRESET =
            ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric_standard");
    /** 公共数据缺省预设 ID。 */
    private static final ResourceLocation DEFAULT_PRESET =
            ResourceLocation.fromNamespaceAndPath("rvp", "default");
    /** 已记录的未知预设集合。 */
    private static final Set<ResourceLocation> WARNED_PRESETS = ConcurrentHashMap.newKeySet();
    /** 最近一次成功重载得到的不可变预设表。 */
    private volatile Map<ResourceLocation, RVP_ThermobaricPreset> presets = Map.of();

    private RVP_ThermobaricPresetManager() {
        super(GSON, PRESET_DIRECTORY);
    }

    /** 先选择资源预设或内建默认值，再应用当前武器的类型化字段覆盖。 */
    public static RVP_ThermobaricPreset resolve(ResourceLocation presetId,
            String canonicalPatchJson) {
        RVP_ThermobaricPreset base = INSTANCE.resolveBasePreset(presetId);
        // 调用温压类型化 patch 解析器，让武器 preset_data 只覆盖显式出现且校验通过的字段。
        return RVP_ThermobaricPresetPatch.parse(canonicalPatchJson).apply(base);
    }

    /** 将重载线程准备好的 JSON 转换为完整不可变预设表，再一次性发布给客户端主线程。 */
    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources,
            ResourceManager resourceManager, ProfilerFiller profiler) {
        presets = buildPresetMap(resources);
        WARNED_PRESETS.clear();
        LOGGER.info("已加载 {} 个 RVP 温压客户端预设", presets.size());
    }

    /** 把资源 JSON 构造成可测试的类型化预设快照。 */
    static Map<ResourceLocation, RVP_ThermobaricPreset> buildPresetMap(
            Map<ResourceLocation, JsonElement> resources) {
        Map<ResourceLocation, RVP_ThermobaricPreset> loaded = new HashMap<>();
        resources.forEach((presetId, element) -> {
            if (element == null || !element.isJsonObject()) {
                LOGGER.warn("温压视觉预设 {} 不是 JSON 对象，已忽略该资源", presetId);
                return;
            }
            try {
                // 调用类型化温压 patch，把资源预设作为内建默认值上的稀疏字段覆盖进行校验。
                RVP_ThermobaricPreset preset = RVP_ThermobaricPresetPatch
                        .parse(GSON.toJson(element))
                        .apply(RVP_ThermobaricPreset.DEFAULT);
                loaded.put(presetId, preset);
            } catch (RuntimeException exception) {
                LOGGER.warn("温压视觉预设 {} 解析失败，已忽略该资源", presetId, exception);
            }
        });
        return Map.copyOf(loaded);
    }

    /** 解析资源预设；标准、缺省和未知 ID 都具有内建安全回退。 */
    private RVP_ThermobaricPreset resolveBasePreset(ResourceLocation presetId) {
        RVP_ThermobaricPreset resourcePreset = presets.get(presetId);
        if (resourcePreset != null) {
            return resourcePreset;
        }
        if (!STANDARD_PRESET.equals(presetId) && !DEFAULT_PRESET.equals(presetId)
                && WARNED_PRESETS.add(presetId)) {
            LOGGER.warn("客户端未找到温压视觉预设 {}，已回退内建标准预设", presetId);
        }
        return presets.getOrDefault(STANDARD_PRESET, RVP_ThermobaricPreset.DEFAULT);
    }
}
