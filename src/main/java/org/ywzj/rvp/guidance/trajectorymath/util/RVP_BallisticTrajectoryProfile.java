package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;

/**
 * PRESET 弹道工具所需的最小只读参数契约。
 *
 * <p>虚拟飞行、实体飞行或预测器可用各自的数据类型实现该接口，数学工具无需依赖任何
 * 具体业务配置类。</p>
 */
public interface RVP_BallisticTrajectoryProfile {
    /** @return 固定发射点世界坐标。 */
    Vec3 launchPosition();

    /** @return 相对发射点 Y 的弹道顶点高度上限，单位格。 */
    double cruiseAltitude();

    /** @return 弹道追踪点的水平前伸上限，单位格。 */
    double maxAscentLead();

    /** @return 终端俯冲的最小启动水平距离，单位格。 */
    double diveRadius();

    /** @return 高度差换算为俯冲启动距离的倍率。 */
    double diveAltitudeFactor();

    /** @return 转弯半径换算为俯冲启动距离的倍率。 */
    double diveLeadFactor();

    /** @return 中段横向战术机动幅度，单位格；零表示关闭。 */
    double tacticalManeuverAmplitude();
}
