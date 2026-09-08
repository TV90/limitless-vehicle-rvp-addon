package org.ywzj.rvp.firesupport.delivery;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDelivery;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryContext;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryResult;
import org.ywzj.rvp.weapon.core.RVP_ProjectileChunkLoadingPolicy;

/** 从目标区外虚拟空中释放点投放真实无制导 RVP 炸弹。 */
public final class RVP_AirLaunchedProjectileDelivery implements RVP_FireSupportDelivery {
    /** 投送诊断日志。 */ private static final Logger LOGGER = LogUtils.getLogger();
    /** 已严格解析且冻结的空投参数。 */ private final RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData data;
    /** 最近一次轮次的确定性空投解算缓存。 */ private int cachedRoundIndex = -1;
    /** 最近一次轮次的空投计划；不可达时为 null。 */ private AirPlan cachedPlan;
    /** 是否已经为当前任务记录过高度钳制日志。 */ private boolean clampLogged;

    public RVP_AirLaunchedProjectileDelivery(RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData data) {
        if (data == null) throw new IllegalArgumentException("空投参数不能为空");
        this.data = data;
    }

    @Override public ResourceLocation typeId() { return RVP_FireSupportDeliveryTypes.AIR_LAUNCHED_PROJECTILE; }
    @Override public int preloadTicks() { return data.preloadTicks(); }

    @Override
    public RVP_FireSupportDeliveryResult prepare(RVP_FireSupportDeliveryContext context) {
        RVP_FireSupportDeliveryResult targetLease = RVP_FireSupportDeliverySupport.leaseTarget(
                context, context.impactPoint().x(), context.impactPoint().z(), data.preloadTicks());
        if (targetLease.status() != RVP_FireSupportDeliveryResult.Status.PREPARED
                && targetLease.status() != RVP_FireSupportDeliveryResult.Status.TOO_EARLY
                && targetLease.status() != RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK) return targetLease;
        if (!RVP_FireSupportDeliverySupport.entityTicking(
                context.level(), context.impactPoint().x(), context.impactPoint().z())) return targetLease;
        AirPlan plan = resolvePlan(context);
        if (plan == null) return RVP_FireSupportDeliverySupport.result(
                RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE, null, null);
        RVP_FireSupportDeliveryResult releaseLease = RVP_FireSupportDeliverySupport.leaseLaunchCandidate(
                context, plan.spawn().x, plan.spawn().z, data.preloadTicks());
        return RVP_FireSupportDeliverySupport.combine(targetLease, releaseLease);
    }

    @Override
    public RVP_FireSupportDeliveryResult deliver(RVP_FireSupportDeliveryContext context) {
        RVP_FireSupportDeliveryResult preparation = prepare(context);
        if (!preparation.prepared()) return preparation;
        AirPlan plan = resolvePlan(context);
        if (plan == null) return RVP_FireSupportDeliverySupport.result(
                RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE, null, null);
        RVP_FireSupportDeliveryResult result = RVP_FireSupportDeliverySupport.spawn(
                context, plan.spawn(), plan.motion(), plan.motion(),
                RVP_ProjectileChunkLoadingPolicy.REMOTE_FIRE_SUPPORT);
        if (result.delivered()) RVP_FireSupportDeliverySupport.confirmLaunch(context, plan.spawn());
        return result;
    }

    /** 按本发落点解算平行航线上的释放点；越界时保持航向/速度并逐格降低高度。 */
    private AirPlan resolvePlan(RVP_FireSupportDeliveryContext context) {
        if (cachedRoundIndex == context.roundIndex()) return cachedPlan;
        cachedRoundIndex = context.roundIndex();
        cachedPlan = null;
        int impactX = Mth.floor(context.impactPoint().x());
        int impactZ = Mth.floor(context.impactPoint().z());
        // 目标 Chunk 已就绪后读取权威高度，避免 getHeight 同步生成区块。
        int groundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, impactX, impactZ);
        double maximumAltitude = context.level().getMaxBuildHeight() - 1.0D - groundY;
        double altitude = Math.min(data.releaseAltitudeAboveImpactMeters(), Math.floor(maximumAltitude));
        Vec3 direction = RVP_FireSupportDeliverySupport.inboundDirection(context, data.headingJitterDegrees());
        while (altitude + 1.0E-9D >= data.minReleaseAltitudeAboveImpactMeters()) {
            // 调用本项目共享 Bomb 离散积分，按当前高度反算释放点到目标的水平距离。
            RVP_FireSupportBallisticSolver.AirSolution solution = RVP_FireSupportBallisticSolver.solveAirRelease(
                    context.weaponData(), groundY + altitude, groundY + 0.5D,
                    data.carrierSpeedMetersPerTick());
            if (solution != null) {
                Vec3 spawn = new Vec3(
                        context.impactPoint().x() - direction.x * solution.releaseDistanceMeters(),
                        groundY + altitude,
                        context.impactPoint().z() - direction.z * solution.releaseDistanceMeters());
                if (RVP_FireSupportDeliverySupport.withinBorder(context.level(), spawn.x, spawn.z)) {
                    if (!clampLogged && altitude + 1.0E-9D < data.releaseAltitudeAboveImpactMeters()) {
                        clampLogged = true;
                        LOGGER.info("炮火任务 {} 武器 {} 空投高度由 {} 钳制为 {} 格", context.missionId(),
                                context.weaponData().getWeaponId(), data.releaseAltitudeAboveImpactMeters(), altitude);
                    }
                    cachedPlan = new AirPlan(spawn, direction.scale(data.carrierSpeedMetersPerTick()), solution);
                    return cachedPlan;
                }
            }
            altitude -= 1.0D;
        }
        return null;
    }

    /** 一发空投的确定性生成位置、初速度与预测摘要。 */
    private record AirPlan(
            /** 虚拟空中释放点。 */ Vec3 spawn,
            /** 沿独立入场方位的载机水平速度。 */ Vec3 motion,
            /** 纯数学预测结果。 */ RVP_FireSupportBallisticSolver.AirSolution solution) {}
}
