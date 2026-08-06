package org.ywzj.rvp.virtualflight.trajectory;

/** 可替换的纯虚拟弹道积分接口；实现不得访问世界或实体。 */
public interface RVP_VirtualTrajectoryIntegrator {
    String implementationId();
    int implementationVersion();
    RVP_VirtualTrajectoryResult step(RVP_VirtualTrajectoryState state,
                                     RVP_VirtualGuidanceInput guidance,
                                     RVP_VirtualTrajectoryParameters parameters);
}
