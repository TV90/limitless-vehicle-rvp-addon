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
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.physics.RVP_UnguidedBallisticMath;

/** 从任务默认锚点炮位发射真实地射弹体；单发不可达时回退到该落点后方的近距虚拟炮位。 */
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
                RVP_FireSupportDeliveryResult.Status.OUTSIDE_WORLD_BORDER, null, null,
                "没有找到位于世界边界内的地射候选；anchor=(" + context.anchorX() + "," + context.anchorZ()
                        + "), requestedDistance=" + data.launchDistanceMeters()
                        + ", minDistance=" + data.minLaunchDistanceMeters()
                        + ", impact=" + context.impactPoint());
        return prepareSearch(context, direction, search);
    }

    @Override
    public RVP_FireSupportDeliveryResult deliver(RVP_FireSupportDeliveryContext context) {
        RVP_FireSupportDeliveryResult preparation = prepare(context);
        if (!preparation.prepared()) return preparation;
        GroundPlan plan = cachedSearch == null ? null : cachedSearch.plan;
        if (plan == null) return unreachable(context, cachedSearch);
        RVP_FireSupportDeliveryResult result = RVP_FireSupportDeliverySupport.spawn(
                context, plan.launch(), plan.initializedMotion(), plan.spawnContextMotion(),
                RVP_ProjectileChunkLoadingPolicy.REMOTE_FIRE_SUPPORT);
        if (result.delivered()) RVP_FireSupportDeliverySupport.confirmLaunch(context, plan.launch());
        return result;
    }

    /** 推进本发候选搜索；只在落点与当前候选 Chunk 都 entity-ticking 后读取高度图和求解弹道。 */
    private RVP_FireSupportDeliveryResult prepareSearch(RVP_FireSupportDeliveryContext context, Vec3 direction,
                                                         RoundSearch search) {
        while (true) {
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
            if (search.exhausted) {
                RVP_FireSupportDeliveryResult fallback = prepareGpsVerticalFallback(context, search);
                if (fallback != null) return fallback;
                return unreachable(context, search);
            }
            GroundPlan plan = resolvePlan(context, search);
            if (plan != null) {
                search.plan = plan;
                logFallback(context, search);
                return combined;
            }
            if (!advanceCandidate(context, direction, search)) {
                // GPS 弹道全部不可解时回到最初炮位，改用竖直向上的初速度投送真实弹体。
                RVP_FireSupportDeliveryResult fallback = prepareGpsVerticalFallback(context, search);
                if (fallback != null) return fallback;
                return unreachable(context, search);
            }
        }
    }

    /** 在两个 Chunk 就绪后按当前候选发射点缓存本发高度、地表落点基准和高/低弹道分支。 */
    private GroundPlan resolvePlan(RVP_FireSupportDeliveryContext context, RoundSearch search) {
        Vec3 launchXZ = search.candidate.launchXZ;
        int launchX = Mth.floor(launchXZ.x);
        int launchZ = Mth.floor(launchXZ.z);
        int impactX = Mth.floor(context.impactPoint().x());
        int impactZ = Mth.floor(context.impactPoint().z());
        // Chunk 已由 prepare 确认 entity-ticking 后才调用权威高度图，禁止同步生成远程地形。
        int launchGroundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, launchX, launchZ);
        int impactGroundY = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, impactX, impactZ);
        Vec3 launch = new Vec3(launchXZ.x, launchGroundY + data.launchHeightAboveGroundMeters(), launchXZ.z);
        RVP_WeaponData weapon = context.weaponData();
        // 调用本体武器制导能力解析：GPS 弹的指定目标与初始反解统一落在地表，普通弹保持既有基准。
        double targetY = weapon.usesGuidanceType(RVP_EnumGuidanceType.GPS) ? impactGroundY : impactGroundY + 0.5D;
        Vec3 target = new Vec3(context.impactPoint().x(), targetY, context.impactPoint().z());
        // 调用本项目诊断型地射求解器：保留失败类别和扫描统计，供最终不可达日志定位参数问题。
        RVP_FireSupportBallisticSolver.GroundSolveDiagnostic diagnostic =
                RVP_FireSupportBallisticSolver.diagnoseGround(
                        weapon, weapon.getWeaponKind(), launch, target,
                        data.maxApexAboveImpactMeters(), data.entrySpeedMetersPerTick());
        search.lastAttempt = new GroundSolveAttempt(launch, target, diagnostic);
        search.attemptedCandidates++;
        return diagnostic.solution() == null
                ? null
                : new GroundPlan(launch, diagnostic.solution().initializedMotion(),
                diagnostic.solution().spawnContextMotion());
    }

    /**
     * GPS 地射的全部候选均无离散弹道时，在最初炮位竖直向上发射。
     * <p>生成点只取搜索开始时的最初炮位 X/Z，GPS 指定目标仍由上下文保留为原着弹点。</p>
     */
    private RVP_FireSupportDeliveryResult prepareGpsVerticalFallback(
            RVP_FireSupportDeliveryContext context, RoundSearch search) {
        RVP_WeaponData weapon = context.weaponData();
        if (!weapon.usesGuidanceType(RVP_EnumGuidanceType.GPS)) return null;

        Vec3 launchXZ = search.initialLaunchXZ;
        // 回退搜索可能已经释放最初炮位的候选租约；兜底重新申请该炮位，确保生成点可安全查询高度图。
        RVP_FireSupportDeliveryResult launchLease = RVP_FireSupportDeliverySupport.leaseLaunchCandidate(
                context, launchXZ.x, launchXZ.z, data.preloadTicks());
        RVP_FireSupportDeliveryResult targetLease = RVP_FireSupportDeliverySupport.leaseTarget(
                context, context.impactPoint().x(), context.impactPoint().z(), data.preloadTicks());
        RVP_FireSupportDeliveryResult combined = RVP_FireSupportDeliverySupport.combine(targetLease, launchLease);
        if (combined.status() != RVP_FireSupportDeliveryResult.Status.PREPARED) return combined;
        if (!RVP_FireSupportDeliverySupport.entityTicking(
                context.level(), launchXZ.x, launchXZ.z)) return combined;

        int launchX = Mth.floor(launchXZ.x);
        int launchZ = Mth.floor(launchXZ.z);
        // 当前候选 Chunk 已由 prepareSearch 确认 entity-ticking 后才查询高度图。
        int launchGroundY = context.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, launchX, launchZ);
        double spawnY = launchGroundY + data.launchHeightAboveGroundMeters();
        if (!Double.isFinite(spawnY)
                || spawnY <= launchGroundY
                || spawnY < context.level().getMinBuildHeight()
                || spawnY >= context.level().getMaxBuildHeight()) {
            return RVP_FireSupportDeliverySupport.result(
                    RVP_FireSupportDeliveryResult.Status.OUTSIDE_BUILD_HEIGHT, null,
                    new Vec3(launchXZ.x, spawnY, launchXZ.z),
                    "GPS 地射弹道无解且最初炮位没有有效竖直兜底高度；launchXZ=" + launchXZ
                            + ", launchGroundY=" + launchGroundY + ", spawnY=" + spawnY
                            + ", minBuildHeight=" + context.level().getMinBuildHeight()
                            + ", maxBuildHeight=" + context.level().getMaxBuildHeight()
                            + ", impact=" + context.impactPoint());
        }

        Vec3 initializedMotion = resolveVerticalFallbackMotion(
                weapon, weapon.getWeaponKind(), data.entrySpeedMetersPerTick());
        Vec3 spawnContextMotion = RVP_UnguidedBallisticMath.resolveSpawnContextMotion(
                weapon, weapon.getWeaponKind(), initializedMotion);
        Vec3 launch = new Vec3(launchXZ.x, spawnY, launchXZ.z);
        search.plan = new GroundPlan(launch, initializedMotion, spawnContextMotion);
        LOGGER.warn("炮火任务 {} 武器 {} 第 {} 发地射弹道全部无解，GPS 兜底改为在最初炮位竖直向上发射；"
                        + "fallbackLaunch={}, initializedMotion={}, spawnContextMotion={}, designatedTarget={}, "
                        + "lastSolve={}",
                context.missionId(), weapon.getWeaponId(), context.roundIndex(), launch,
                initializedMotion, spawnContextMotion, context.designatedTarget(), search.lastAttempt);
        return RVP_FireSupportDeliverySupport.result(
                RVP_FireSupportDeliveryResult.Status.PREPARED, null, launch,
                "GPS 地射弹道无解，已准备最初炮位竖直上发兜底；fallbackLaunch=" + launch
                        + ", initializedMotion=" + initializedMotion
                        + ", designatedTarget=" + context.designatedTarget());
    }

    /** 计算 GPS 地射兜底的世界坐标竖直向上初速度，并统一应用最小初速规则。 */
    static Vec3 resolveVerticalFallbackMotion(RVP_WeaponData weapon, RVP_EnumWeaponKind kind,
                                              double entrySpeedMetersPerTick) {
        double speed = entrySpeedMetersPerTick > 0.0D
                ? entrySpeedMetersPerTick : weapon.resolveMuzzleSpeed(kind);
        if (!Double.isFinite(speed) || speed <= 0.0D) speed = 0.01D;
        return new Vec3(0.0D, speed, 0.0D);
    }

    /** 记录地射候选全部耗尽时的可复现参数，并保持原有不可达返回状态。 */
    private RVP_FireSupportDeliveryResult unreachable(RVP_FireSupportDeliveryContext context, RoundSearch search) {
        if (search == null) {
            return RVP_FireSupportDeliverySupport.result(
                    RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE, null, null,
                    "地射候选搜索状态为空，无法建立可复现的弹道求解输入");
        }
        GroundSolveAttempt attempt = search.lastAttempt;
        String diagnostic;
        if (attempt == null) {
            diagnostic = "未形成弹道求解候选；weapon=" + context.weaponData().getWeaponId()
                    + ", kind=" + context.weaponData().getWeaponKind()
                    + ", gpsGuided=" + context.weaponData().usesGuidanceType(RVP_EnumGuidanceType.GPS)
                    + ", impact=" + context.impactPoint() + ", currentCandidate=" + search.candidate.launchXZ
                    + ", anchorDistance=" + search.anchorDistanceMeters
                    + ", attemptedCandidates=" + search.attemptedCandidates
                    + ", entrySpeedOverride=" + data.entrySpeedMetersPerTick()
                    + ", minDistance=" + data.minLaunchDistanceMeters();
        } else {
            RVP_FireSupportBallisticSolver.GroundSolveDiagnostic solver = attempt.diagnostic();
            diagnostic = "地射弹道求解失败；reason=" + solver.failureReason()
                    + ", weapon=" + context.weaponData().getWeaponId()
                    + ", kind=" + context.weaponData().getWeaponKind()
                    + ", gpsGuided=" + context.weaponData().usesGuidanceType(RVP_EnumGuidanceType.GPS)
                    + ", impact=" + context.impactPoint() + ", launch=" + attempt.launch()
                    + ", target=" + attempt.target() + ", anchorDistance=" + search.anchorDistanceMeters
                    + ", candidateDistance=" + search.candidate.distanceMeters
                    + ", attemptedCandidates=" + search.attemptedCandidates
                    + ", entrySpeedOverride=" + data.entrySpeedMetersPerTick()
                    + ", effectiveSpeed=" + solver.effectiveSpeedMetersPerTick()
                    + ", horizontalDistance=" + solver.horizontalDistanceMeters()
                    + ", evaluatedAngles=" + solver.evaluatedAngles()
                    + ", reachableEvaluations=" + solver.reachableEvaluations()
                    + ", signChanges=" + solver.signChangeCount()
                    + ", maxApex=" + data.maxApexAboveImpactMeters()
                    + ", minDistance=" + data.minLaunchDistanceMeters();
        }
        return RVP_FireSupportDeliverySupport.result(
                RVP_FireSupportDeliveryResult.Status.TRAJECTORY_UNREACHABLE, null, search.candidate.launchXZ,
                diagnostic);
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
            /** 弹体完成初始化后的初速度。 */ Vec3 initializedMotion,
            /** 传给无载具生成上下文的初速度。 */ Vec3 spawnContextMotion) {}

    /** 单个候选发射点及其相对本发落点的水平距离。 */
    private record LaunchCandidate(
            /** 候选发射点 XZ 坐标。 */ Vec3 launchXZ,
            /** 候选发射点到本发落点的水平距离，单位格。 */ double distanceMeters,
            /** 是否仍是任务锚点固定炮位。 */ boolean anchorCandidate) {}

    /** 当前轮次的可变候选搜索状态；只服务服务端 prepare/deliver 生命周期。 */
    private static final class RoundSearch {
        /** 初始锚点炮位到本发落点的水平实际距离，供回退起点和诊断使用。 */ private final double anchorDistanceMeters;
        /** 搜索开始时确定的最初炮位 X/Z；GPS 兜底必须回到该位置，而不是最后一次回退候选。 */ private final Vec3 initialLaunchXZ;
        /** 当前等待或求解中的候选发射点。 */ private LaunchCandidate candidate;
        /** 当前候选是否已经从锚点炮位回退。 */ private boolean usedFallback;
        /** 全部候选均已尝试且无解时为 true。 */ private boolean exhausted;
        /** 已经确定的本发弹道；null 表示尚未找到。 */ private GroundPlan plan;
        /** 是否已经为本发写入最终回退距离诊断日志。 */ private boolean fallbackLogged;
        /** 最近一次候选的完整解算参数和失败类别。 */ private GroundSolveAttempt lastAttempt;
        /** 已实际送入弹道求解器的候选数量。 */ private int attemptedCandidates;

        private RoundSearch(LaunchCandidate candidate) {
            this.candidate = candidate;
            this.anchorDistanceMeters = candidate.distanceMeters;
            this.initialLaunchXZ = candidate.launchXZ;
        }
    }

    /** 单个候选的世界坐标和弹道诊断。 */
    private record GroundSolveAttempt(
            /** 候选发射点，含地表高度和发射高度。 */ Vec3 launch,
            /** 本次解算使用的目标点，GPS 时为地表高度。 */ Vec3 target,
            /** 求解器失败原因及扫描统计。 */ RVP_FireSupportBallisticSolver.GroundSolveDiagnostic diagnostic) {}
}
