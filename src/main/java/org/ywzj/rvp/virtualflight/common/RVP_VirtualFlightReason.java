package org.ywzj.rvp.virtualflight.common;

/** 日志、统计和调试命令共享的稳定状态原因码。 */
public enum RVP_VirtualFlightReason {
    /** 真实实体成功转换成持久化虚拟记录。 */
    REAL_TO_VIRTUAL,
    /** 进入动态恢复距离。 */
    RESTORE_DISTANCE_REACHED,
    /** 服务器公平预算批准恢复请求。 */
    RESTORE_BUDGET_GRANTED,
    /** 恢复区块和首 Tick 终点均已 ready。 */
    RESTORE_CHUNKS_READY,
    /** 虚拟记录成功重建真实实体。 */
    VIRTUAL_TO_REAL,
    /** 安全点搜索或 addFreshEntity 拒绝恢复。 */
    RESTORE_ADD_REJECTED,
    /** 恢复区块等待超过配置上限。 */
    RESTORE_TIMEOUT,
    /** 世界中已有实体占用稳定 UUID。 */
    UUID_CONFLICT,
    /** 当前武器索引已不存在或不再是 RVP Missile。 */
    WEAPON_DATA_MISSING,
    /** SavedData 中积分器 ID/版本与当前实现不兼容。 */
    INCOMPATIBLE_INTEGRATOR,
    /** 状态结构、坐标或积分结果非法。 */
    INVALID_STATE,
    /** 虚拟积分扣减后的剩余寿命小于零。 */
    LIFE_EXPIRED,
    /** 达到武器配置允许的最大虚拟飞行 Tick。 */
    MAX_VIRTUAL_TICKS,
    /** 不同维度 SavedData 出现同一稳定 UUID。 */
    DUPLICATE_SAVED_UUID
}
