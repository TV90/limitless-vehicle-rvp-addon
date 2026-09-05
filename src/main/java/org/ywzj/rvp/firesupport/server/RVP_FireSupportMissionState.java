package org.ywzj.rvp.firesupport.server;

/** 网络稳定使用的权威任务阶段。 */
public enum RVP_FireSupportMissionState {
    CALLING,
    STRIKING,
    CEASE_FIRE_PENDING,
    COMPLETED,
    CANCELLED,
    CEASED,
    FAILED
}
