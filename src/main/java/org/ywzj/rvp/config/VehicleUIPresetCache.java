package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * UI 预设名缓存，按载具 ID 索引。
 * <p>
 * 由 {@link VehicleDataManagerMixin} 在数据解析时写入，overlay 渲染时读取。
 * </p>
 */
public final class VehicleUIPresetCache {

    private static final Map<ResourceLocation, String> CACHE = new HashMap<>();

    /** 存入载具 ID 对应的预设名 */
    public static void put(ResourceLocation vehicleId, String presetName) {
        CACHE.put(vehicleId, presetName);
    }

    /** 读取载具 ID 对应的预设名，不存在返回 {@code null} */
    public static String get(ResourceLocation vehicleId) {
        return CACHE.get(vehicleId);
    }

    /** 是否在观瞄时显示骨骼俯视图，按载具 ID 索引。不存在默认 true。 */
    private static final Map<ResourceLocation, Boolean> SHOW_SKELETON = new HashMap<>();

    /** 载具 NCTR 名称，按载具 ID 索引。不存在默认 "?". */
    private static final Map<ResourceLocation, String> NCTR_NAME = new HashMap<>();

    /** 存入载具 ID 对应的骨骼显示开关 */
    public static void putShowSkeleton(ResourceLocation vehicleId, boolean show) {
        SHOW_SKELETON.put(vehicleId, show);
    }

    /** 读取载具 ID 对应的骨骼显示开关，不存在返回 true */
    public static boolean isShowSkeleton(ResourceLocation vehicleId) {
        return SHOW_SKELETON.getOrDefault(vehicleId, true);
    }

    /** 存入载具 ID 对应的 NCTR 名称。 */
    public static void putNctrName(ResourceLocation vehicleId, String nctrName) {
        if (nctrName == null || nctrName.isBlank()) {
            NCTR_NAME.remove(vehicleId);
            return;
        }
        NCTR_NAME.put(vehicleId, nctrName);
    }

    /** 读取载具 ID 对应的 NCTR 名称，不存在返回 "?". */
    public static String getNctrName(ResourceLocation vehicleId) {
        String value = NCTR_NAME.get(vehicleId);
        return value == null || value.isBlank() ? "?" : value;
    }

    /** 战术地图图标名（对应 textures/gui/map_icon/<name>.png），按载具 ID 索引；不存在返回 null 走类型默认。 */
    private static final Map<ResourceLocation, String> TACTICAL_MAP_ICON = new HashMap<>();

    /** 存入载具 ID 对应的战术地图图标名；空值表示清除，恢复类型默认图标。 */
    public static void putTacticalMapIcon(ResourceLocation vehicleId, String iconName) {
        if (iconName == null || iconName.isBlank()) {
            TACTICAL_MAP_ICON.remove(vehicleId);
            return;
        }
        TACTICAL_MAP_ICON.put(vehicleId, iconName.trim());
    }

    /** 读取载具 ID 对应的战术地图图标名，不存在返回 null。 */
    public static String getTacticalMapIcon(ResourceLocation vehicleId) {
        return TACTICAL_MAP_ICON.get(vehicleId);
    }

    public static Set<ResourceLocation> keys() {
        return CACHE.keySet();
    }

    private VehicleUIPresetCache() {}
}
