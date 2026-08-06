package org.ywzj.rvp.virtualflight.trajectory;

import net.minecraft.world.phys.Vec3;

/** 单 Tick 积分所需的不可变弹体状态；flightTick 表示最后完成的飞行 Tick。 */
public record RVP_VirtualTrajectoryState(Vec3 position, Vec3 velocity, float xRot, float yRot,
                                         double peakFlightSpeed, double flightDistance,
                                         int flightTick, int remainingLife, int secondPulseStartTick) {
}
