package org.ywzj.rvp.guidance.trajectorymath.virtualguidance;

/** 可替换的纯轨迹积分接口；实现不得访问世界、实体、区块或武器配置对象。 */
public interface RVP_VirtualTrajectoryIntegrator {
    /** @return 用于持久化兼容性检查的稳定实现标识。 */
    String implementationId();

    /** @return 用于持久化兼容性检查的状态版本。 */
    int implementationVersion();

    /**
     * 把不可变轨迹状态推进一个逻辑 Tick。
     *
     * @param state 上一个 Tick 完成后的状态
     * @param guidance 本 Tick 的固定制导输入
     * @param parameters 本 Tick 的物理参数
     * @return 新状态、转角与数值有效性
     */
    RVP_VirtualTrajectoryResult step(RVP_VirtualTrajectoryState state,
                                     RVP_VirtualGuidanceInput guidance,
                                     RVP_VirtualTrajectoryParameters parameters);
}
