package org.ywzj.rvp.client.visual.thermobaric;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 阶段 B 的固定温压预设解析器；阶段 C 再接入资源重载。 */
public final class RVP_ThermobaricPresetManager {
    /** 日志记录器。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 阶段 B 内建标准预设 ID。 */
    public static final ResourceLocation STANDARD_PRESET =
            ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric_standard");
    /** 公共数据缺省预设 ID。 */
    private static final ResourceLocation DEFAULT_PRESET =
            ResourceLocation.fromNamespaceAndPath("rvp", "default");
    /** 已记录的未知预设集合。 */
    private static final Set<ResourceLocation> WARNED_PRESETS = ConcurrentHashMap.newKeySet();

    private RVP_ThermobaricPresetManager() {
    }

    /** 先选择内建预设，再应用当前武器的类型化字段覆盖。 */
    public static RVP_ThermobaricPreset resolve(ResourceLocation presetId, String canonicalPatchJson) {
        if (!STANDARD_PRESET.equals(presetId) && !DEFAULT_PRESET.equals(presetId)
                && WARNED_PRESETS.add(presetId)) {
            LOGGER.warn("客户端未找到温压视觉预设 {}，已回退内建标准预设", presetId);
        }
        // 调用温压类型化 patch 解析器，让武器 preset_data 只覆盖显式出现且校验通过的字段。
        return RVP_ThermobaricPresetPatch.parse(canonicalPatchJson)
                .apply(RVP_ThermobaricPreset.DEFAULT);
    }
}
