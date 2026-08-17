package org.ywzj.rvp.util;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RVP 区块路径规划和就绪判定的纯单元测试，不启动 Minecraft 世界。 */
class RVP_ChunkPathLoaderTest {

    /** 验证静止和纯 Y 轴运动不会产生额外水平区块。 */
    @Test
    void stationaryAndVerticalMotionOnlyProtectCurrentChunk() {
        assertEquals(chunks(chunk(0, 0)), plan(new Vec3(1, 70, 1), Vec3.ZERO, 5, 64));
        assertEquals(chunks(chunk(-1, -2)), plan(new Vec3(-0.1, 70, -16.1), new Vec3(0, -100, 0), 5, 64));
    }

    /** 验证 X/Z 四个轴向移动会按顺序覆盖沿途每个区块。 */
    @Test
    void positiveAndNegativeAxesCrossEveryChunk() {
        assertEquals(
                chunks(chunk(0, 0), chunk(1, 0), chunk(2, 0)),
                plan(new Vec3(1, 0, 1), new Vec3(40, 0, 0), 1, 64));
        assertEquals(
                chunks(chunk(0, 0), chunk(-1, 0), chunk(-2, 0), chunk(-3, 0)),
                plan(new Vec3(1, 0, 1), new Vec3(-40, 0, 0), 1, 64));
        assertEquals(
                chunks(chunk(0, 0), chunk(0, 1), chunk(0, 2)),
                plan(new Vec3(1, 0, 1), new Vec3(0, 0, 40), 1, 64));
        assertEquals(
                chunks(chunk(0, 0), chunk(0, -1), chunk(0, -2), chunk(0, -3)),
                plan(new Vec3(1, 0, 1), new Vec3(0, 0, -40), 1, 64));
    }

    /** 验证负坐标使用 floor 语义，避免直接强转整数造成区块归属错误。 */
    @Test
    void negativeCoordinatesUseFloorSemantics() {
        assertEquals(chunks(chunk(-1, 0)), plan(new Vec3(-0.1, 0, 1), Vec3.ZERO, 1, 64));
        assertEquals(chunks(chunk(-1, 0)), plan(new Vec3(-16.0, 0, 1), Vec3.ZERO, 1, 64));
        assertEquals(chunks(chunk(-2, 0)), plan(new Vec3(-16.1, 0, 1), Vec3.ZERO, 1, 64));
    }

    /** 验证精确穿过区块角点时同时包含两个侧邻块和对角块。 */
    @Test
    void exactCornerAddsBothSideNeighborsAndDiagonal() {
        assertEquals(
                chunks(chunk(0, 0), chunk(1, 0), chunk(0, 1), chunk(1, 1)),
                plan(new Vec3(8, 0, 8), new Vec3(16, 0, 16), 1, 64));
    }

    /** 验证起点或终点位于精确边界时，区块归属遵循实际运动方向。 */
    @Test
    void exactChunkBoundaryHonorsMovementDirection() {
        assertEquals(
                chunks(chunk(0, 0), chunk(1, 0)),
                plan(new Vec3(8, 0, 1), new Vec3(8, 0, 0), 1, 64));
        assertEquals(
                chunks(chunk(-1, 0), chunk(-2, 0)),
                plan(new Vec3(-16, 0, 1), new Vec3(-1, 0, 0), 1, 64));
    }

    /** 验证 15 格/Tick 速度乘 5 Tick 预测窗口后连续覆盖 75 格路径。 */
    @Test
    void horizonScalesFifteenBlockMotion() {
        assertEquals(
                chunks(chunk(0, 0), chunk(1, 0), chunk(2, 0), chunk(3, 0), chunk(4, 0)),
                plan(new Vec3(1, 0, 1), new Vec3(15, 0, 0), 5, 64));
    }

    /** 验证非法的零或负预测 Tick 数会安全回退为 1 Tick。 */
    @Test
    void horizonBelowOneFallsBackToOne() {
        // 先生成标准 1 Tick 结果，再与零和负值输入对照。
        List<ChunkPos> expected = plan(new Vec3(1, 0, 1), new Vec3(20, 0, 0), 1, 64);
        assertEquals(expected, plan(new Vec3(1, 0, 1), new Vec3(20, 0, 0), 0, 64));
        assertEquals(expected, plan(new Vec3(1, 0, 1), new Vec3(20, 0, 0), -10, 64));
    }

