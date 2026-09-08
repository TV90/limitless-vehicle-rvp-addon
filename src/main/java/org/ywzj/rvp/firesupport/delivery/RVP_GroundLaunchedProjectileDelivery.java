package org.ywzj.rvp.firesupport.delivery;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDelivery;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryContext;
import org.ywzj.rvp.firesupport.api.RVP_FireSupportDeliveryResult;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportSpawnChunkLeaseManager;
import org.ywzj.rvp.weapon.core.RVP_ProjectileChunkLoadingPolicy;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** 从任务默认锚点炮位发射真实无制导炮弹或火箭；单发不可达时回退到该落点后方的近距虚拟炮位。 */
public final class RVP_GroundLaunchedProjectileDelivery implements RVP_FireSupportDelivery {
    /** 投送诊断日志。 */ private static final Logger LOGGER = LogUtils.getLogger();
    /** 已严格解析且冻结的地射参数。 */ private final RVP_FireSupportDeliveryTypes.GroundLaunchedProjectileData data;
    /** 当前缓存搜索所属的任务轮次；切换轮次后必须重新从锚点炮位开始。 */ private int cachedRoundIndex = -1;
    /** 当前轮次的候选搜索、最终弹道或耗尽状态；prepare 与 deliver 必须共用。 */ private RoundSearch cachedSearch;
    /** 是否已经为当前任务记录过锚点炮位的世界边界距离钳制。 */ private boolean anchorClampLogged;

    public RVP_GroundLaunchedProjectileDelivery(RVP_FireSupportDeliveryTypes.GroundLaunchedProjectileData data) {
        if (data == null) throw new IllegalArgumentException("地射投送参数不能为空");
        this.data = data;
    }

    @Override public ResourceLocation typeId() { return RVP_FireSupportDeliveryTypes.GROUND_LAUNCHED_PROJECTILE; }
    @Override public int preloadTicks() { return data.preloadTicks(); }

    @Override
    public RVP_FireSupportDeliveryResult prepare(RVP_FireSupportDeliveryContext context) {
        Vec3 direction = RVP_FireSupportDeliverySupport.inboundDirection(context, data.headingJitterDegrees());
        RoundSearch search = resolveSearch(context, direction);
        if (search == null) return RVP_FireSupportDeliverySupport.result(
                RVP_FireSupportDeliveryResult.Status.OUTSIDE_WORLD_BORDER, null, null);
        return prepareSearch(context, direction, search);
    }

    @Override
    public RVP_FireSupportDeliveryResult deliver(RVP_FireSupportDeliveryContext context) {
        RVP_FireSupportDeliveryResult preparation = prepare(context);
        if (!preparation.prepared()) return preparation;
        GroundPlan plan = cachedSearch == null ? null : cachedSearch.plan;
        if (plan == null) return RVP_FireSupportDeliverySupport.result(
                RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE, null, null);
        RVP_FireSupportDeliveryResult result = RVP_FireSupportDeliverySupport.spawn(
                context, plan.launch(), plan.solution().initializedMotion(),
                plan.solution().spawnContextMotion(), RVP_ProjectileChunkLoadingPolicy.REMOTE_FIRE_SUPPORT);
        if (result.delivered()) RVP_FireSupportDeliverySupport.confirmLaunch(context, plan.launch());
        return result;
    }

    /** 推进本发候选搜索；只在落点与当前候选 Chunk 都 entity-ticking 后读取高度图和求解弹道。 */
    private RVP_FireSupportDeliveryResult prepareSearch(RVP_FireSupportDeliveryContext context, Vec3 direction,
                                                         RoundSearch search) {
        while (!search.exhausted) {
            RVP_FireSupportDeliveryResult targetLease = RVP_FireSupportDeliverySupport.leaseTarget(
                    context, context.impactPoint().x(), context.impactPoint().z(), data.preloadTicks());
            RVP_FireSupportDeliveryResult launchLease = RVP_FireSupportDeliverySupport.leaseLaunchCandidate(
                    context, search.candidate.launchXZ.x, search.candidate.launchXZ.z, data.preloadTicks());
            RVP_FireSupportDeliveryResult combined = RVP_FireSupportDeliverySupport.combine(targetLease, launchLease);
            if (combined.status() != RVP_FireSupportDeliveryResult.Status.PREPARED
                    && combined.status() != RVP_FireSupportDeliveryResult.Status.TOO_EARLY
                    && combined.status() != RVP_FireSupportDeliveryResult.Status.WAITING_FOR_CHUNK) return combined;
            if (!RVP_FireSupportDeliverySupport.entityTicking(
                    context.level(), context.impactPoint().x(), context.impactPoint().z())
                    || !RVP_FireSupportDeliverySupport.entityTicking(
                    context.level(), search.candidate.launchXZ.x, search.candidate.launchXZ.z)) return combined;
            if (search.plan != null) return combined;
            GroundPlan plan = resolvePlan(context, search.candidate.launchXZ);
            if (plan != null) {
                search.plan = plan;
                logFallback(context, search);
                return combined;
            }
            if (!advanceCandidate(context, direction, search)) {
                return RVP_FireSupportDeliverySupport.result(
                        RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE, null, search.candidate.launchXZ);
            }
        }
        return RVP_FireSupportDeliverySupport.result(
                RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE, null, search.candidate.launchXZ);
    }

