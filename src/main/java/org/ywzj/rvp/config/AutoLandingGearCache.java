package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * 缓存每架飞机的自动收放起落架配置。
 * <p>
 * 由 {@link org.ywzj.rvp.mixin.VehicleDataManagerMixin} 在资源加载时填充，
 * 由 {@link org.ywzj.rvp.mixin.VehicleAutoLandingGearMixin} 在 tick 时查询。
 * </p>
 */
public final class AutoLandingGearCache {

    /** 禁用配置（默认） */
    public static final AutoLandingGearConfig DISABLED = new AutoLandingGearConfig(false, 100, 50, 25);

    private static Map<ResourceLocation, AutoLandingGearConfig> CONFIGS = Map.of();

    private AutoLandingGearCache() {}

    public static void replace(Map<ResourceLocation, AutoLandingGearConfig> configs) {
        CONFIGS = Map.copyOf(configs);
    }

    /**
     * 获取指定飞机的自动起落架配置，未配置时返回 DISABLED。
     */
    public static AutoLandingGearConfig get(ResourceLocation vehicleId) {
        if (vehicleId == null) return DISABLED;
        return CONFIGS.getOrDefault(vehicleId, DISABLED);
    }

    /**
     * 指定飞机是否启用了自动收放起落架。
     */
    public static boolean isEnabled(ResourceLocation vehicleId) {
        return get(vehicleId).enabled;
    }

    /**
     * 单架飞机的自动起落架配置。
     */
    public static final class AutoLandingGearConfig {
        public final boolean enabled;
        /** 速度超过此值 (km/h) → 收起起落架 */
        public final double retractSpeed;
        /** 速度低于此值 (km/h) 且离地低于 deployHeight → 放下起落架 */
        public final double deploySpeed;
        /** 离地高度低于此值 (m) 且速度低于 deploySpeed → 放下起落架 */
        public final double deployHeight;

        public AutoLandingGearConfig(boolean enabled, double retractSpeed, double deploySpeed, double deployHeight) {
            this.enabled = enabled;
            this.retractSpeed = retractSpeed;
            this.deploySpeed = deploySpeed;
            this.deployHeight = deployHeight;
        }
    }
}
