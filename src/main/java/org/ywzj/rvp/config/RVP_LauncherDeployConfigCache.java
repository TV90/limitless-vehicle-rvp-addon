package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RVP_LauncherDeployConfigCache {
    private static final Map<ResourceLocation, List<RVP_LauncherDeployConfig>> CACHE = new ConcurrentHashMap<>();

    private RVP_LauncherDeployConfigCache() {}

    public static void clear() {
        CACHE.clear();
    }

    public static void put(ResourceLocation vehicleId, List<RVP_LauncherDeployConfig> configs) {
        if (vehicleId == null || configs == null || configs.isEmpty()) {
            return;
        }
        CACHE.put(vehicleId, List.copyOf(configs));
    }

    public static List<RVP_LauncherDeployConfig> get(ResourceLocation vehicleId) {
        if (vehicleId == null) {
            return List.of();
        }
        return CACHE.getOrDefault(vehicleId, List.of());
    }
}
