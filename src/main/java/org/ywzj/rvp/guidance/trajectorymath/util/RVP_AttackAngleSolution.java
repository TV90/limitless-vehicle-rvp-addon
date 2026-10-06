package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;

/**
 * 单 Tick 准三自由度求解结果；姿态为独立状态，攻角为本次施力前的派生量。
 *
 * @param velocity 法向转向后的速度，单位格/Tick；保持输入速率
 * @param bodyDirection 本 Tick 弹体前向单位向量，供推力、同步和下一 Tick 使用
 * @param attackAngleRadians 本次施力前的总攻角，单位弧度
 * @param loadFactor sin(攻角)/sin(上限) 的饱和值，范围 0～1，供诱导阻力使用
 * @param turnAngleRadians 实际速度方向转角，单位弧度
 */
public record RVP_AttackAngleSolution(Vec3 velocity, Vec3 bodyDirection,
                                     double attackAngleRadians, double loadFactor,
                                     double turnAngleRadians) {
}
