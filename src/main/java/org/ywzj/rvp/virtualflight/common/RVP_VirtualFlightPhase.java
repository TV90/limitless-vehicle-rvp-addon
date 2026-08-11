package org.ywzj.rvp.virtualflight.common;

/** 服务端权威虚拟飞行阶段；名称同时作为 SavedData 稳定值。 */
public enum RVP_VirtualFlightPhase {
    /** 不持有沿途区块 Ticket，仅执行纯虚拟弹道积分。 */
    VIRTUAL_CRUISE,
    /** 已越过恢复触发距离，等待服务器级进入恢复预算。 */
    RESTORE_REQUESTED,
    /** 正在按公平预算取得/刷新恢复 Ticket，并等待区块 entity-ticking。 */
    RESTORE_WAITING_CHUNKS
}
