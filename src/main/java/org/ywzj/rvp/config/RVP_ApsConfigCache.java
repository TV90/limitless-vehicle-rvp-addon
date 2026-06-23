package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public final class RVP_ApsConfigCache {

    private static final Map<ResourceLocation, RVP_ApsConfig> CACHE = new HashMap<>();

    public static void put(ResourceLocation vehicleId, RVP_ApsConfig config) {
        if (config == null || !config.isEnabled()) {
            CACHE.remove(vehicleId);
            return;
        }
        CACHE.put(vehicleId, config);
    }

    public static RVP_ApsConfig get(ResourceLocation vehicleId) {
        if (vehicleId == null) {
            return RVP_ApsConfig.DISABLED;
        }
        return CACHE.getOrDefault(vehicleId, RVP_ApsConfig.DISABLED);
    }

    private RVP_ApsConfigCache() {}
}
