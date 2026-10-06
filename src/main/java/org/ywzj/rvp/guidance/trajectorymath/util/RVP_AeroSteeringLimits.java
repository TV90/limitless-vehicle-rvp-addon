package org.ywzj.rvp.guidance.trajectorymath.util;

/**
 * 单 Tick 气动转向求解所需的不可变限制快照。
 *
 * <p>本记录只携带已经由上层解析完成的数值，不访问武器数据、实体或世界。阶段 S2
 * 由实体态与虚拟态共同构造该快照并复用同一求解器。</p>
 *
 * @param rvpMaxGs 可选设计法向过载上限，单位 G；{@code null} 表示由 turningFactor 折算
 * @param turningFactor 未配置 rvpMaxGs 时使用的旧版单 Tick 方向插值强度，运行时钳制到 0～1
 * @param referenceSpeed 设计动压参考速度，单位格/Tick；非正有限值表示关闭速度动压减载
 * @param densityFactor 当前高度的空气密度倍率，无量纲；参与归一化动压计算
 * @param inducedDrag 诱导阻力系数，无量纲；非正值表示不产生诱导阻力损失
 * @param turnRateLimitDegPerTick 可选绝对转角上限，单位度/Tick；非正值表示不限制
 * @param enabled 是否启用气动转向；false 时严格委托旧版转向算法
 * @param attackAngleLimitDeg 总攻角控制上限，单位度；0 表示关闭，合法范围为 (0, 90)
 */
public record RVP_AeroSteeringLimits(
        Double rvpMaxGs,
        float turningFactor,
        double referenceSpeed,
        double densityFactor,
        double inducedDrag,
        double turnRateLimitDegPerTick,
        boolean enabled,
        double attackAngleLimitDeg
) {
    /** 未请求攻角的调用方保留现有七参数契约。 */
    public RVP_AeroSteeringLimits(Double rvpMaxGs, float turningFactor, double referenceSpeed,
                                 double densityFactor, double inducedDrag,
                                 double turnRateLimitDegPerTick, boolean enabled) {
        this(rvpMaxGs, turningFactor, referenceSpeed, densityFactor, inducedDrag,
                turnRateLimitDegPerTick, enabled, 0.0);
    }

    /** 攻角要求显式启用；没有有限 G 预算的瞬转弹继续使用原转向方式。 */
    public boolean attackAngleEnabled() {
        return enabled && Double.isFinite(attackAngleLimitDeg)
                && attackAngleLimitDeg > 0.0 && attackAngleLimitDeg < 90.0
                && (rvpMaxGs != null || (Float.isFinite(turningFactor) && turningFactor < 1.0F));
    }
}
