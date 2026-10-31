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
    EO_JAMMER,
    /** 激光武器 / DIRCM 光束两端持续光：起点半球光、终点球光，颜色随光束本身。 */
    LASER
}