    /** 载具租约前探为零时严格只保护当前区块，不沿速度隐式扩展一 Tick。 */
    @Test
    void leasePathWithZeroLookAheadOnlyKeepsCurrentChunk() {
        assertEquals(
                chunks(chunk(0, 0)),
                RVP_ChunkPathLoader.planLeasePath(
                        new Vec3(1, 70, 1), new Vec3(100, 0, 0), 0));
    }

    /** 载具正数前探复用 supercover 路径并保持连续顺序。 */
    @Test
    void leasePathUsesSupercoverForPositiveLookAhead() {
        assertEquals(
                chunks(chunk(0, 0), chunk(1, 0), chunk(2, 0), chunk(3, 0), chunk(4, 0)),
                RVP_ChunkPathLoader.planLeasePath(
                        new Vec3(1, 70, 1), new Vec3(15, 0, 0), 5));
    }

    /** 验证 null、NaN 和 Infinity 不会进入 DDA 遍历。 */
    @Test
    void invalidInputDoesNotTraverse() {
        assertTrue(plan(null, Vec3.ZERO, 1, 64).isEmpty());
        assertTrue(plan(new Vec3(Double.NaN, 0, 0), Vec3.ZERO, 1, 64).isEmpty());
        assertEquals(
                chunks(chunk(0, 0)),
                plan(new Vec3(1, 0, 1), new Vec3(Double.POSITIVE_INFINITY, 0, 0), 1, 64));
        assertEquals(
                chunks(chunk(0, 0)),
                plan(new Vec3(1, 0, 1), new Vec3(Double.NaN, 0, 0), 1, 64));
    }

    /** 验证达到上限后只保留连续前缀，不抽样加入远端终点。 */
    @Test
    void chunkLimitKeepsContinuousPrefixWithoutEndpointSampling() {
        // 2000 格路径的真实终点在 X=125；上限为 4 时只能返回 X=0..3 的连续前缀。
        List<ChunkPos> result = plan(new Vec3(1, 0, 1), new Vec3(2000, 0, 0), 1, 4);

        assertEquals(chunks(chunk(0, 0), chunk(1, 0), chunk(2, 0), chunk(3, 0)), result);
        assertFalse(result.contains(chunk(125, 0)));
        assertFalse(RVP_ChunkPathLoader.reachesProjectedEnd(
                new Vec3(1, 0, 1), new Vec3(2000, 0, 0), 1, result));
    }

    /** 验证上限为零时仍保留起点区块，避免返回可被误解为空安全路径的结果。 */
    @Test
    void zeroChunkLimitStillKeepsStartChunk() {
        assertEquals(
                chunks(chunk(0, 0)),
                plan(new Vec3(1, 0, 1), new Vec3(100, 0, 0), 1, 0));
    }

    /** 验证未截断路径能够明确确认已覆盖预测终点。 */
    @Test
    void completePathReportsProjectedEndReached() {
        Vec3 start = new Vec3(1, 0, 1);
        Vec3 motion = new Vec3(40, 0, 0);
        List<ChunkPos> result = plan(start, motion, 1, 64);

        assertTrue(RVP_ChunkPathLoader.reachesProjectedEnd(start, motion, 1, result));
    }

    /** 使用第三次实测数据验证 15 格位移仍会从 Z=617 跨入 Z=618。 */
    @Test
    void thirdFlightBoundaryCrossingIncludesAdjacentChunk() {
        assertEquals(
                chunks(chunk(-6, 617), chunk(-6, 618)),
                plan(new Vec3(-83.657, 438.056, 9887.078), new Vec3(-0.085, -2.321, 14.819), 1, 64));
    }

    /** 验证全部区块就绪时返回完整数量，且不存在首个失败区块。 */
    @Test
    void allReadyPathReportsReadyCount() {
        List<ChunkPos> path = chunks(chunk(0, 0), chunk(1, 0), chunk(2, 0));

        // 注入恒为 READY 的纯查询器，验证与 ServerLevel 无关的判定核心。
        RVP_ChunkPathLoader.PathReadiness readiness = RVP_ChunkPathLoader.checkPathReadiness(
                path, ignored -> RVP_ChunkPathLoader.ChunkReadiness.READY);

        assertTrue(readiness.pathReady());
        assertEquals(3, readiness.readyChunkCount());
        assertNull(readiness.firstUnreadyChunk());
        assertEquals(RVP_ChunkPathLoader.ChunkReadiness.READY, readiness.firstUnreadyState());
    }

