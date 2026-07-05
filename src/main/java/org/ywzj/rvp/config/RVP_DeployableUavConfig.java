package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * 母车可部署 UAV 的配置。
 */
public record RVP_DeployableUavConfig(
        boolean enabled,
        ResourceLocation vehicleId,
        String role,
        Vec3 spawnOffset,
        String spawnYawMode,
        boolean singleInstance,
        boolean allowControlSwitch,
        boolean autoLinkDatalink
) {
    public static final RVP_DeployableUavConfig DISABLED = new RVP_DeployableUavConfig(
            false,
            null,
            "none",
            Vec3.ZERO,
            "parent",
            true,
            true,
            true
    );

    public boolean isConfigured() {
        return enabled && vehicleId != null;
    }
}
