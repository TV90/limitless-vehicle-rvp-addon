package org.ywzj.rvp.client.render.remotevisibility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 远距载具的纯数值稳定预算器，不读取或创建任何模型资源。 */
public final class RVP_RemoteVehicleRenderBudget {
    /** 屏幕贡献降序、距离升序、实体 ID 升序的稳定优先级。 */
    private static final Comparator<Candidate> PRIORITY = Comparator
            .comparingDouble(Candidate::screenContribution).reversed()
            .thenComparingDouble(Candidate::distanceSquared)
            .thenComparingInt(Candidate::entityId);

    private RVP_RemoteVehicleRenderBudget() {
    }

    /**
     * 在总量预算内选择候选，并单独限制没有整模型 LOD 的高模回退数量。
     * 被跳过的高模不会占用总量名额，后续低模候选可以补足。
     */
    public static List<Candidate> select(List<Candidate> candidates, int maxTotal, int maxFallbackHighModels) {
        if (candidates.isEmpty() || maxTotal <= 0) {
            return List.of();
        }
        int totalLimit = Math.max(0, maxTotal);
        int fallbackLimit = Math.max(0, Math.min(maxFallbackHighModels, totalLimit));
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(PRIORITY);

        List<Candidate> selected = new ArrayList<>(Math.min(totalLimit, sorted.size()));
        int fallbackCount = 0;
        for (Candidate candidate : sorted) {
            if (selected.size() >= totalLimit) {
                break;
            }
            if (candidate.fallbackHighModel && fallbackCount >= fallbackLimit) {
                continue;
            }
            selected.add(candidate);
            if (candidate.fallbackHighModel) {
                fallbackCount++;
            }
        }
        return List.copyOf(selected);
    }

    /**
     * 已通过距离和视锥初筛的纯数值候选。
     *
     * @param entityId 服务端实体 ID，用作最终稳定排序键
     * @param distanceSquared 相机三维距离平方
     * @param screenContribution 结构尺寸相对距离得到的屏幕贡献分数
     * @param fallbackHighModel 是否没有整模型 LOD、需要使用静态原模型回退
     */
    public record Candidate(int entityId, double distanceSquared, double screenContribution,
                            boolean fallbackHighModel) {
    }
}
