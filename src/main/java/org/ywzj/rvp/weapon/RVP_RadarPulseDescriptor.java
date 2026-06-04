package org.ywzj.rvp.weapon;

import net.minecraft.world.phys.Vec3;

/**
 * Pulse descriptor word observed by an anti-radiation seeker.
 */
public record RVP_RadarPulseDescriptor(
        long timeOfArrivalTick,
        double pulseWidthMicroseconds,
        double angleOfArrivalDegrees,
        double carrierFrequencyMhz,
        double amplitude,
        int emitterVehicleId,
        int emitterRadarIndex,
        Vec3 emitterPosition,
        boolean lockedEmission
) {
}
