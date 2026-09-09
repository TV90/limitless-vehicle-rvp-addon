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
import org.ywzj.rvp.weapon.physics.RVP_UnguidedBallisticMath;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;

/** 生成真实飞机并从其挂架位置投放 RVP 炸弹、导弹或火箭弹。 */
public final class RVP_AirLaunchedProjectileDelivery implements RVP_FireSupportDelivery {
    /** 投送诊断日志。 */ private static final Logger LOGGER = LogUtils.getLogger();
    /** 已严格解析且冻结的空投参数。 */ private final RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData data;
    /** 最近一次轮次的确定性空投解算缓存。 */ private int cachedRoundIndex = -1;
    /** 最近一次轮次的空投计划；不可达时为 null。 */ private AirPlan cachedPlan;
    /** 实时求解缓存对应的挂架位置；位置变化时必须重新解算。 */ private Vec3 cachedSourcePosition;
    /** 实时求解缓存对应的载机速度；转弯时速度方向变化必须重新解算。 */ private Vec3 cachedSourceMotion;
    /** 是否已经为当前任务记录过高度钳制日志。 */ private boolean clampLogged;

    public RVP_AirLaunchedProjectileDelivery(RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData data) {
        if (data == null) throw new IllegalArgumentException("空投参数不能为空");
        this.data = data;
    }

