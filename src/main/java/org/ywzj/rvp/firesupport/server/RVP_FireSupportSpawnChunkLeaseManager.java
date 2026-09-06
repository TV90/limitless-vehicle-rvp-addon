package org.ywzj.rvp.firesupport.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 炮火弹体生成前的短期 Chunk 租约管理器。
 * 只添加临时 POST_TELEPORT Ticket，不强制同步加载，也不创建永久 force chunk。
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_FireSupportSpawnChunkLeaseManager {
    /** 每个服务器 Tick 最多首次申请的炮火 Chunk 数。 */ public static final int MAX_NEW_TICKETS_PER_TICK = 24;
    /** 超过计划生成时间后仍未 entity-ticking 的最大等待，单位 Tick。 */ public static final int MAX_WAIT_TICKS = 1200;
    /** 与既有动态路径系统一致的 POST_TELEPORT Ticket 距离参数。 */ private static final int TICKET_LEVEL = 2;
    /** 按服务器实例隔离所有临时租约。 */ private static final Map<MinecraftServer, ServerState> STATES = new IdentityHashMap<>();

    private RVP_FireSupportSpawnChunkLeaseManager() {}

    /**
     * 在进入 preload 窗口后登记/查询一个计划落点 Chunk。
     * 调用仅查询现有就绪状态并排队 Ticket，绝不同步加载或生成区块。
     */
    public static LeaseStatus request(ServerLevel level, UUID missionId, ChunkPos chunk,
                                      long expectedSpawnTick, int preloadTicks, int maxMissionChunks) {
        if (level == null || missionId == null || chunk == null || preloadTicks < 0 || maxMissionChunks < 1) {
            return LeaseStatus.INVALID_REQUEST;
        }
        long now = level.getGameTime();
        if (now < expectedSpawnTick - preloadTicks) return LeaseStatus.TOO_EARLY;
        ServerState state = STATES.computeIfAbsent(level.getServer(), ignored -> new ServerState());
        MissionKey missionKey = new MissionKey(level.dimension().location().toString(), missionId);
        Set<ChunkPos> missionChunks = state.missionChunks.computeIfAbsent(missionKey, ignored -> new LinkedHashSet<>());
        if (!missionChunks.contains(chunk) && missionChunks.size() >= maxMissionChunks) return LeaseStatus.MISSION_CHUNK_LIMIT;
        missionChunks.add(chunk);

        LeaseKey key = new LeaseKey(missionKey.dimensionId(), missionId, chunk);
        Lease lease = state.leases.computeIfAbsent(key,
                ignored -> new Lease(level, chunk, expectedSpawnTick, state.allocateTicketId()));
        // 同一任务后续弹落在同一 Chunk 时复用租约，并把等待截止推进到最新计划弹。
        lease.expectedSpawnTick = Math.max(lease.expectedSpawnTick, expectedSpawnTick);
        lease.lastRequestTick = now;
        if (now > lease.expectedSpawnTick + MAX_WAIT_TICKS) {
            lease.timedOut = true;
            return LeaseStatus.TIMED_OUT;
        }
        if (isEntityTicking(level, chunk)) return now >= expectedSpawnTick ? LeaseStatus.READY : LeaseStatus.PRELOADING;
        return now >= expectedSpawnTick ? LeaseStatus.WAITING_FOR_CHUNK : LeaseStatus.PRELOADING;
    }

    /** 任务完成或取消后停止刷新其所有租约；已有临时 Ticket 按原版生命周期自然过期。 */
    public static void releaseMission(MinecraftServer server, UUID missionId) {
        ServerState state = STATES.get(server);
        if (state == null || missionId == null) return;
        state.leases.entrySet().removeIf(entry -> entry.getKey().missionId().equals(missionId));
        state.missionChunks.entrySet().removeIf(entry -> entry.getKey().missionId().equals(missionId));
    }

    /** @return 当前服务器租约统计，供测试入口和后续任务管理器观测。 */
    public static Statistics statistics(MinecraftServer server) {
        ServerState state = STATES.get(server);
        if (state == null) return new Statistics(0, 0, 0, 0);
        long issued = state.leases.values().stream().filter(lease -> lease.ticketIssued).count();
        long timedOut = state.leases.values().stream().filter(lease -> lease.timedOut).count();
        return new Statistics(state.leases.size(), state.missionChunks.size(), (int) issued, (int) timedOut);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        processServerTick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        STATES.remove(event.getServer());
    }

    private static void processServerTick(MinecraftServer server) {
        ServerState state = STATES.get(server);
        if (state == null) return;
        long now = server.overworld().getGameTime();
        state.leases.values().removeIf(lease -> {
            if (now > lease.expectedSpawnTick + MAX_WAIT_TICKS) lease.timedOut = true;
            return lease.timedOut && now > lease.expectedSpawnTick + MAX_WAIT_TICKS + 20;
        });
        // 超时租约清除后同步回收任务去重集合，避免任务侧漏调 release 时留下纯内存残项。
        state.missionChunks.entrySet().removeIf(entry -> state.leases.keySet().stream().noneMatch(
                key -> key.dimensionId().equals(entry.getKey().dimensionId())
                        && key.missionId().equals(entry.getKey().missionId())));

        List<Lease> active = state.leases.values().stream()
                .filter(lease -> !lease.timedOut)
                .sorted(Comparator.comparingLong((Lease lease) -> lease.expectedSpawnTick)
                        .thenComparingLong(lease -> lease.chunk.toLong()))
                .toList();
        List<Integer> selected = selectNewTicketIndexes(active.stream().map(lease -> lease.ticketIssued).toList(),
                MAX_NEW_TICKETS_PER_TICK, state.rotationCursor);
        if (!active.isEmpty()) state.rotationCursor = (state.rotationCursor + 1) % active.size();
        Set<Integer> selectedSet = Set.copyOf(selected);
        for (int i = 0; i < active.size(); i++) {
            Lease lease = active.get(i);
            if (lease.ticketIssued || selectedSet.contains(i)) {
                // 调用原版临时 Ticket API；重复添加只刷新同一任务/Chunk 的短租约，不扩大范围。
                lease.level.getChunkSource().addRegionTicket(
                        TicketType.POST_TELEPORT, lease.chunk, TICKET_LEVEL, lease.ticketId);
                lease.ticketIssued = true;
            }
        }
    }

    /** 纯内存预算分配：从轮转起点选择尚未首次获票的条目，最多返回 budget 个索引。 */
    static List<Integer> selectNewTicketIndexes(List<Boolean> issued, int budget, int rotationCursor) {
        if (issued == null || issued.isEmpty() || budget <= 0) return List.of();
        List<Integer> selected = new ArrayList<>();
        int start = Math.floorMod(rotationCursor, issued.size());
        for (int offset = 0; offset < issued.size() && selected.size() < budget; offset++) {
            int index = (start + offset) % issued.size();
            if (!Boolean.TRUE.equals(issued.get(index))) selected.add(index);
        }
        return List.copyOf(selected);
    }

    private static boolean isEntityTicking(ServerLevel level, ChunkPos chunk) {
        int blockX = chunk.getMinBlockX() + 8;
        int blockZ = chunk.getMinBlockZ() + 8;
        return level.isPositionEntityTicking(new BlockPos(blockX, level.getMinBuildHeight(), blockZ));
    }

    /** 请求方可据此区分计划等待、Chunk 等待、预算/上限和可生成状态。 */
    public enum LeaseStatus {
        READY,
        TOO_EARLY,
        PRELOADING,
        WAITING_FOR_CHUNK,
        MISSION_CHUNK_LIMIT,
        TIMED_OUT,
        INVALID_REQUEST
    }

    /** 管理器当前的只读统计。 */
    public record Statistics(
            /** 唯一任务/Chunk 租约数。 */ int activeLeaseCount,
            /** 包含租约的任务数。 */ int missionCount,
            /** 已经首次申请 Ticket 的租约数。 */ int issuedTicketCount,
            /** 等待超时但尚未清理的租约数。 */ int timedOutCount) {}

    /** 同一维度任务的去重键。 */ private record MissionKey(String dimensionId, UUID missionId) {}
    /** 同一任务、同一 Chunk 的唯一租约键。 */ private record LeaseKey(String dimensionId, UUID missionId, ChunkPos chunk) {}

    private static final class Lease {
        /** 租约所在服务端世界。 */ private final ServerLevel level;
        /** 唯一计划 Chunk。 */ private final ChunkPos chunk;
        /** 该 Chunk 当前已知最后一发的计划生成 Tick；同任务后续弹可向后推进。 */ private long expectedSpawnTick;
        /** POST_TELEPORT 使用的管理器内唯一负整数标识。 */ private final int ticketId;
        /** 最近一次任务侧请求 Tick。 */ private long lastRequestTick;
        /** 是否至少成功申请过一次临时 Ticket。 */ private boolean ticketIssued;
        /** 是否已经超过最大等待时间。 */ private boolean timedOut;

        private Lease(ServerLevel level, ChunkPos chunk, long expectedSpawnTick, int ticketId) {
            this.level = level;
            this.chunk = chunk;
            this.expectedSpawnTick = expectedSpawnTick;
            this.ticketId = ticketId;
            this.lastRequestTick = level.getGameTime();
        }
    }

    private static final class ServerState {
        /** 任务/Chunk 去重后的活动租约。 */ private final Map<LeaseKey, Lease> leases = new LinkedHashMap<>();
        /** 每个任务已占用的唯一 Chunk，用于硬上限。 */ private final Map<MissionKey, Set<ChunkPos>> missionChunks = new LinkedHashMap<>();
        /** 公平选择首次 Ticket 的轮转游标。 */ private int rotationCursor;
        /** 递减分配的负 Ticket 标识，避免与正常实体 ID 混淆。 */ private int nextTicketId = -1;

        private int allocateTicketId() {
            if (nextTicketId == Integer.MIN_VALUE) nextTicketId = -1;
            return nextTicketId--;
        }
    }
}
