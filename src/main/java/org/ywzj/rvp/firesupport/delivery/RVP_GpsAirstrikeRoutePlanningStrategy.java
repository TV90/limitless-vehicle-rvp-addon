package org.ywzj.rvp.firesupport.delivery;

import java.util.function.Predicate;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryContext;

/** GPS 主制导或末段制导弹体的固定前置空投航点实现。 */
final class RVP_GpsAirstrikeRoutePlanningStrategy implements RVP_AirstrikeRoutePlanningStrategy {
    /** 判断实时速度可用性的最小平方长度。 */
    private static final double MIN_MOTION_LENGTH_SQR = 1.0E-8D;

    @Override
    public RVP_AirstrikeRoutePlanningResult plan(
            RVP_FireSupportDeliveryContext context,
            RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData data,
            Vec3 sourceMotion) {
        // 调用本项目公共方向解析，保证 GPS 航点与任务的入场方位及确定性扰动一致。
        Vec3 direction = RVP_FireSupportDeliverySupport.inboundDirection(
                context, data.headingJitterDegrees());
        if (context.sourcePosition() != null) {
            // GPS 弹不拟合无制导落点；直接继承控制器维护的实时载机速度，异常时回退配置航速。
            Vec3 motion = resolveInitialMotion(sourceMotion, direction, data.carrierSpeedMetersPerTick());
            return RVP_AirstrikeRoutePlanningResult.success(
                    new RVP_AirstrikeRoutePlanningResult.Plan(
                            context.sourcePosition(), motion, null, null, Double.NaN));
        }

        int impactX = Mth.floor(context.impactPoint().x());
        int impactZ = Mth.floor(context.impactPoint().z());
        // 调用原版服务端高度图：目标 Chunk 已由投送准备阶段保证就绪，不会在这里同步生成区块。
        int groundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, impactX, impactZ);
        // 航线中心由释放点减去挂架偏移得到，机体中心必须为挂架的负 Y 偏移预留世界高度。
        double maximumAltitude = context.level().getMaxBuildHeight() - 1.0D
                + data.rackOffset().y() - groundY;
        double altitude = Math.min(data.releaseAltitudeAboveImpactMeters(), Math.floor(maximumAltitude));
        return planReference(context.impactPoint().x(), context.impactPoint().z(), groundY,
                data.releaseAltitudeAboveImpactMeters(), altitude,
                data.minReleaseAltitudeAboveImpactMeters(), direction,
                data.gpsReleaseDistanceMeters(), data.carrierSpeedMetersPerTick(),
                // 调用本项目世界边界检查，固定 GPS 释放距离不可被静默缩短或钳制。
                spawn -> RVP_FireSupportDeliverySupport.withinBorder(context.level(), spawn.x, spawn.z));
    }

    /** 用已解析的地表、高度和边界判定建立 GPS 参考航点；拆为纯逻辑入口供回归测试覆盖。 */
    static RVP_AirstrikeRoutePlanningResult planReference(
            double impactX, double impactZ, double groundY,
            double requestedAltitude, double clampedAltitude, double minimumAltitude,
            Vec3 inboundDirection, double releaseDistance, double carrierSpeed,
            Predicate<Vec3> borderCheck) {
        if (clampedAltitude + 1.0E-9D < minimumAltitude) {
            return RVP_AirstrikeRoutePlanningResult.failure(
                    "GPS 空投释放高度低于最小值；impact=(" + impactX + "," + impactZ + ")"
                            + ", groundY=" + groundY + ", requestedAltitude=" + requestedAltitude
                            + ", clampedAltitude=" + clampedAltitude + ", minAltitude=" + minimumAltitude);
        }
        Vec3 spawn = resolveReleasePosition(impactX, impactZ, groundY, clampedAltitude,
                inboundDirection, releaseDistance);
        if (borderCheck == null || !borderCheck.test(spawn)) {
            return RVP_AirstrikeRoutePlanningResult.failure(
                    "GPS 空投固定前置释放点超出世界边界；impact=(" + impactX + "," + impactZ + ")"
                            + ", release=" + spawn + ", gpsReleaseDistance=" + releaseDistance
                            + ", direction=" + inboundDirection);
        }
        return RVP_AirstrikeRoutePlanningResult.success(
                new RVP_AirstrikeRoutePlanningResult.Plan(
                        spawn, inboundDirection.scale(carrierSpeed), null, null, clampedAltitude));
    }

    /** 按目标、入场方向和固定前置距离计算 GPS 参考释放点。 */
    static Vec3 resolveReleasePosition(double impactX, double impactZ, double groundY, double altitude,
                                       Vec3 inboundDirection, double releaseDistance) {
        return new Vec3(impactX - inboundDirection.x * releaseDistance, groundY + altitude,
                impactZ - inboundDirection.z * releaseDistance);
    }

    /** 优先保留实时载机三维速度；无有效速度时才回退配置的水平入场速度。 */
    static Vec3 resolveInitialMotion(Vec3 sourceMotion, Vec3 inboundDirection, double carrierSpeed) {
        if (sourceMotion != null && finite(sourceMotion)
                && sourceMotion.lengthSqr() > MIN_MOTION_LENGTH_SQR) {
            return sourceMotion;
        }
        Vec3 horizontal = new Vec3(inboundDirection.x, 0.0D, inboundDirection.z);
        return horizontal.lengthSqr() > MIN_MOTION_LENGTH_SQR
                ? horizontal.normalize().scale(carrierSpeed) : new Vec3(0.0D, 0.0D, carrierSpeed);
    }

    /** 判断向量三个分量是否均为有限值。 */
    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
