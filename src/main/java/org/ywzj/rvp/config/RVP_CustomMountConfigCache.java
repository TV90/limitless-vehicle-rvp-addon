package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public final class RVP_CustomMountConfigCache {

    private static Map<ResourceLocation, List<RVP_CustomMountConfig>> CONFIGS = Map.of();

    private RVP_CustomMountConfigCache() {}

    public static void replace(Map<ResourceLocation, List<RVP_CustomMountConfig>> configs) {
        CONFIGS = Map.copyOf(configs);
    }

    @NotNull
    public static List<RVP_CustomMountConfig> get(ResourceLocation vehicleId) {
        if (vehicleId == null) {
            return List.of();
        }
        return CONFIGS.getOrDefault(vehicleId, List.of());
    }
}
