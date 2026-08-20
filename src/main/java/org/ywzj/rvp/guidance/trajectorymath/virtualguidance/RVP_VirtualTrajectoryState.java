package org.ywzj.rvp.guidance.trajectorymath.virtualguidance;

import net.minecraft.world.phys.Vec3;

/**
 * 单 Tick 积分所需的不可变弹体状态。
 *
 * @param position 当前世界坐标
 * @param velocity 当前速度，单位格/Tick
 * @param xRot 当前俯仰角，单位度
 * @param yRot 当前偏航角，单位度
 * @param peakFlightSpeed 已完成航程中的最大速率，单位格/Tick
 * @param flightDistance 已累计飞行距离，单位格
 * @param flightTick 最后完成的飞行 Tick
 * @param remainingLife 剩余寿命，单位 Tick
 * @param secondPulseStartTick 第二脉冲开始的飞行 Tick；负值表示尚未开始
 */
public record RVP_VirtualTrajectoryState(Vec3 position, Vec3 velocity, float xRot, float yRot,
                                         double peakFlightSpeed, double flightDistance,
                                         int flightTick, int remainingLife, int secondPulseStartTick) {
}
