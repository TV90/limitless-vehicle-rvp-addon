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
        // 保留初始化阶段的空车型保护，不能把 null 传给 ConcurrentHashMap。
        if (vehicleId == null) {
            return RVP_DeployableUavConfig.DISABLED;
        }
        RVP_DeployableUavCatalog.Option option = RVP_DeployableUavCatalog.getDefaultOption(vehicleId);
        return option == null ? CACHE.getOrDefault(vehicleId, RVP_DeployableUavConfig.DISABLED)
                : option.effectiveConfig();
    }
    /** 读取母车全部可选辅助载具候选。 */
    public static java.util.List<RVP_DeployableUavCatalog.Option> getOptions(ResourceLocation parentVehicleId) {
        return RVP_DeployableUavCatalog.getOptions(parentVehicleId);
    }

    /** 读取车型默认候选的有效配置，保留既有单配置调用点。 */
    public static RVP_DeployableUavConfig getDefaultEffective(ResourceLocation parentVehicleId) {
        RVP_DeployableUavCatalog.Option option = RVP_DeployableUavCatalog.getDefaultOption(parentVehicleId);
        return option == null ? RVP_DeployableUavConfig.DISABLED : option.effectiveConfig();
    }

}
