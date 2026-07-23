package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * 母车可部署 UAV 的配置。盘旋参数已移至 {@link RVP_LoiterConfig}（通用），
 * 本类仅保留 {@code autoLoiterOnSwitchBack} 作为 UAV 切回母车时的触发开关。
 *
 * <p>{@code initialSpeed} 为释放时赋予子载具的初速度（blocks/tick），
 * 方向沿子载具生成朝向（spawnYaw）的水平前进方向。不填或 ≤0 时不赋予初速度。</p>
 */
public record RVP_DeployableUavConfig(
        boolean enabled,
        ResourceLocation vehicleId,
        String role,
        Vec3 spawnOffset,
        String spawnYawMode,
        boolean singleInstance,
        boolean allowControlSwitch,
        boolean autoLinkDatalink,
        int redeployCooldownTick,
        boolean autoLoiterOnSwitchBack,
        float initialSpeed
) {
    public static final RVP_DeployableUavConfig DISABLED = new RVP_DeployableUavConfig(
            false,
            null,
            "none",
            Vec3.ZERO,
            "parent",
            true,
            true,
            true,
            0,
            true,
            0f
    );

    public boolean isConfigured() {
        return enabled && vehicleId != null;
    }
}
