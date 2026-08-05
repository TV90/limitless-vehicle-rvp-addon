package org.ywzj.rvp.util;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 服务器级新增 Ticket 预算分配算法的纯单元测试。 */
class RVP_ChunkPathLoadManagerTest {

    /** 已有连续路径只需刷新，不消耗本 Tick 新增预算。 */
    @Test
    void existingContinuousPrefixRefreshesWithoutBudget() {
        RVP_ChunkPathLoadManager.AllocationInput input = input(
                1,
                RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE,
                List.of(chunk(0), chunk(1)),
                Set.of(chunk(0), chunk(1)));

        RVP_ChunkPathLoadManager.AllocationCycle cycle = allocate(List.of(input), 0, cursors());
        RVP_ChunkPathLoadManager.AllocationOutput output = cycle.outputs().get(input.key());

        assertEquals(List.of(chunk(0), chunk(1)), output.grantedChunks());
        assertTrue(output.newChunks().isEmpty());
        assertFalse(output.budgetExhausted());
        assertEquals(0, cycle.remainingBudget());
    }

    /** 多实体分配的新增区块总数不得突破服务器级预算。 */
    @Test
    void globalBudgetIsSharedAcrossEntities() {
        RVP_ChunkPathLoadManager.AllocationInput first = input(
                1,
                RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE,
                List.of(chunk(0), chunk(1), chunk(2)),
                Set.of());
        RVP_ChunkPathLoadManager.AllocationInput second = input(
                2,
                RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE,
                List.of(chunk(10), chunk(11), chunk(12)),
                Set.of());

        RVP_ChunkPathLoadManager.AllocationCycle cycle = allocate(
                List.of(first, second), 3, cursors());
        int newChunkCount = cycle.outputs().values().stream()
                .mapToInt(output -> output.newChunks().size())
                .sum();

        assertEquals(3, newChunkCount);
        assertEquals(0, cycle.remainingBudget());
        assertEquals(List.of(chunk(0), chunk(1)), cycle.outputs().get(first.key()).grantedChunks());
        assertEquals(List.of(chunk(10)), cycle.outputs().get(second.key()).grantedChunks());
    }

    /** 等待弹体优先于活动弹体和固定翼获得新增预算。 */
    @Test
    void waitingProjectileConsumesBudgetBeforeLowerPriorities() {
        RVP_ChunkPathLoadManager.AllocationInput fixedWing = input(
                1,
                RVP_ChunkPathLoadManager.RequestPriority.FIXED_WING,
                List.of(chunk(20), chunk(21)),
                Set.of());
        RVP_ChunkPathLoadManager.AllocationInput active = input(
                2,
                RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE,
                List.of(chunk(10), chunk(11)),
                Set.of());
        RVP_ChunkPathLoadManager.AllocationInput waiting = input(
                3,
                RVP_ChunkPathLoadManager.RequestPriority.WAITING_PROJECTILE,
                List.of(chunk(0), chunk(1)),
                Set.of());

        RVP_ChunkPathLoadManager.AllocationCycle cycle = allocate(
                List.of(fixedWing, active, waiting), 2, cursors());

        assertEquals(List.of(chunk(0), chunk(1)), cycle.outputs().get(waiting.key()).grantedChunks());
        assertTrue(cycle.outputs().get(active.key()).grantedChunks().isEmpty());
        assertTrue(cycle.outputs().get(fixedWing.key()).grantedChunks().isEmpty());
        assertTrue(cycle.outputs().get(active.key()).budgetExhausted());
        assertTrue(cycle.outputs().get(fixedWing.key()).budgetExhausted());
    }

