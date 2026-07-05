package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RVP_DeployableUavConfigCache {
    private static final Map<ResourceLocation, RVP_DeployableUavConfig> CACHE = new ConcurrentHashMap<>();

    private RVP_DeployableUavConfigCache() {}

    public static void clear() {
        CACHE.clear();
    }

    public static void put(ResourceLocation vehicleId, RVP_DeployableUavConfig config) {
        if (vehicleId == null || config == null || !config.enabled()) {
            return;
        }
        CACHE.put(vehicleId, config);
    }

    public static RVP_DeployableUavConfig get(ResourceLocation vehicleId) {
        if (vehicleId == null) {
            return RVP_DeployableUavConfig.DISABLED;
        }
        return CACHE.getOrDefault(vehicleId, RVP_DeployableUavConfig.DISABLED);
    }
}
