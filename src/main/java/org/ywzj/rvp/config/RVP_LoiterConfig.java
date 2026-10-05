package org.ywzj.rvp.config;

/**
 * 通用载具自动盘旋配置。任何飞行器（无人机、空中炮艇等）均可通过载具 JSON 的
 * {@code rvp_loiter_*} 字段配置，独立于可部署 UAV 系统。
 * <p>2026-10-06 用户定版精简：JSON 仅保留 4 键（enabled/radius/altitude_offset/auto_on_takeoff），
 * 其余算法参数收敛为本类常量——想调改这里，不再开 JSON 口子。</p>
 */
public record RVP_LoiterConfig(
        boolean enabled,
        double loiterRadius,
        double loiterAltitudeOffset,
        boolean autoLoiterOnTakeoff
) {
    /** 目标高度的地形余量（格）：目标高度不低于正下方地表 + 本值。 */
    public static final double TERRAIN_CLEARANCE = 20.0;
    /**
     * 盘旋坡度上限（度）：固定翼按配置半径反算所需坡度（atan(V²/gR)），超过本上限才允许
     * 实际半径大于配置值（物理兜底）。2026-10-06 由 25 提到 40——100kph 巡航配 100 格半径
     * 需约 38° 坡度，25° 上限会把半径顶到 169 格（用户实测"半径大得一批"的根因之一）。
     */
    public static final double LOITER_BANK = 40.0;
    /** 盘旋方向：1 = 右盘旋，-1 = 左盘旋。 */
    public static final int LOITER_DIRECTION = 1;
    /** 对齐阶段（CLIMB/TRANSIT/APPROACH）的坡度上限（度）：接入段平缓，不满舵。 */
    public static final double ALIGN_BANK_CAP = 25.0;
    /** 旋翼盘旋半径下限（格）：低于该值圆周过小易绕飞抖动。 */
    public static final double ROTARY_MIN_RADIUS = 30.0;

    public static final RVP_LoiterConfig DISABLED = new RVP_LoiterConfig(
            false,
            120.0,
            40.0,
            false
    );

    public boolean isConfigured() {
        return enabled;
    }
}