    /** 同优先级首个服务实体会逐 Tick 轮换，预算不足时不会固定饿死后序实体。 */
    @Test
    void samePriorityUsesRotatingStartCursor() {
        RVP_ChunkPathLoadManager.AllocationInput first = input(
                1,
                RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE,
                List.of(chunk(0)),
                Set.of());
        RVP_ChunkPathLoadManager.AllocationInput second = input(
                2,
                RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE,
                List.of(chunk(10)),
                Set.of());
        EnumMap<RVP_ChunkPathLoadManager.RequestPriority, Integer> cursors = cursors();

        RVP_ChunkPathLoadManager.AllocationCycle firstCycle = allocate(
                List.of(first, second), 1, cursors);
        RVP_ChunkPathLoadManager.AllocationCycle secondCycle = allocate(
                List.of(first, second), 1, cursors);

        assertEquals(List.of(chunk(0)), firstCycle.outputs().get(first.key()).grantedChunks());
        assertTrue(firstCycle.outputs().get(second.key()).grantedChunks().isEmpty());
        assertTrue(secondCycle.outputs().get(first.key()).grantedChunks().isEmpty());
        assertEquals(List.of(chunk(10)), secondCycle.outputs().get(second.key()).grantedChunks());
    }

    /** 上一轮授权存在中间缺口时，只保留从新路径起点开始的连续前缀。 */
    @Test
    void previousGrantAfterGapCannotBypassMissingChunk() {
        RVP_ChunkPathLoadManager.AllocationInput input = input(
                1,
                RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE,
                List.of(chunk(0), chunk(1), chunk(2)),
                Set.of(chunk(0), chunk(2)));

        RVP_ChunkPathLoadManager.AllocationOutput output = allocate(
                List.of(input), 0, cursors()).outputs().get(input.key());

        assertEquals(List.of(chunk(0)), output.grantedChunks());
        assertTrue(output.budgetExhausted());
    }

    /** 路径向前滚动时丢弃后方旧块，并只为新前端区块消耗预算。 */
    @Test
    void rollingPathDropsStaleChunkAndExtendsFront() {
        RVP_ChunkPathLoadManager.AllocationInput input = input(
                1,
                RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE,
                List.of(chunk(1), chunk(2)),
                Set.of(chunk(0), chunk(1)));

        RVP_ChunkPathLoadManager.AllocationOutput output = allocate(
                List.of(input), 1, cursors()).outputs().get(input.key());

        assertEquals(List.of(chunk(1), chunk(2)), output.grantedChunks());
        assertEquals(List.of(chunk(2)), output.newChunks());
        assertFalse(output.grantedChunks().contains(chunk(0)));
        assertFalse(output.budgetExhausted());
    }

    /** 调用纯预算分配器，避免测试依赖 MinecraftServer 或真实区块 Ticket。 */
    private static RVP_ChunkPathLoadManager.AllocationCycle allocate(
            List<RVP_ChunkPathLoadManager.AllocationInput> inputs,
            int budget,
            EnumMap<RVP_ChunkPathLoadManager.RequestPriority, Integer> cursors) {
        return RVP_ChunkPathLoadManager.allocateRequests(inputs, budget, cursors);
    }

    /** 构造稳定 UUID 和主世界维度的测试请求。 */
    private static RVP_ChunkPathLoadManager.AllocationInput input(
            long uuidLowBits,
            RVP_ChunkPathLoadManager.RequestPriority priority,
            List<ChunkPos> desired,
            Set<ChunkPos> previous) {
        RVP_ChunkPathLoadManager.RequestKey key = new RVP_ChunkPathLoadManager.RequestKey(
                "minecraft:overworld", new UUID(0, uuidLowBits));
        return new RVP_ChunkPathLoadManager.AllocationInput(key, priority, desired, previous);
    }

    /** 为每个测试创建独立轮转游标，避免用例间相互影响。 */
    private static EnumMap<RVP_ChunkPathLoadManager.RequestPriority, Integer> cursors() {
        return new EnumMap<>(RVP_ChunkPathLoadManager.RequestPriority.class);
    }

    /** 构造位于 Z=0 的连续 X 向测试区块。 */
    private static ChunkPos chunk(int x) {
        return new ChunkPos(x, 0);
    }
}
