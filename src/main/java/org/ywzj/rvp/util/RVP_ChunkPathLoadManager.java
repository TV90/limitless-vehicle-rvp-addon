package org.ywzj.rvp.util;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务器级动态路径 Ticket 管理器。
 *
 * <p>实体 Tick 只提交下一 Tick 所需路径；管理器在 ServerTick START 统一刷新已有 Ticket，并按
 * “等待弹体 → 活动弹体 → 固定翼”的优先级和同级轮转顺序分配新增预算。所有 Ticket 都是本体
 * 同参数的临时 {@link TicketType#POST_TELEPORT} Ticket，路径滚走或实体停止提交后自然过期。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ChunkPathLoadManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每个服务器 Tick 最多新增的路径中心 Ticket 数。 */
    public static final int GLOBAL_NEW_CHUNK_REQUESTS_PER_TICK = 32;
    /** 单实体单次提交允许的最大连续路径区块数。 */
    public static final int MAX_CHUNKS_PER_ENTITY_TICK = 64;
    /** 与本体 EntityUtil.keepChunkLoaded(...) 保持一致的 Ticket 距离参数。 */
    private static final int POST_TELEPORT_TICKET_LEVEL = 3;
    /** 汇总统计日志周期，单位为服务器 Tick。 */
    private static final int STATS_LOG_INTERVAL_TICKS = 200;

    /** 以服务器实例隔离预算和实体状态；ServerStoppedEvent 会主动清理。 */
    private static final Map<MinecraftServer, ServerState> SERVER_STATES = new IdentityHashMap<>();
    /** 周期汇总日志默认关闭；统计快照始终累计并可供调试命令读取。 */
    private static boolean statisticsLoggingEnabled;

    /** 纯静态管理器不允许实例化。 */
    private RVP_ChunkPathLoadManager() {
    }

    /**
     * 提交实体下一服务器 Tick 需要的有序路径，并返回当前已经获票的连续前缀。
     *
     * <p>同一实体在一个 Tick 内多次提交时以后一次路径为准；优先级取两次中较高者。</p>
     */
    public static PathRequestSnapshot submitPathRequest(
            @Nullable Entity entity,
            @Nullable List<ChunkPos> plannedChunks,
            @Nullable RequestPriority priority) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)
                || plannedChunks == null || plannedChunks.isEmpty()) {
            return PathRequestSnapshot.empty();
        }

        MinecraftServer server = level.getServer();
        ServerState serverState = SERVER_STATES.computeIfAbsent(server, ignored -> new ServerState());
        RequestKey key = new RequestKey(level.dimension().location().toString(), entity.getUUID());
        List<ChunkPos> normalizedPath = normalizePath(plannedChunks);
        if (normalizedPath.isEmpty()) {
            return PathRequestSnapshot.empty();
        }

        RequestPriority resolvedPriority = priority == null ? RequestPriority.ACTIVE_PROJECTILE : priority;
        PendingRequest previous = serverState.pendingRequests.get(key);
        if (previous != null && previous.priority().ordinal() < resolvedPriority.ordinal()) {
            resolvedPriority = previous.priority();
        }
        serverState.pendingRequests.put(key, new PendingRequest(
                key, level, entity.getId(), normalizedPath, resolvedPriority));

        EntityTicketState ticketState = serverState.entityStates.get(key);
        if (ticketState == null) {
            return new PathRequestSnapshot(normalizedPath.size(), 0, 0, false, Set.of());
        }

        // 只暴露与本次新路径一致的连续前缀，旧方向上残留的 Ticket 不得用于放行移动。
        LinkedHashSet<ChunkPos> grantedPrefix = continuousPrefix(normalizedPath, ticketState.grantedChunks());
        return new PathRequestSnapshot(
                normalizedPath.size(),
                grantedPrefix.size(),
                ticketState.lastNewRequestCount(),
                ticketState.lastBudgetExhausted(),
                Set.copyOf(grantedPrefix));
    }

    /** 记录路径就绪结果，供服务器级周期统计汇总；同实体同 Tick 的后一次观察覆盖前一次。 */
    public static void recordPathObservation(
            @Nullable Entity entity,
            RVP_ChunkPathLoader.PathReadiness readiness,
            @Nullable RequestPriority priority) {
        if (entity == null || !(entity.level() instanceof ServerLevel level) || readiness == null) {
            return;
        }
        ServerState serverState = SERVER_STATES.computeIfAbsent(level.getServer(), ignored -> new ServerState());
        RequestKey key = new RequestKey(level.dimension().location().toString(), entity.getUUID());
        RequestPriority resolvedPriority = priority == null ? RequestPriority.ACTIVE_PROJECTILE : priority;
        serverState.observations.put(key, new PathObservation(readiness, resolvedPriority));
    }

    /**
     * 明确释放某实体的管理状态。实际区块 Ticket 不同步移除，而是按 POST_TELEPORT 生命周期自然过期。
     */
    public static void releaseEntity(@Nullable Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        ServerState state = SERVER_STATES.get(level.getServer());
        if (state == null) {
            return;
        }
        RequestKey key = new RequestKey(level.dimension().location().toString(), entity.getUUID());
        state.pendingRequests.remove(key);
        state.entityStates.remove(key);
        state.observations.remove(key);
    }

    /** 返回当前统计周期快照，主要供调试命令和测试读取。 */
    public static StatisticsSnapshot getStatistics(@Nullable MinecraftServer server) {
        ServerState state = server == null ? null : SERVER_STATES.get(server);
        return state == null ? StatisticsSnapshot.empty() : state.snapshot();
    }

    /** 开启或关闭每 200 Tick 的汇总日志；关闭不影响内部统计快照。 */
    public static void setStatisticsLoggingEnabled(boolean enabled) {
        statisticsLoggingEnabled = enabled;
    }

    /** 返回周期汇总日志当前是否启用。 */
    public static boolean isStatisticsLoggingEnabled() {
        return statisticsLoggingEnabled;
    }

    /** 在每个服务器 Tick 开始阶段批量刷新和分配 Ticket。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        processServerTick(event.getServer());
    }

    /** 服务器完全停止后释放静态引用，避免集成服务器再次启动时沿用旧预算和实体状态。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SERVER_STATES.remove(event.getServer());
    }

    /** 汇总上一 Tick 观察、周期输出统计，并处理上一 Tick 提交的全部路径。 */
    private static void processServerTick(MinecraftServer server) {
        ServerState state = SERVER_STATES.computeIfAbsent(server, ignored -> new ServerState());
        state.aggregateObservations();
        state.maybeLogStatistics(server.getTickCount());

        List<PendingRequest> validRequests = new ArrayList<>();
        for (PendingRequest request : state.pendingRequests.values()) {
            Entity entity = request.level().getEntity(request.key().entityUuid());
            // UUID 和实体 ID 同时校验，避免实体 ID 复用后刷新旧实体路径。
            if (entity != null && entity.getId() == request.entityId()) {
                validRequests.add(request);
            }
        }
        state.pendingRequests.clear();

        Set<RequestKey> activeKeys = new LinkedHashSet<>();
        List<AllocationInput> inputs = new ArrayList<>(validRequests.size());
        for (PendingRequest request : validRequests) {
            activeKeys.add(request.key());
            EntityTicketState previous = state.entityStates.get(request.key());
            Set<ChunkPos> previousChunks = previous == null ? Set.of() : previous.grantedChunks();
            inputs.add(new AllocationInput(
                    request.key(), request.priority(), request.path(), previousChunks));
        }
        // 未在上一 Tick 继续提交的实体停止刷新，其临时 Ticket 将由原版超时机制回收。
        state.entityStates.keySet().retainAll(activeKeys);

        AllocationCycle allocation = allocateRequests(
                inputs, GLOBAL_NEW_CHUNK_REQUESTS_PER_TICK, state.rotationCursors);
        state.remainingBudget = allocation.remainingBudget();
        for (PendingRequest request : validRequests) {
            AllocationOutput output = allocation.outputs().get(request.key());
            if (output == null) {
                continue;
            }
            // 对连续获票前缀逐块精确刷新；不调用会附带额外前方票的本体 EntityUtil。
            for (ChunkPos chunk : output.grantedChunks()) {
                addExactTicket(request.level(), request.entityId(), chunk);
                state.intervalRequestedChunkCount++;
            }
            state.intervalNewRequestedChunkCount += output.newChunks().size();
            if (output.budgetExhausted()) {
                state.intervalBudgetExhaustedCount++;
            }
            state.entityStates.put(request.key(), new EntityTicketState(
                    output.grantedChunks(), output.newChunks().size(), output.budgetExhausted()));
        }
    }

    /** 只为指定 ChunkPos 添加一个与本体参数一致的临时 Ticket，不产生额外前方请求。 */
    private static void addExactTicket(ServerLevel level, int entityId, ChunkPos chunkPos) {
        level.getChunkSource().addRegionTicket(
                TicketType.POST_TELEPORT,
                chunkPos,
                POST_TELEPORT_TICKET_LEVEL,
                entityId);
    }

    /** 去除 null 和重复区块，同时保持原路径顺序并执行单实体上限。 */
    private static List<ChunkPos> normalizePath(List<ChunkPos> chunks) {
        LinkedHashSet<ChunkPos> normalized = new LinkedHashSet<>();
        for (ChunkPos chunk : chunks) {
            if (chunk != null) {
                normalized.add(chunk);
                if (normalized.size() >= MAX_CHUNKS_PER_ENTITY_TICK) {
                    break;
                }
            }
        }
        return List.copyOf(normalized);
    }

    /** 返回新路径中仍由上一轮授权覆盖的连续前缀，遇到首个缺口立即停止。 */
    private static LinkedHashSet<ChunkPos> continuousPrefix(List<ChunkPos> desired, Set<ChunkPos> granted) {
        LinkedHashSet<ChunkPos> prefix = new LinkedHashSet<>();
        for (ChunkPos chunk : desired) {
            if (!granted.contains(chunk)) {
                break;
            }
            prefix.add(chunk);
        }
        return prefix;
    }

    /**
     * 执行纯内存预算分配。等待弹体优先；同优先级从轮转游标开始，每轮每实体最多新增一个区块。
     */
    static AllocationCycle allocateRequests(
            List<AllocationInput> inputs,
            int totalBudget,
            EnumMap<RequestPriority, Integer> rotationCursors) {
        int remainingBudget = Math.max(0, totalBudget);
        Map<RequestKey, MutableAllocation> mutable = new LinkedHashMap<>();
        for (AllocationInput input : inputs) {
            LinkedHashSet<ChunkPos> prefix = continuousPrefix(input.desiredChunks(), input.previouslyGrantedChunks());
            mutable.put(input.key(), new MutableAllocation(input, prefix));
        }

        for (RequestPriority priority : RequestPriority.values()) {
            List<MutableAllocation> group = mutable.values().stream()
                    .filter(allocation -> allocation.input.priority() == priority)
                    .sorted(Comparator
                            .comparing((MutableAllocation allocation) ->
                                    allocation.input.key().dimensionId())
                            .thenComparing(allocation -> allocation.input.key().entityUuid().toString()))
                    .toList();
            if (group.isEmpty()) {
                continue;
            }

            int cursor = Math.floorMod(rotationCursors.getOrDefault(priority, 0), group.size());
            boolean progressed = true;
            while (remainingBudget > 0 && progressed) {
                progressed = false;
                for (int offset = 0; offset < group.size() && remainingBudget > 0; offset++) {
                    MutableAllocation allocation = group.get((cursor + offset) % group.size());
                    int nextIndex = allocation.grantedChunks.size();
                    if (nextIndex >= allocation.input.desiredChunks().size()) {
                        continue;
                    }
                    // 只从当前连续前缀末尾向前扩展，绝不会跨过缺口给远端区块加票。
                    ChunkPos next = allocation.input.desiredChunks().get(nextIndex);
                    allocation.grantedChunks.add(next);
                    allocation.newChunks.add(next);
                    remainingBudget--;
                    progressed = true;
                }
            }
            // 每 Tick 轮换同级首个服务实体，避免稳定 UUID 排序造成长期饥饿。
            rotationCursors.put(priority, (cursor + 1) % group.size());
        }

        Map<RequestKey, AllocationOutput> outputs = new LinkedHashMap<>();
        for (Map.Entry<RequestKey, MutableAllocation> entry : mutable.entrySet()) {
            MutableAllocation allocation = entry.getValue();
            boolean exhausted = allocation.grantedChunks.size() < allocation.input.desiredChunks().size();
            outputs.put(entry.getKey(), new AllocationOutput(
                    List.copyOf(allocation.grantedChunks),
                    List.copyOf(allocation.newChunks),
                    exhausted));
        }
        return new AllocationCycle(Map.copyOf(outputs), remainingBudget);
    }

    /** 请求优先级；枚举声明顺序就是预算分配顺序。 */
    public enum RequestPriority {
        WAITING_PROJECTILE,
        ACTIVE_PROJECTILE,
        FIXED_WING
    }

    /** 当前实体路径提交后可见的授权快照。 */
    public record PathRequestSnapshot(
            int plannedChunkCount,
            int requestedChunkCount,
            int newRequestedChunkCount,
            boolean budgetExhausted,
            Set<ChunkPos> grantedChunks) {
        private static PathRequestSnapshot empty() {
            return new PathRequestSnapshot(0, 0, 0, false, Set.of());
        }
    }

    /** 当前统计周期的服务器级指标。 */
    public record StatisticsSnapshot(
            long requestedChunkCount,
            long newRequestedChunkCount,
            long readyChunkCount,
            long waitingProjectileCount,
            long budgetExhaustedCount,
            int activeEntityCount,
            int pendingEntityCount,
            int remainingBudget) {
        private static StatisticsSnapshot empty() {
            return new StatisticsSnapshot(0, 0, 0, 0, 0, 0, 0,
                    GLOBAL_NEW_CHUNK_REQUESTS_PER_TICK);
        }
    }

    /** 服务器内唯一实体请求键，维度与 UUID 共同防止跨维度冲突。 */
    record RequestKey(String dimensionId, UUID entityUuid) {
    }

    /** 纯分配器输入，供生产处理和单元测试共用。 */
    record AllocationInput(
            RequestKey key,
            RequestPriority priority,
            List<ChunkPos> desiredChunks,
            Set<ChunkPos> previouslyGrantedChunks) {
    }

    /** 纯分配器的单实体输出。 */
    record AllocationOutput(
            List<ChunkPos> grantedChunks,
            List<ChunkPos> newChunks,
            boolean budgetExhausted) {
    }

    /** 纯分配器的一次全局预算周期输出。 */
    record AllocationCycle(Map<RequestKey, AllocationOutput> outputs, int remainingBudget) {
    }

    /** 等待下一 ServerTick START 处理的实体路径。 */
    private record PendingRequest(
            RequestKey key,
            ServerLevel level,
            int entityId,
            List<ChunkPos> path,
            RequestPriority priority) {
    }

    /** 上一次分配后实体持有的连续路径前缀。 */
    private record EntityTicketState(
            Set<ChunkPos> grantedChunks,
            int lastNewRequestCount,
            boolean lastBudgetExhausted) {
        private EntityTicketState(List<ChunkPos> grantedChunks, int lastNewRequestCount, boolean lastBudgetExhausted) {
            this(Set.copyOf(grantedChunks), lastNewRequestCount, lastBudgetExhausted);
        }
    }

    /** 同一服务器 Tick 内最后一次路径就绪观察。 */
    private record PathObservation(
            RVP_ChunkPathLoader.PathReadiness readiness,
            RequestPriority priority) {
    }

    /** 纯分配算法内部使用的可变累加器。 */
    private static final class MutableAllocation {
        private final AllocationInput input;
        private final LinkedHashSet<ChunkPos> grantedChunks;
        private final List<ChunkPos> newChunks = new ArrayList<>();

        private MutableAllocation(AllocationInput input, LinkedHashSet<ChunkPos> grantedChunks) {
            this.input = input;
            this.grantedChunks = grantedChunks;
        }
    }

    /** 单个 MinecraftServer 的预算、提交、授权和周期统计状态。 */
    private static final class ServerState {
        private final Map<RequestKey, PendingRequest> pendingRequests = new LinkedHashMap<>();
        private final Map<RequestKey, EntityTicketState> entityStates = new LinkedHashMap<>();
        private final Map<RequestKey, PathObservation> observations = new LinkedHashMap<>();
        private final EnumMap<RequestPriority, Integer> rotationCursors = new EnumMap<>(RequestPriority.class);
        private int remainingBudget = GLOBAL_NEW_CHUNK_REQUESTS_PER_TICK;
        private long intervalRequestedChunkCount;
        private long intervalNewRequestedChunkCount;
        private long intervalReadyChunkCount;
        private long intervalWaitingProjectileCount;
        private long intervalBudgetExhaustedCount;

        /** 将上一 Tick 每实体最后一次观察合并进周期统计。 */
        private void aggregateObservations() {
            for (PathObservation observation : observations.values()) {
                intervalReadyChunkCount += observation.readiness().readyChunkCount();
                if (observation.priority() != RequestPriority.FIXED_WING
                        && !observation.readiness().pathReady()) {
                    intervalWaitingProjectileCount++;
                }
            }
            observations.clear();
        }

        /** 每 200 Tick 且确有活动时输出一条聚合日志，避免逐区块刷屏。 */
        private void maybeLogStatistics(int serverTickCount) {
            if (!statisticsLoggingEnabled
                    || serverTickCount <= 0
                    || serverTickCount % STATS_LOG_INTERVAL_TICKS != 0) {
                return;
            }
            if (intervalRequestedChunkCount == 0 && intervalReadyChunkCount == 0
                    && intervalWaitingProjectileCount == 0 && intervalBudgetExhaustedCount == 0) {
                return;
            }
            LOGGER.info(
                    "[RVP][ChunkPath] requestedChunkCount={} newRequestedChunkCount={} readyChunkCount={} "
                            + "waitingProjectileCount={} budgetExhaustedCount={} activeEntityCount={} pendingEntityCount={}",
                    intervalRequestedChunkCount,
                    intervalNewRequestedChunkCount,
                    intervalReadyChunkCount,
                    intervalWaitingProjectileCount,
                    intervalBudgetExhaustedCount,
                    entityStates.size(),
                    pendingRequests.size());
            intervalRequestedChunkCount = 0;
            intervalNewRequestedChunkCount = 0;
            intervalReadyChunkCount = 0;
            intervalWaitingProjectileCount = 0;
            intervalBudgetExhaustedCount = 0;
        }

        /** 构造不改变内部状态的统计快照。 */
        private StatisticsSnapshot snapshot() {
            return new StatisticsSnapshot(
                    intervalRequestedChunkCount,
                    intervalNewRequestedChunkCount,
                    intervalReadyChunkCount,
                    intervalWaitingProjectileCount,
                    intervalBudgetExhaustedCount,
                    entityStates.size(),
                    pendingRequests.size(),
                    remainingBudget);
        }
    }
}
