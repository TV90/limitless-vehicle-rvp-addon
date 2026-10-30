package org.ywzj.rvp.client.light;

/** 只区分预算与生命周期，不识别具体武器。 */
public enum RVP_LightSourceType {
    /** 成功开火的炮口短光。 */
    MUZZLE,
    /** 沿用现有视觉事件的爆炸短光。 */
    EXPLOSION,
    /** 按弹体 UUID 更新的发动机持续光。 */
    ROCKET_ENGINE,
    /** 光电干扰机玻片持续光；不继承发动机调参、喷口 HDR 核心和烟雾受光。 */
    EO_JAMMER
}
