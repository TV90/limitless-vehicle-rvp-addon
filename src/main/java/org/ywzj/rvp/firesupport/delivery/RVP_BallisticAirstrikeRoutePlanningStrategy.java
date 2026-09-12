package org.ywzj.rvp.firesupport.delivery;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryContext;

/** 普通炸弹、导弹和火箭弹的离散弹道空投航点实现。 */
final class RVP_BallisticAirstrikeRoutePlanningStrategy implements RVP_AirstrikeRoutePlanningStrategy {
    @Override
    public RVP_AirstrikeRoutePlanningResult plan(
            RVP_FireSupportDeliveryContext context,
            RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData data,
            Vec3 sourceMotion) {
        int impactX = Mth.floor(context.impactPoint().x());
        int impactZ = Mth.floor(context.impactPoint().z());
        // 调用原版服务端高度图：目标 Chunk 已由投送准备阶段保证就绪，不会在这里同步生成区块。
        int groundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, impactX, impactZ);
        if (context.sourcePosition() != null) {
            Vec3 target = new Vec3(context.impactPoint().x(), groundY + 0.5D, context.impactPoint().z());
            Vec3 motion = sourceMotion == null ? Vec3.ZERO : sourceMotion;
            // 调用本项目三维离散弹道解算器，按实时挂架位置和载机速度修正本发初速度。
            RVP_FireSupportBallisticSolver.ActualAirSolution actual =
                    RVP_FireSupportBallisticSolver.solveActualAirRelease(
                            context.weaponData(), context.weaponData().getWeaponKind(),
                            context.sourcePosition(), target, motion);
            if (actual == null) {
                return RVP_AirstrikeRoutePlanningResult.failure(
                        "实时空投三维弹道无解；release=" + context.sourcePosition()
                                + ", target=" + target + ", carrierMotion=" + motion
                                + ", kind=" + context.weaponData().getWeaponKind()
                                + ", lifeTicks=" + context.weaponData().getLife());
            }
            return RVP_AirstrikeRoutePlanningResult.success(
                    new RVP_AirstrikeRoutePlanningResult.Plan(
                            context.sourcePosition(), actual.initializedMotion(), null, actual, Double.NaN));
        }

        // 航线中心由释放点减去挂架偏移得到，机体中心必须为挂架的负 Y 偏移预留世界高度。
        double maximumAltitude = context.level().getMaxBuildHeight() - 1.0D
                + data.rackOffset().y() - groundY;
        double altitude = Math.min(data.releaseAltitudeAboveImpactMeters(), Math.floor(maximumAltitude));
        // 调用本项目公共方向解析，保证整次任务共享请求入场方位和确定性扰动。
        Vec3 direction = RVP_FireSupportDeliverySupport.inboundDirection(
                context, data.headingJitterDegrees());
        int attemptedAltitudes = 0;
        int borderRejectedPlans = 0;
        while (altitude + 1.0E-9D >= data.minReleaseAltitudeAboveImpactMeters()) {
            attemptedAltitudes++;
            // 调用本项目共享离散积分，按当前高度和具体弹种反算目标上游投放距离。
            RVP_FireSupportBallisticSolver.AirSolution solution =
                    RVP_FireSupportBallisticSolver.solveAirRelease(
                            context.weaponData(), context.weaponData().getWeaponKind(), groundY + altitude,
                            groundY + 0.5D, data.carrierSpeedMetersPerTick());
            if (solution != null) {
                Vec3 spawn = new Vec3(
                        context.impactPoint().x() - direction.x * solution.releaseDistanceMeters(),
                        groundY + altitude,
                        context.impactPoint().z() - direction.z * solution.releaseDistanceMeters());
                // 调用本项目世界边界检查，禁止把参考释放点静默钳制到错误位置。
                if (RVP_FireSupportDeliverySupport.withinBorder(context.level(), spawn.x, spawn.z)) {
                    return RVP_AirstrikeRoutePlanningResult.success(
                            new RVP_AirstrikeRoutePlanningResult.Plan(
                                    spawn, direction.scale(data.carrierSpeedMetersPerTick()),
                                    solution, null, altitude));
                }
                borderRejectedPlans++;
            }
            altitude -= 1.0D;
        }
        return RVP_AirstrikeRoutePlanningResult.failure(
                "空投释放计划无解；impact=" + context.impactPoint()
                        + ", groundY=" + groundY + ", targetY=" + (groundY + 0.5D)
                        + ", requestedAltitude=" + data.releaseAltitudeAboveImpactMeters()
                        + ", minAltitude=" + data.minReleaseAltitudeAboveImpactMeters()
                        + ", attemptedAltitudes=" + attemptedAltitudes
                        + ", borderRejectedPlans=" + borderRejectedPlans
                        + ", carrierSpeed=" + data.carrierSpeedMetersPerTick()
                        + ", direction=" + direction);
    }
}
