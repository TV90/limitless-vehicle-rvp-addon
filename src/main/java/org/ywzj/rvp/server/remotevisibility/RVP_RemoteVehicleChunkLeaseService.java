package org.ywzj.rvp.server.remotevisibility;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.config.RVP_CommonConfig.ChunkLoadingMode;
import org.ywzj.rvp.config.RVP_CommonConfig.VisibilityMode;
import org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.uav.RVP_LinkedUavStateTable;
import org.ywzj.rvp.util.RVP_ChunkPathLoadManager;
import org.ywzj.rvp.util.RVP_ChunkPathLoader;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 对服务端最终授权的远距载具执行每维度稳定预算和低优先级路径租约提交。 */
public final class RVP_RemoteVehicleChunkLeaseService {
    /** 租约统计日志。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 水平速度平方低于该值时视为静止。 */
    static final double MOVING_THRESHOLD_SQ = 1.0E-8D;
    /** 调试统计聚合输出周期，单位服务器 Tick。 */
    private static final int STATS_LOG_INTERVAL_TICKS = 200;
    /** 按服务器实例隔离上一 Tick 选择和统计状态。 */
    private static final Map<MinecraftServer, ServerState> SERVER_STATES = new IdentityHashMap<>();

    /** 工具服务不允许实例化。 */
    private RVP_RemoteVehicleChunkLeaseService() {
    }

