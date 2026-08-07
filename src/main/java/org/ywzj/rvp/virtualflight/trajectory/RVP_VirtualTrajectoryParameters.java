package org.ywzj.rvp.virtualflight.trajectory;

import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** 从当前 RVP 武器配置冻结出的单 Tick 纯积分参数。 */
public record RVP_VirtualTrajectoryParameters(
        double maxGs,
        Double cruiseAltitude,
        /* 仅随快照保存并在实体恢复时还原；纯积分器不消费模型朝向配置。 */
        boolean rotateToMotion,
        //是否开启发动机推进
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

    public static RVP_VirtualTrajectoryParameters from(RVP_WeaponData data,
                                                        int coldLaunchTimeTick, double altitude) {
        var projectile = data.getProjectileData();
        return new RVP_VirtualTrajectoryParameters(
                data.getVirtualMidcourseData().getVirtualMidcourseMaxG(),
                data.getVirtualMidcourseData().getCruiseAltitude(),
                projectile.isRotateToMotion(), data.usesPropulsion(), data.getResolvedMass(),
                data.getResolvedThrust(), data.getResolvedMotorBurnTime(),
                Math.max(data.getResolvedIgnitionDelayTick(), coldLaunchTimeTick),
                data.getResolvedDragCoefficient(), projectile.resolveAltitudeDragFactor(altitude),
                data.getGravity(), projectile.getMinSpeed(), projectile.getMaxSpeed());
    }
}
