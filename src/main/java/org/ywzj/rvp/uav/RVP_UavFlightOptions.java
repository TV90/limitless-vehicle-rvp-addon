package org.ywzj.rvp.uav;

/** UAV固定翼飞行路由，独立于Gunner开关。 */
public final class RVP_UavFlightOptions {
    /** false保留原盘旋算法；true参数化调用公共控制器与执行器，需重建重启。 */
    public static final boolean USE_SHARED_FIXED_WING_FLIGHT = false;
    private RVP_UavFlightOptions() {}
}