    /**
     * 使用最近一次视觉授权集合刷新所有维度的载具租约。
     *
     * <p>本方法由视觉同步服务在每个 ServerTick END 调用；视觉授权低频重算，载具当前位置和
     * 速度路径则每 Tick 重算，避免移动载具在两个快照周期之间离开已保护路径。</p>
     */
    public static void tick(
            MinecraftServer server,
            Map<ResourceLocation, List<AuthorizedTarget>> authorizedByDimension) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(authorizedByDimension, "authorizedByDimension");
        ServerState state = SERVER_STATES.computeIfAbsent(server, ignored -> new ServerState());
        Set<ResourceLocation> visitedDimensions = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceLocation dimension = level.dimension().location();
            visitedDimensions.add(dimension);
            List<AuthorizedTarget> authorized = authorizedByDimension.getOrDefault(dimension, List.of());
            processDimension(server, level, authorized, state);
        }
        state.previouslySelected.keySet().retainAll(visitedDimensions);
        state.statistics.keySet().retainAll(visitedDimensions);
        maybeLogStatistics(server, state);
    }

    /** 返回当前服务器每维度的只读租约统计快照。 */
    public static Map<ResourceLocation, DimensionStatistics> getStatistics(@Nullable MinecraftServer server) {
        ServerState state = server == null ? null : SERVER_STATES.get(server);
        return state == null ? Map.of() : Map.copyOf(state.statistics);
    }

    /** 服务器停止时释放静态状态；临时 Ticket 由世界关闭或原生命周期处理。 */
    public static void clear(@Nullable MinecraftServer server) {
        if (server != null) {
            SERVER_STATES.remove(server);
        }
    }

    /** 执行单维度实体解析、模式过滤、稳定选择与路径提交。 */
    private static void processDimension(
            MinecraftServer server,
            ServerLevel level,
            List<AuthorizedTarget> authorized,
            ServerState state) {
        ResourceLocation dimension = level.dimension().location();
        ChunkLoadingMode mode = RVP_CommonConfig.getRemoteVehicleChunkLoadingMode();
        boolean visualEnabled = RVP_CommonConfig.isRemoteVehicleRenderingEnabled()
                && RVP_CommonConfig.getRemoteVehicleVisibilityMode() != VisibilityMode.OFF;
        int maximumVehicles = RVP_CommonConfig.getRemoteVehicleMaxChunkLoadedVehiclesPerDimension();
        if (!visualEnabled || mode == ChunkLoadingMode.OFF || maximumVehicles <= 0 || authorized.isEmpty()) {
            state.previouslySelected.put(dimension, Set.of());
            state.statistics.put(dimension, new DimensionStatistics(
                    authorized.size(), 0, 0, 0, 0, 0, 0));
            return;
        }

        Set<UUID> previous = state.previouslySelected.getOrDefault(dimension, Set.of());
        Map<UUID, ResolvedCandidate> resolvedByUuid = new LinkedHashMap<>();
        for (AuthorizedTarget target : authorized) {
            Entity entity = level.getEntity(target.vehicleUuid());
            if (!(entity instanceof AbstractVehicle vehicle)
                    || vehicle.getId() != target.entityId()
                    || vehicle.isRemoved()
                    || !vehicle.isAlive()
                    || RVP_RemoteVehicleVisibilityPolicy.classify(vehicle).isEmpty()) {
                continue;
            }
            double nearestObserverDistanceSq = nearestActiveObserverDistanceSq(
                    server, level, vehicle, target.observerUuids());
            if (!Double.isFinite(nearestObserverDistanceSq)) {
                continue;
            }
            double horizontalMotionSq = vehicle.getDeltaMovement().horizontalDistanceSqr();
            boolean moving = Double.isFinite(horizontalMotionSq) && horizontalMotionSq > MOVING_THRESHOLD_SQ;
            boolean aiOrUav = vehicle.getDriver() instanceof GunnerEntity
                    || vehicle.uav
                    || RVP_LinkedUavStateTable.isDeployableUavInstance(vehicle);
            LeaseCandidate facts = new LeaseCandidate(
                    vehicle.getUUID(), vehicle.getId(), previous.contains(vehicle.getUUID()), moving,
                    nearestObserverDistanceSq, aiOrUav);
            resolvedByUuid.put(vehicle.getUUID(), new ResolvedCandidate(vehicle, facts));
        }

        List<LeaseCandidate> allFacts = resolvedByUuid.values().stream()
                .map(ResolvedCandidate::facts)
                .toList();
        List<LeaseCandidate> selectedFacts = selectForBudget(mode, maximumVehicles, allFacts);
        int eligibleCount = (int) allFacts.stream().filter(candidate -> modeAllows(mode, candidate)).count();
        Set<UUID> selectedUuids = new LinkedHashSet<>();
        int plannedChunkCount = 0;
        int grantedChunkCount = 0;
        int pathBudgetExhaustedVehicleCount = 0;
        int lookAheadTicks = RVP_CommonConfig.getRemoteVehicleChunkLookAheadTicks();
        for (LeaseCandidate selected : selectedFacts) {
            ResolvedCandidate resolved = resolvedByUuid.get(selected.vehicleUuid());
            if (resolved == null) {
                continue;
            }
            AbstractVehicle vehicle = resolved.vehicle();
            List<ChunkPos> plannedPath = RVP_ChunkPathLoader.planLeasePath(
                    vehicle.position(), vehicle.getDeltaMovement(), lookAheadTicks);
            if (plannedPath.isEmpty()) {
                continue;
            }
            List<ChunkPos> currentTickPath = lookAheadTicks <= 0
                    ? plannedPath
                    : RVP_ChunkPathLoader.planLeasePath(vehicle.position(), vehicle.getDeltaMovement(), 1);
            ChunkPos currentTickEnd = currentTickPath.isEmpty()
                    ? vehicle.chunkPosition()
                    : currentTickPath.get(currentTickPath.size() - 1);
            // 调用 RVP 路径 Ticket 管理器，以最低优先级提交载具当前区块和速度前探连续路径。
            RVP_ChunkPathLoadManager.PathRequestSnapshot snapshot =
                    RVP_ChunkPathLoadManager.submitPathRequest(
                            vehicle,
                            plannedPath,
                            currentTickEnd,
                            RVP_ChunkPathLoadManager.RequestPriority.REMOTE_VEHICLE);
            selectedUuids.add(vehicle.getUUID());
            plannedChunkCount += snapshot.plannedChunkCount();
            grantedChunkCount += snapshot.requestedChunkCount();
            if (snapshot.budgetExhausted()) {
                pathBudgetExhaustedVehicleCount++;
            }
        }
        state.previouslySelected.put(dimension, Set.copyOf(selectedUuids));
        state.statistics.put(dimension, new DimensionStatistics(
                authorized.size(),
                eligibleCount,
                selectedUuids.size(),
                Math.max(0, eligibleCount - selectedUuids.size()),
                plannedChunkCount,
                grantedChunkCount,
                pathBudgetExhaustedVehicleCount));
    }

    /** 返回目标到仍在线且仍处于同维度的最近授权观察者的水平距离平方。 */
    private static double nearestActiveObserverDistanceSq(
            MinecraftServer server,
            ServerLevel level,
            AbstractVehicle vehicle,
            Set<UUID> observerUuids) {
        double nearest = Double.POSITIVE_INFINITY;
        for (UUID observerUuid : observerUuids) {
            ServerPlayer observer = server.getPlayerList().getPlayer(observerUuid);
            if (observer == null || observer.serverLevel() != level) {
                continue;
            }
            double dx = observer.getX() - vehicle.getX();
            double dz = observer.getZ() - vehicle.getZ();
            double distanceSq = dx * dx + dz * dz;
            if (Double.isFinite(distanceSq)) {
                nearest = Math.min(nearest, distanceSq);
            }
        }
        return nearest;
    }

    /**
     * 对单维度租约事实执行模式过滤和稳定数量预算。
     *
     * <p>排序依次为已有租约、正在运动、最近授权观察者距离和实体 ID。</p>
     */
    static List<LeaseCandidate> selectForBudget(
            ChunkLoadingMode mode,
            int maximumVehicles,
            List<LeaseCandidate> candidates) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(candidates, "candidates");
        if (mode == ChunkLoadingMode.OFF || maximumVehicles <= 0) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> modeAllows(mode, candidate))
                .sorted(Comparator
                        .comparing((LeaseCandidate candidate) -> !candidate.previouslyLeased())
                        .thenComparing(candidate -> !candidate.moving())
                        .thenComparingDouble(LeaseCandidate::nearestObserverDistanceSq)
                        .thenComparingInt(LeaseCandidate::entityId))
                .limit(maximumVehicles)
                .toList();
    }

    /** 判断租约模式是否接受该候选。 */
    private static boolean modeAllows(ChunkLoadingMode mode, LeaseCandidate candidate) {
        return mode == ChunkLoadingMode.VISIBLE_TARGETS
                || mode == ChunkLoadingMode.AI_UAV_ONLY && candidate.aiOrUav();
    }

    /** 在生命周期调试开启时每 200 Tick 输出当前每维度的紧凑统计。 */
    private static void maybeLogStatistics(MinecraftServer server, ServerState state) {
        int tick = server.getTickCount();
        if (!RVP_ProjectileLifecycleDebug.isEnabled()
                || tick <= 0
                || tick % STATS_LOG_INTERVAL_TICKS != 0) {
            return;
        }
        for (Map.Entry<ResourceLocation, DimensionStatistics> entry : state.statistics.entrySet()) {
            DimensionStatistics statistics = entry.getValue();
            if (statistics.authorizedTargetCount() == 0 && statistics.selectedLeaseCount() == 0) {
                continue;
            }
            LOGGER.info(
                    "[RVP][RemoteVehicleLease] dimension={} authorized={} eligible={} selected={} "
                            + "dimensionBudgetDropped={} plannedChunks={} grantedChunks={} pathBudgetExhaustedVehicles={}",
                    entry.getKey(), statistics.authorizedTargetCount(), statistics.eligibleTargetCount(),
                    statistics.selectedLeaseCount(), statistics.dimensionBudgetDroppedCount(),
                    statistics.plannedChunkCount(), statistics.grantedChunkCount(),
                    statistics.pathBudgetExhaustedVehicleCount());
        }
    }

    /**
     * 视觉同步服务交付给租约服务的单目标授权并集。
     *
     * @param vehicleUuid 目标载具 UUID
     * @param entityId 目标载具当前实体 ID
     * @param observerUuids 至少一名最终获授权观察者的 UUID 集合
     */
    public record AuthorizedTarget(UUID vehicleUuid, int entityId, Set<UUID> observerUuids) {
        public AuthorizedTarget {
            Objects.requireNonNull(vehicleUuid, "vehicleUuid");
            observerUuids = Set.copyOf(Objects.requireNonNull(observerUuids, "observerUuids"));
            if (entityId < 0 || observerUuids.isEmpty()) {
                throw new IllegalArgumentException("Invalid remote vehicle lease authorization");
            }
        }
    }

    /**
     * 纯租约选择事实。
     *
     * @param vehicleUuid 目标载具 UUID
     * @param entityId 目标实体 ID
     * @param previouslyLeased 上一 Tick 是否位于维度租约预算内
     * @param moving 是否存在有效水平运动
     * @param nearestObserverDistanceSq 到最近有效授权观察者的水平距离平方
     * @param aiOrUav 是否为 Gunner AI 驾驶载具或 UAV
     */
    record LeaseCandidate(
            UUID vehicleUuid,
            int entityId,
            boolean previouslyLeased,
            boolean moving,
            double nearestObserverDistanceSq,
            boolean aiOrUav) {
        LeaseCandidate {
            Objects.requireNonNull(vehicleUuid, "vehicleUuid");
            if (entityId < 0 || !Double.isFinite(nearestObserverDistanceSq)
                    || nearestObserverDistanceSq < 0.0D) {
                throw new IllegalArgumentException("Invalid remote vehicle lease candidate");
            }
        }
    }

    /**
     * 单维度最近一次租约处理统计。
     *
     * @param authorizedTargetCount 视觉服务授权目标并集数量
     * @param eligibleTargetCount 租约模式过滤后的候选数量
     * @param selectedLeaseCount 维度载具预算内实际提交数量
     * @param dimensionBudgetDroppedCount 因维度载具数量上限淘汰的候选数量
     * @param plannedChunkCount 本 Tick 所有已选载具规划区块总数
     * @param grantedChunkCount 提交时管理器返回的连续获批区块总数
     * @param pathBudgetExhaustedVehicleCount 管理器上轮路径预算不足的载具数量
     */
    public record DimensionStatistics(
            int authorizedTargetCount,
            int eligibleTargetCount,
            int selectedLeaseCount,
            int dimensionBudgetDroppedCount,
            int plannedChunkCount,
            int grantedChunkCount,
            int pathBudgetExhaustedVehicleCount) {
    }

    /** 已解析实体与对应纯选择事实。 */
    private record ResolvedCandidate(AbstractVehicle vehicle, LeaseCandidate facts) {
    }

    /** 单个服务器的上一 Tick 选择和最新统计。 */
    private static final class ServerState {
        /** 每维度上一 Tick 位于载具数量预算内的 UUID 集合。 */
        private final Map<ResourceLocation, Set<UUID>> previouslySelected = new LinkedHashMap<>();
        /** 每维度最近一次处理统计。 */
        private final Map<ResourceLocation, DimensionStatistics> statistics = new LinkedHashMap<>();
    }
}
