package org.ywzj.rvp.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 母车可部署 UAV 的配置。盘旋参数已移至 {@link RVP_LoiterConfig}（通用），
 * 本类仅保留 {@code autoLoiterOnSwitchBack} 作为 UAV 切回母车时的触发开关。
 *
 * <p>{@code initialSpeed} 为释放时赋予子载具的初速度（blocks/tick），
 * 方向沿子载具生成朝向（spawnYaw）的水平前进方向。不填或 ≤0 时不赋予初速度。</p>
 *
 * <p>{@code allowedSeatIndexes} 为允许部署无人机的母车座位索引列表；
 * 留空（默认）表示仅驾驶位（座位 0）可部署。</p>
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
        float initialSpeed,
        double signalRange,
        List<Integer> allowedSeatIndexes
) {
    /** 信号范围默认值（格）：母车与无人机的最大可控距离，超出即失联（2026-10-06 用户定版 2048）。 */
    public static final double DEFAULT_SIGNAL_RANGE = 2048.0;

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
            0f,
            DEFAULT_SIGNAL_RANGE,
            List.of()
    );

    public boolean isConfigured() {
        return enabled && vehicleId != null;
    }

    /** 信号范围（格）：≤0 视为无限（不检查距离）。 */
    public double effectiveSignalRange() {
        return signalRange <= 0 ? Double.MAX_VALUE : signalRange;
    }

    /** 是否允许在指定座位索引部署无人机：空列表表示仅驾驶位（0）。 */
    public boolean isSeatAllowed(int seatIndex) {
        if (allowedSeatIndexes == null || allowedSeatIndexes.isEmpty()) {
            return seatIndex == 0;
        }
        return allowedSeatIndexes.contains(seatIndex);
    }
}
