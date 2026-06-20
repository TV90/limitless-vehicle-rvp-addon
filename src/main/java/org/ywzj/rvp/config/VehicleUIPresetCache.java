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

    /** 存入载具 ID 对应的骨骼显示开关 */
    public static void putShowSkeleton(ResourceLocation vehicleId, boolean show) {
        SHOW_SKELETON.put(vehicleId, show);
    }

    /** 读取载具 ID 对应的骨骼显示开关，不存在返回 true */
    public static boolean isShowSkeleton(ResourceLocation vehicleId) {
        return SHOW_SKELETON.getOrDefault(vehicleId, true);
    }

    public static Set<ResourceLocation> keys() {
        return CACHE.keySet();
    }

    private VehicleUIPresetCache() {}
}
