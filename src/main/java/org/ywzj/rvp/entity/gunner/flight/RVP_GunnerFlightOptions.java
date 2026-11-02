package org.ywzj.rvp.entity.gunner.flight;

/** Gunner业务路由开关；改值后重新构建并重启，旧算法完整保留。 */
public final class RVP_GunnerFlightOptions {
    /** false走原路径；true参数化调用公共固定翼控制器与执行器。默认保留旧行为。 */
    public static final boolean USE_IMPROVED_FIXED_WING_AI = false;
    private RVP_GunnerFlightOptions() {}
}
