package org.ywzj.rvp.guidance.trajectorymath.virtualguidance;

/**
 * 一步纯轨迹积分结果。
 *
 * @param state 积分后的不可变弹体状态
 * @param turnAngleRadians 本 Tick 速度方向变化角，单位弧度
 * @param invalid 结果是否含 NaN 或 Infinity，用于阻止非法数值进入业务状态
 */
public record RVP_VirtualTrajectoryResult(RVP_VirtualTrajectoryState state,
                                          double turnAngleRadians,
                                          boolean invalid) {
}
