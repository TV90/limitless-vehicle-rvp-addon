package org.ywzj.rvp.guidance.trajectorymath.virtualguidance;

import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringLimits;

/**
 * 单 Tick 纯轨迹积分参数。
 *
 * @param rvpMaxGs 可选 RVP 最大法向过载，单位 G；非 null 时优先于 turningFactor
 * @param turningFactor 未配置 rvpMaxGs 时使用的单 Tick 方向插值强度，范围 0～1
 * @param aeroSteering 是否启用气动转向统一求解；阶段 S2 默认 false
 * @param rvpRefSpeed 动压参考速度，单位格/Tick；0 表示关闭动压减载
 * @param inducedDrag 诱导阻力系数，无量纲；0 表示关闭诱导阻力
 * @param turnRateLimitDegPerTick 绝对转角上限，单位度/Tick；0 表示不额外限制
 * @param densityFactor 当前高度的空气密度倍率，无量纲
 * @param cruiseAltitude 可选世界 Y 巡航高度；{@code null} 表示以当前高度为闭环基准
 * @param rotateToMotion 是否让实体恢复后朝向运动方向；纯积分过程只负责携带该快照值
 * @param constantSpeed 是否明确保持配置速率；为 true 时豁免诱导阻力掉速
 * @param propulsion 是否启用发动机推力
 * @param mass 当前 Tick 已解析的弹体质量（含变质量 A1 燃烧递减；RVP 游戏单位）
 * @param thrust 当前 Tick 已解析的发动机推力（含推力曲线 A2；RVP 游戏单位，加速度=thrust/mass）
 * @param motorBurnTime 主发动机从点火起持续的 Tick 数
 * @param ignitionTick 发射后开始点火的飞行 Tick
 * @param dragCoefficient 速度平方阻力系数；阻力按系数×速度平方÷当前质量×高度倍率计算
 * @param altitudeDragFactor 当前高度对应的阻力倍率
 * @param gravity 每 Tick 施加的 Y 轴重力增量；零值使用本体默认重力常量
 * @param minSpeed 最低速率，单位格/Tick；非正值表示不限制
 * @param maxSpeed 最高速率，单位格/Tick；非正值表示不限制
 */
public record RVP_VirtualTrajectoryParameters(
        Double rvpMaxGs,
        float turningFactor,
        boolean aeroSteering,
        double rvpRefSpeed,
        double inducedDrag,
        double turnRateLimitDegPerTick,
        double densityFactor,
        Double cruiseAltitude,
        boolean rotateToMotion,
        boolean constantSpeed,
        boolean propulsion,
        double mass,
        double thrust,
        double motorBurnTime,
        int ignitionTick,
        double dragCoefficient,
        double altitudeDragFactor,
        double gravity,
        float minSpeed,
        float maxSpeed
) {
    /**
     * 把虚拟积分参数投影为统一气动求解器的最小输入。
     *
     * @return 当前 Tick 的不可变气动转向限制
     */
    public RVP_AeroSteeringLimits aeroSteeringLimits() {
        return new RVP_AeroSteeringLimits(
                rvpMaxGs, turningFactor, rvpRefSpeed, densityFactor,
                inducedDrag, turnRateLimitDegPerTick, aeroSteering);
    }
}
