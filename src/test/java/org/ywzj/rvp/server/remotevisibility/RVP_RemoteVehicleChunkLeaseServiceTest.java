package org.ywzj.rvp.server.remotevisibility;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_CommonConfig.ChunkLoadingMode;
import org.ywzj.rvp.server.remotevisibility.RVP_RemoteVehicleChunkLeaseService.LeaseCandidate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 远距载具每维度数量预算和稳定排序的纯单元测试。 */
class RVP_RemoteVehicleChunkLeaseServiceTest {
    /** OFF 不提交；AI_UAV_ONLY 只接受 Gunner/UAV；VISIBLE_TARGETS 接受全部授权目标。 */
    @Test
    void chunkLoadingModesFilterCandidates() {
        LeaseCandidate ordinary = candidate(1, false, true, 100, false);
        LeaseCandidate aiOrUav = candidate(2, false, false, 200, true);

        assertTrue(select(ChunkLoadingMode.OFF, 10, List.of(ordinary, aiOrUav)).isEmpty());
        assertEquals(List.of(2), ids(select(
                ChunkLoadingMode.AI_UAV_ONLY, 10, List.of(ordinary, aiOrUav))));
        assertEquals(List.of(1, 2), ids(select(
                ChunkLoadingMode.VISIBLE_TARGETS, 10, List.of(ordinary, aiOrUav))));
    }

    /** 排序固定为已有租约、运动、最近观察者和实体 ID，后续条件不能越级。 */
    @Test
    void stablePriorityOrderMatchesLeaseContract() {
        List<LeaseCandidate> selected = select(
                ChunkLoadingMode.VISIBLE_TARGETS,
                10,
                List.of(
                        candidate(8, false, false, 10, false),
                        candidate(7, false, true, 500, false),
                        candidate(6, true, false, 1000, false),
                        candidate(5, false, true, 100, false),
                        candidate(4, false, true, 100, false)));

        assertEquals(List.of(6, 4, 5, 7, 8), ids(selected));
    }

    /** 维度预算为零时无租约，正数预算只保留稳定排序的前缀。 */
    @Test
    void dimensionBudgetUsesStablePrefix() {
        List<LeaseCandidate> candidates = List.of(
                candidate(3, false, true, 300, false),
                candidate(2, false, true, 200, false),
                candidate(1, false, true, 100, false));

        assertTrue(select(ChunkLoadingMode.VISIBLE_TARGETS, 0, candidates).isEmpty());
        assertEquals(List.of(1, 2), ids(select(
                ChunkLoadingMode.VISIBLE_TARGETS, 2, candidates)));
    }

    /** 构造具有稳定 UUID 的纯租约候选。 */
    private static LeaseCandidate candidate(
            int entityId,
            boolean previous,
            boolean moving,
            double distance,
            boolean aiOrUav) {
        return new LeaseCandidate(
                new UUID(0L, entityId), entityId, previous, moving, distance * distance, aiOrUav);
    }

    /** 调用被测纯预算器。 */
    private static List<LeaseCandidate> select(
            ChunkLoadingMode mode,
            int maximumVehicles,
            List<LeaseCandidate> candidates) {
        return RVP_RemoteVehicleChunkLeaseService.selectForBudget(mode, maximumVehicles, candidates);
    }

    /** 提取选择结果中的实体 ID。 */
    private static List<Integer> ids(List<LeaseCandidate> candidates) {
        return candidates.stream().map(LeaseCandidate::entityId).toList();
    }
}
