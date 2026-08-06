package org.ywzj.rvp.virtualflight.trajectory;

import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_ProjectileData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** 从当前 RVP 武器配置冻结出的单 Tick 纯积分参数。 */
public record RVP_VirtualTrajectoryParameters(
        float turningFactor, boolean rotateToMotion, boolean propulsion,
        float mass, float thrust, float motorBurnTime, int ignitionTick,
        float dragCoefficient, float altitudeDragFactor, float gravity,
        float minSpeed, float maxSpeed, Integer cruiseStartTick,
        float cruiseEndHorizontalDistance, float cruiseLevelingFactor) {

    public static RVP_VirtualTrajectoryParameters from(RVP_WeaponData data, int flightTick,
                                                        int coldLaunchTimeTick, double altitude) {
        RVP_ProjectileData projectile = data.getProjectileData();
        RVP_GuidanceData guidance = data.getGuidanceData();
        Float configured = projectile.resolveTurningFactor(flightTick);
        return new RVP_VirtualTrajectoryParameters(
                configured == null ? 0.5f : configured,
                projectile.isRotateToMotion(), data.usesPropulsion(), data.getResolvedMass(),
                data.getResolvedThrust(), data.getResolvedMotorBurnTime(),
                Math.max(data.getResolvedIgnitionDelayTick(), coldLaunchTimeTick),
                data.getResolvedDragCoefficient(), projectile.resolveAltitudeDragFactor(altitude),
                data.getGravity(), projectile.getMinSpeed(), projectile.getMaxSpeed(),
                guidance.getCruiseStartTick(), guidance.getCruiseEndHorizontalDist(),
                guidance.getCruiseLevelingFactor());
    }
}