    /** 验证查询在首个未加载区块处短路，不继续探测更远区块。 */
    @Test
    void readinessStopsAtFirstNotLoadedChunk() {
        List<ChunkPos> path = chunks(chunk(0, 0), chunk(1, 0), chunk(2, 0));
        List<ChunkPos> queried = new ArrayList<>();

        // queried 同时验证调用顺序以及首个失败后的短路行为。
        RVP_ChunkPathLoader.PathReadiness readiness = RVP_ChunkPathLoader.checkPathReadiness(path, chunk -> {
            queried.add(chunk);
            return chunk.equals(path.get(1))
                    ? RVP_ChunkPathLoader.ChunkReadiness.NOT_LOADED
                    : RVP_ChunkPathLoader.ChunkReadiness.READY;
        });

        assertFalse(readiness.pathReady());
        assertEquals(1, readiness.readyChunkCount());
        assertEquals(path.get(1), readiness.firstUnreadyChunk());
        assertEquals(RVP_ChunkPathLoader.ChunkReadiness.NOT_LOADED, readiness.firstUnreadyState());
        assertEquals(path.subList(0, 2), queried);
    }

    /** 验证“已加载但不可实体 Tick”不会与“未加载”混为同一状态。 */
    @Test
    void readinessDistinguishesLoadedButNotEntityTicking() {
        List<ChunkPos> path = chunks(chunk(0, 0), chunk(1, 0), chunk(2, 0));
        Map<ChunkPos, RVP_ChunkPathLoader.ChunkReadiness> states = new HashMap<>();
        states.put(path.get(0), RVP_ChunkPathLoader.ChunkReadiness.READY);
        states.put(path.get(1), RVP_ChunkPathLoader.ChunkReadiness.NOT_ENTITY_TICKING);

        // Map 模拟每个区块独立的加载/晋级状态。
        RVP_ChunkPathLoader.PathReadiness readiness = RVP_ChunkPathLoader.checkPathReadiness(
                path, states::get);

        assertFalse(readiness.pathReady());
        assertEquals(1, readiness.readyChunkCount());
        assertEquals(path.get(1), readiness.firstUnreadyChunk());
        assertEquals(RVP_ChunkPathLoader.ChunkReadiness.NOT_ENTITY_TICKING, readiness.firstUnreadyState());
    }

    /** 验证尚未获得全局预算授权的区块与未加载状态保持独立。 */
    @Test
    void readinessDistinguishesNotRequested() {
        List<ChunkPos> path = chunks(chunk(0, 0), chunk(1, 0));

        RVP_ChunkPathLoader.PathReadiness readiness = RVP_ChunkPathLoader.checkPathReadiness(
                path, chunk -> chunk.equals(path.get(0))
                        ? RVP_ChunkPathLoader.ChunkReadiness.READY
                        : RVP_ChunkPathLoader.ChunkReadiness.NOT_REQUESTED);

        assertFalse(readiness.pathReady());
        assertEquals(1, readiness.readyChunkCount());
        assertEquals(path.get(1), readiness.firstUnreadyChunk());
        assertEquals(RVP_ChunkPathLoader.ChunkReadiness.NOT_REQUESTED, readiness.firstUnreadyState());
    }

    /** 验证空路径被明确拒绝，不能按“零个区块全部就绪”放行移动。 */
    @Test
    void emptyPathIsInvalidAndCannotMove() {
        RVP_ChunkPathLoader.PathReadiness readiness = RVP_ChunkPathLoader.checkPathReadiness(
                List.of(), ignored -> RVP_ChunkPathLoader.ChunkReadiness.READY);

        assertFalse(readiness.pathReady());
        assertEquals(0, readiness.readyChunkCount());
        assertNull(readiness.firstUnreadyChunk());
        assertEquals(RVP_ChunkPathLoader.ChunkReadiness.INVALID_PATH, readiness.firstUnreadyState());
    }

    /** 调用被测 supercover 规划器，缩短各测试用例的参数样板。 */
    private static List<ChunkPos> plan(Vec3 start, Vec3 motion, int horizonTicks, int maxChunks) {
        return RVP_ChunkPathLoader.collectSupercoverChunks(start, motion, horizonTicks, maxChunks);
    }

    /** 构造期望的二维区块坐标。 */
    private static ChunkPos chunk(int x, int z) {
        return new ChunkPos(x, z);
    }

    /** 将期望区块按顺序组装为不可变列表。 */
    private static List<ChunkPos> chunks(ChunkPos... chunks) {
        return List.of(chunks);
    }
}
