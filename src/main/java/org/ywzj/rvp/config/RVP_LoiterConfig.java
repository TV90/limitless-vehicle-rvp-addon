package org.ywzj.rvp.config;

/**
 * 通用载具自动盘旋配置。任何飞行器（无人机、空中炮艇等）均可通过载具 JSON 的
 * {@code rvp_loiter_*} 字段配置，独立于可部署 UAV 系统。
 * <p>仅保留核心行为参数；采样/超时等内部算法参数由盘旋服务固定为常量（见
 * {@code RVP_UavLoiterTickService}）。</p>
 */
public record RVP_LoiterConfig(
        boolean enabled,
        double loiterRadius,
        double loiterAltitudeOffset,
        double loiterTerrainClearance,
        double loiterMinSafeAltitude,
        double loiterFixedWingMinBank,
        boolean autoLoiterOnTakeoff,
        double loiterBank,
        int loiterDirection
) {
    public static final RVP_LoiterConfig DISABLED = new RVP_LoiterConfig(
            false,
            120.0,
            40.0,
            30.0,
            80.0,
            30.0,
            false,
            25.0,
            1
    );

    public boolean isConfigured() {
        return enabled;
    }
}
