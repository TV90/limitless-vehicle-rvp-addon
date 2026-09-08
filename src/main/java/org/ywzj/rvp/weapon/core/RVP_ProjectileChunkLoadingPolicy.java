package org.ywzj.rvp.weapon.core;

/** 无载具生成入口对弹体动态 Chunk 路径保护的显式策略。 */
public enum RVP_ProjectileChunkLoadingPolicy {
    /** 使用弹体类型默认策略；普通 Bullet 不强加载路径。 */
    DEFAULT,
    /** 炮火支援远程弹道；允许 Bullet 使用与重型弹体相同的动态路径保护。 */
    REMOTE_FIRE_SUPPORT
}