    /** @return 已严格解析的空中投送配置，供任务级航线控制器读取。 */
    public RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData data() { return data; }

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
                context.sourcePosition() == null
                        ? RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE
                        : RVP_FireSupportDeliveryResult.Status.RETRY_LATER,
                null, null);
        Vec3 candidate = context.sourcePosition() == null ? plan.spawn() : context.sourcePosition();
        RVP_FireSupportDeliveryResult releaseLease = RVP_FireSupportDeliverySupport.leaseLaunchCandidate(
                context, candidate.x, candidate.z, data.preloadTicks());
        return RVP_FireSupportDeliverySupport.combine(targetLease, releaseLease);
    }

    @Override
    public RVP_FireSupportDeliveryResult deliver(RVP_FireSupportDeliveryContext context) {
        RVP_FireSupportDeliveryResult preparation = prepare(context);
        if (!preparation.prepared()) return preparation;
        AirPlan plan = resolvePlan(context);
        if (plan == null) return RVP_FireSupportDeliverySupport.result(
                context.sourcePosition() == null
                        ? RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE
                        : RVP_FireSupportDeliveryResult.Status.RETRY_LATER,
                null, null);
        Vec3 spawn = context.sourcePosition() == null ? plan.spawn() : context.sourcePosition();
        Vec3 initializedMotion = resolveProjectileMotion(context, plan);
        Vec3 spawnContextMotion = RVP_UnguidedBallisticMath.resolveSpawnContextMotion(
                context.weaponData(), context.weaponData().getWeaponKind(), initializedMotion);
        RVP_FireSupportDeliveryResult result = RVP_FireSupportDeliverySupport.spawn(
                context, spawn, initializedMotion, spawnContextMotion,
                RVP_ProjectileChunkLoadingPolicy.REMOTE_FIRE_SUPPORT);
        if (result.delivered()) RVP_FireSupportDeliverySupport.confirmLaunch(context, spawn);
        return result;
    }

    /** 计算从飞机挂架生成的实际弹体初速度；炸弹继承载机速度，其他弹种使用武器初速。 */
    private Vec3 resolveProjectileMotion(RVP_FireSupportDeliveryContext context, AirPlan plan) {
        if (plan.actualSolution() != null) return plan.actualSolution().initializedMotion();
        if (context.weaponData().getWeaponKind() == RVP_EnumWeaponKind.BOMB
                || context.sourceVehicle() == null) return plan.motion();
        double speed = context.weaponData().resolveMuzzleSpeed(context.weaponData().getWeaponKind());
        return speed > 0.0D ? plan.motion().normalize().scale(speed) : plan.motion();
    }

    /** 按实际挂架状态或原始落点解算本发空投计划。 */
    public AirPlan resolvePlan(RVP_FireSupportDeliveryContext context) {
        // 调用控制器提供的权威姿态速度；本体 VehicleMoveEvent 可能已被取消，不能再读取被本体清零的实体速度。
        Vec3 currentMotion = context.sourceMotion() != null
                ? context.sourceMotion()
                : context.sourceVehicle() == null ? null : context.sourceVehicle().getDeltaMovement();
        if (cachedRoundIndex == context.roundIndex()
                && same(cachedSourcePosition, context.sourcePosition())
                && same(cachedSourceMotion, currentMotion)) return cachedPlan;
        cachedRoundIndex = context.roundIndex();
        cachedSourcePosition = context.sourcePosition();
        cachedSourceMotion = currentMotion;
        cachedPlan = null;
        if (context.sourcePosition() != null) {
            int impactX = Mth.floor(context.impactPoint().x());
            int impactZ = Mth.floor(context.impactPoint().z());
            int groundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, impactX, impactZ);
            Vec3 target = new Vec3(context.impactPoint().x(), groundY + 0.5D, context.impactPoint().z());
            Vec3 motion = currentMotion == null ? Vec3.ZERO : currentMotion;
            RVP_FireSupportBallisticSolver.ActualAirSolution actual =
                    RVP_FireSupportBallisticSolver.solveActualAirRelease(
                            context.weaponData(), context.weaponData().getWeaponKind(),
                            context.sourcePosition(), target, motion);
            if (actual == null) return null;
            cachedPlan = new AirPlan(context.sourcePosition(), actual.initializedMotion(), null, actual);
            return cachedPlan;
        }
        int impactX = Mth.floor(context.impactPoint().x());
        int impactZ = Mth.floor(context.impactPoint().z());
        // 目标 Chunk 已就绪后读取权威高度，避免 getHeight 同步生成区块。
        int groundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, impactX, impactZ);
        // 航线中心由释放点减去 rack_offset 得到；挂架在机体下方时，机体中心会高于释放点，必须预留该高度。
        double maximumAltitude = context.level().getMaxBuildHeight() - 1.0D
                + data.rackOffset().y() - groundY;
        double altitude = Math.min(data.releaseAltitudeAboveImpactMeters(), Math.floor(maximumAltitude));
        Vec3 direction = RVP_FireSupportDeliverySupport.inboundDirection(context, data.headingJitterDegrees());
        while (altitude + 1.0E-9D >= data.minReleaseAltitudeAboveImpactMeters()) {
            // 调用本项目共享离散积分，按当前高度和具体弹种反算目标上游投放距离。
            RVP_FireSupportBallisticSolver.AirSolution solution = RVP_FireSupportBallisticSolver.solveAirRelease(
                    context.weaponData(), context.weaponData().getWeaponKind(), groundY + altitude, groundY + 0.5D,
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
                    cachedPlan = new AirPlan(spawn, direction.scale(data.carrierSpeedMetersPerTick()), solution, null);
                    return cachedPlan;
                }
            }
            altitude -= 1.0D;
        }
        return null;
    }

    /** 一发空投的确定性生成位置、初速度与预测摘要。 */
    public record AirPlan(
            /** 虚拟空中释放点。 */ Vec3 spawn,
            /** 沿独立入场方位的载机水平速度。 */ Vec3 motion,
            /** 原始一维空投预测结果；实时三维解算时为 null。 */ RVP_FireSupportBallisticSolver.AirSolution solution,
            /** 实际挂架位置下的三维预测结果；原始路线构建时为 null。 */
            RVP_FireSupportBallisticSolver.ActualAirSolution actualSolution) {}

    /** 判断实时缓存的向量是否仍然相同。 */
    private static boolean same(Vec3 first, Vec3 second) {
        if (first == null || second == null) return first == second;
        return first.distanceToSqr(second) <= 1.0E-8D;
    }
}
