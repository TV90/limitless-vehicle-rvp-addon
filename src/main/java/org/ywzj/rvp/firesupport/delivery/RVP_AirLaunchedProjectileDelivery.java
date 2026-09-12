package org.ywzj.rvp.firesupport.delivery;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDelivery;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryContext;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryResult;
import org.ywzj.rvp.weapon.core.RVP_ProjectileChunkLoadingPolicy;
import org.ywzj.rvp.weapon.physics.RVP_UnguidedBallisticMath;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

/** 生成真实飞机并从其挂架位置投放 RVP 炸弹、导弹或火箭弹。 */
public final class RVP_AirLaunchedProjectileDelivery implements RVP_FireSupportDelivery {
    /** 投送诊断日志。 */ private static final Logger LOGGER = LogUtils.getLogger();
    /** 普通弹体使用的离散弹道航点规划实现。 */
    private static final RVP_AirstrikeRoutePlanningStrategy BALLISTIC_ROUTE_PLANNER =
            new RVP_BallisticAirstrikeRoutePlanningStrategy();
    /** GPS 主制导或末段制导弹体使用的固定前置航点规划实现。 */
    private static final RVP_AirstrikeRoutePlanningStrategy GPS_ROUTE_PLANNER =
            new RVP_GpsAirstrikeRoutePlanningStrategy();
    /** 已严格解析且冻结的空投参数。 */ private final RVP_FireSupportDeliveryTypes.AirLaunchedProjectileData data;
    /** 最近一次轮次的确定性空投解算缓存。 */ private int cachedRoundIndex = -1;
    /** 最近一次轮次的空投计划；不可达时为 null。 */ private AirPlan cachedPlan;
    /** 最近一次空投解算失败的详细原因；供任务终态日志定位航线或姿态问题。 */ private String cachedPlanFailureDetail;
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
                context.level(), context.impactPoint().x(), context.impactPoint().z())) {
            return RVP_FireSupportDeliverySupport.result(
                    RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK, null, null,
                    "空投目标 Chunk 租约已返回但当前仍未 entity-ticking；impact=" + context.impactPoint());
        }
        AirPlan plan = resolvePlan(context);
        if (plan == null) return RVP_FireSupportDeliverySupport.result(
                context.sourcePosition() == null
                        ? RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE
                        : RVP_FireSupportDeliveryResult.Status.RETRY_LATER,
                null, null, cachedPlanFailureDetail);
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
                null, null, cachedPlanFailureDetail);
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

    /** 计算挂架弹体初速度；GPS 弹继承实时载机速度，非 GPS 弹沿用既有弹道解或武器初速。 */
    private Vec3 resolveProjectileMotion(RVP_FireSupportDeliveryContext context, AirPlan plan) {
        // GPS 空射已经在计划中保留实时载机速度，交给本项目原生制导接管，禁止退化为 0.01 最小武器初速。
        if (selectRoutePlanner(context.weaponData()) instanceof RVP_GpsAirstrikeRoutePlanningStrategy) {
            return plan.motion();
        }
        if (plan.actualSolution() != null) return plan.actualSolution().initializedMotion();
        if (context.weaponData().getWeaponKind() == RVP_EnumWeaponKind.BOMB
                || context.sourceVehicle() == null) return plan.motion();
        double speed = context.weaponData().resolveMuzzleSpeed(context.weaponData().getWeaponKind());
        return speed > 0.0D ? plan.motion().normalize().scale(speed) : plan.motion();
    }

    /** 按武器制导能力选择内部策略，解算实际挂架状态或原始落点的本发空投计划。 */
    public AirPlan resolvePlan(RVP_FireSupportDeliveryContext context) {
        // 优先使用本项目控制器提供的权威姿态速度；仅在普通调用缺失时读取本体载具速度。
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
        cachedPlanFailureDetail = null;
        RVP_AirstrikeRoutePlanningStrategy planner = selectRoutePlanner(context.weaponData());
        // 调用本项目类型化航点策略：GPS 与普通弹道只在单发释放规划层分流。
        RVP_AirstrikeRoutePlanningResult result = planner.plan(context, data, currentMotion);
        if (!result.successful()) {
            cachedPlanFailureDetail = result.diagnostic();
            return null;
        }
        RVP_AirstrikeRoutePlanningResult.Plan plan = result.plan();
        if (plan == null) {
            cachedPlanFailureDetail = "空投航线策略返回成功但未提供释放计划";
            return null;
        }
        if (context.sourcePosition() == null && Double.isFinite(plan.releaseAltitudeMeters())) {
            logAltitudeClamp(context, plan.releaseAltitudeMeters());
        }
        cachedPlan = new AirPlan(plan.spawn(), plan.motion(), plan.solution(), plan.actualSolution());
        return cachedPlan;
    }

    /** 按主制导或末段制导能力选择本发内部航点策略。 */
    static RVP_AirstrikeRoutePlanningStrategy selectRoutePlanner(RVP_WeaponData weaponData) {
        // 调用本体武器制导链能力解析，同时覆盖主 GPS 与末段 GPS。
        return weaponData != null && weaponData.usesGuidanceType(RVP_EnumGuidanceType.GPS)
                ? GPS_ROUTE_PLANNER : BALLISTIC_ROUTE_PLANNER;
    }

    /** 统一记录因世界高度导致的空投高度钳制。 */
    private void logAltitudeClamp(RVP_FireSupportDeliveryContext context, double altitude) {
        if (!clampLogged && altitude + 1.0E-9D < data.releaseAltitudeAboveImpactMeters()) {
            clampLogged = true;
            LOGGER.info("炮火任务 {} 武器 {} 空投高度由 {} 钳制为 {} 格", context.missionId(),
                    context.weaponData().getWeaponId(), data.releaseAltitudeAboveImpactMeters(), altitude);
        }
    }

    /** @return 最近一次空投解算失败的详细原因，供任务管理器的 LOGGER.error 使用。 */
    public String lastPlanFailureDetail() {
        return cachedPlanFailureDetail == null ? "未提供空投解算失败详情" : cachedPlanFailureDetail;
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
