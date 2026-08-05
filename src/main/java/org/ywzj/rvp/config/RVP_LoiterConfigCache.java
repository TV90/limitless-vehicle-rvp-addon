package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 载具盘旋配置缓存，按 vehicleId 索引。
 */
public final class RVP_LoiterConfigCache {
    private static final Map<ResourceLocation, RVP_LoiterConfig> CACHE = new ConcurrentHashMap<>();

    private RVP_LoiterConfigCache() {}

    public static void clear() {
        CACHE.clear();
    }

    public static void put(ResourceLocation vehicleId, RVP_LoiterConfig config) {
        if (vehicleId == null || config == null || !config.enabled()) {
            return;
        }
        CACHE.put(vehicleId, config);
    }

    public static RVP_LoiterConfig get(ResourceLocation vehicleId) {
        if (vehicleId == null) {
            return RVP_LoiterConfig.DISABLED;
        }
        return CACHE.getOrDefault(vehicleId, RVP_LoiterConfig.DISABLED);
    }
}
