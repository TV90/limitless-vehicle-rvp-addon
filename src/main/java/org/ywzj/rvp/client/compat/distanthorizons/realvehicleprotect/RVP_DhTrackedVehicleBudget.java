package org.ywzj.rvp.client.compat.distanthorizons.realvehicleprotect;

import java.util.Comparator;
import java.util.List;

/** 真实载具保护层的纯逻辑稳定预算选择器。 */
public final class RVP_DhTrackedVehicleBudget {
    private RVP_DhTrackedVehicleBudget() {
    }

    /**
     * 按屏幕贡献降序、距离升序、实体 ID 升序选择候选。
     *
     * @param candidates 未排序候选
     * @param limit      允许返回的最大数量
     * @return 不修改输入集合的稳定选择结果
     */
    public static List<Candidate> select(List<Candidate> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> candidate != null
                        && Double.isFinite(candidate.distanceSquared())
                        && Double.isFinite(candidate.screenContribution()))
                .sorted(Comparator.comparingDouble(Candidate::screenContribution).reversed()
                        .thenComparingDouble(Candidate::distanceSquared)
                        .thenComparingInt(Candidate::entityId))
                .limit(limit)
                .toList();
    }

    /** 预算排序所需的最小候选字段。 */
    public record Candidate(int entityId, double distanceSquared, double screenContribution) {
    }
}