    /** 在两个 Chunk 就绪后按当前候选发射点缓存本发高度、地表落点基准和高/低弹道分支。 */
    private GroundPlan resolvePlan(RVP_FireSupportDeliveryContext context, Vec3 launchXZ) {
        int launchX = Mth.floor(launchXZ.x);
        int launchZ = Mth.floor(launchXZ.z);
        int impactX = Mth.floor(context.impactPoint().x());
        int impactZ = Mth.floor(context.impactPoint().z());
        // Chunk 已由 prepare 确认 entity-ticking 后才调用权威高度图，禁止同步生成远程地形。
        int launchGroundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, launchX, launchZ);
        int impactGroundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, impactX, impactZ);
        Vec3 launch = new Vec3(launchXZ.x, launchGroundY + data.launchHeightAboveGroundMeters(), launchXZ.z);
        RVP_WeaponData weapon = context.weaponData();
        // 地射反解始终以地表落点为目标；近地引信由实体飞行期的扫掠逻辑独立判定，不能抬高反解终点。
        Vec3 target = new Vec3(context.impactPoint().x(), impactGroundY + 0.5D, context.impactPoint().z());
        // 调用本项目共享离散弹道求解器，优先高弹道并按配置顶高回退低弹道。
        RVP_FireSupportBallisticSolver.GroundSolution solution = RVP_FireSupportBallisticSolver.solveGround(
                weapon, weapon.getWeaponKind(), launch, target, data.maxApexAboveImpactMeters());
        return solution == null ? null : new GroundPlan(launch, target, solution);
    }

    /** 为新轮次创建锚点候选；边界不足时保持既有行为，沿来向逐格缩短。 */
    private RoundSearch resolveSearch(RVP_FireSupportDeliveryContext context, Vec3 inboundDirection) {
        if (cachedSearch != null && cachedRoundIndex == context.roundIndex()) return cachedSearch;
        double distance = Math.floor(data.launchDistanceMeters());
        while (distance + 1.0E-9D >= data.minLaunchDistanceMeters()) {
            Vec3 launchXZ = RVP_GroundLaunchCandidateResolver.fromAnchor(
                    context.anchorX(), context.anchorZ(), inboundDirection, distance);
            if (RVP_FireSupportDeliverySupport.withinBorder(context.level(), launchXZ.x, launchXZ.z)) {
                if (!anchorClampLogged && distance + 1.0E-9D < data.launchDistanceMeters()) {
                    anchorClampLogged = true;
                    LOGGER.info("炮火任务 {} 武器 {} 地射距离由 {} 钳制为 {} 格", context.missionId(),
                            context.weaponData().getWeaponId(), data.launchDistanceMeters(), distance);
                }
                cachedRoundIndex = context.roundIndex();
                cachedSearch = new RoundSearch(new LaunchCandidate(launchXZ,
                        RVP_GroundLaunchCandidateResolver.horizontalDistance(
                                launchXZ, context.impactPoint().x(), context.impactPoint().z()), true));
                return cachedSearch;
            }
            distance -= 1.0D;
        }
        cachedRoundIndex = -1;
        cachedSearch = null;
        return null;
    }

    /** 在锚点候选无解后按本发落点建立标称距离候选，再以 16 格步长逐次缩短。 */
    private boolean advanceCandidate(RVP_FireSupportDeliveryContext context, Vec3 inboundDirection,
                                     RoundSearch search) {
        double distance = search.candidate.anchorCandidate
                ? RVP_GroundLaunchCandidateResolver.initialFallbackDistance(search.candidate.launchXZ,
                context.impactPoint().x(), context.impactPoint().z(), data.launchDistanceMeters())
                : RVP_GroundLaunchCandidateResolver.nextFallbackDistance(
                search.candidate.distanceMeters, data.minLaunchDistanceMeters());
        while (Double.isFinite(distance)) {
            Vec3 launchXZ = RVP_GroundLaunchCandidateResolver.fromImpact(
                    context.impactPoint().x(), context.impactPoint().z(), inboundDirection, distance);
            if (RVP_FireSupportDeliverySupport.withinBorder(context.level(), launchXZ.x, launchXZ.z)
                    && !RVP_GroundLaunchCandidateResolver.sameXZ(search.candidate.launchXZ, launchXZ)) {
                replaceCandidateLease(context, search.candidate.launchXZ, launchXZ);
                search.candidate = new LaunchCandidate(launchXZ, distance, false);
                search.usedFallback = true;
                return true;
            }
            distance = RVP_GroundLaunchCandidateResolver.nextFallbackDistance(
                    distance, data.minLaunchDistanceMeters());
        }
        search.exhausted = true;
        return false;
    }

    /** 切换候选时只释放已经废弃、且不与目标或新候选重合的发射 Chunk 账本。 */
    private void replaceCandidateLease(RVP_FireSupportDeliveryContext context, Vec3 previousLaunchXZ,
                                       Vec3 nextLaunchXZ) {
        ChunkPos previousChunk = new ChunkPos(Mth.floor(previousLaunchXZ.x) >> 4, Mth.floor(previousLaunchXZ.z) >> 4);
        ChunkPos nextChunk = new ChunkPos(Mth.floor(nextLaunchXZ.x) >> 4, Mth.floor(nextLaunchXZ.z) >> 4);
        ChunkPos impactChunk = new ChunkPos(Mth.floor(context.impactPoint().x()) >> 4,
                Mth.floor(context.impactPoint().z()) >> 4);
        if (RVP_GroundLaunchCandidateResolver.shouldReleaseSupersededLaunchChunk(
                previousChunk, nextChunk, impactChunk)) {
            RVP_FireSupportSpawnChunkLeaseManager.releaseLaunchCandidate(
                    context.level(), context.missionId(), previousChunk);
        }
    }

    /** 首次找到回退弹道时记录一次可操作诊断，避免为每个被放弃候选刷屏。 */
    private void logFallback(RVP_FireSupportDeliveryContext context, RoundSearch search) {
        if (!search.usedFallback || search.fallbackLogged) return;
        search.fallbackLogged = true;
        LOGGER.info("炮火任务 {} 武器 {} 第 {} 发地射锚点实际距离 {} 格不可达，回退为 {} 格", context.missionId(),
                context.weaponData().getWeaponId(), context.roundIndex(), search.anchorDistanceMeters,
                search.candidate.distanceMeters);
    }

    /** 一发提前冻结的已选炮位、解算终点与初速度。 */
    private record GroundPlan(
            /** 真实弹体生成点。 */ Vec3 launch,
            /** 目标地表上方 0.5 格的弹道落点基准；近地引信实际起爆位置由实体运行时决定。 */ Vec3 target,
            /** 高/低弹道选择与初速度。 */ RVP_FireSupportBallisticSolver.GroundSolution solution) {}

    /** 单个候选发射点及其相对本发落点的水平距离。 */
    private record LaunchCandidate(
            /** 候选发射点 XZ 坐标。 */ Vec3 launchXZ,
            /** 候选发射点到本发落点的水平距离，单位格。 */ double distanceMeters,
            /** 是否仍是任务锚点固定炮位。 */ boolean anchorCandidate) {}

    /** 当前轮次的可变候选搜索状态；只服务服务端 prepare/deliver 生命周期。 */
    private static final class RoundSearch {
        /** 初始锚点炮位到本发落点的水平实际距离，供回退起点和诊断使用。 */ private final double anchorDistanceMeters;
        /** 当前等待或求解中的候选发射点。 */ private LaunchCandidate candidate;
        /** 当前候选是否已经从锚点炮位回退。 */ private boolean usedFallback;
        /** 全部候选均已尝试且无解时为 true。 */ private boolean exhausted;
        /** 已经确定的本发弹道；null 表示尚未找到。 */ private GroundPlan plan;
        /** 是否已经为本发写入最终回退距离诊断日志。 */ private boolean fallbackLogged;

        private RoundSearch(LaunchCandidate candidate) {
            this.candidate = candidate;
            this.anchorDistanceMeters = candidate.distanceMeters;
        }
    }
}
