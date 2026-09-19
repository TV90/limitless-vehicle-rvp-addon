package org.ywzj.rvp.entity.gunner.behavior.runtime;

import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorIntent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按优先级、计划顺序和实例 ID 对单 tick 意图做确定性仲裁。 */
public final class RVP_GunnerIntentArbiter {

    /** 仲裁结果。 */
    public record Resolution(
            /** 按稳定键保存的通道胜者。 */ Map<String, RVP_GunnerBehaviorIntent> winners,
            /** 被拒绝候选及原因。 */ List<String> rejections,
            /** 被拒绝候选对象，用于回传 OCCUPIED。 */ List<RVP_GunnerBehaviorIntent> rejectedIntents) {}

    /** 高优先级优先；同优先级按固定计划顺序，再按实例 ID 字典序。 */
    private static final Comparator<RVP_GunnerBehaviorIntent> ORDER = Comparator
            .comparingInt(RVP_GunnerBehaviorIntent::priority).reversed()
            .thenComparingInt(RVP_GunnerBehaviorIntent::planOrder)
            .thenComparing(RVP_GunnerBehaviorIntent::behaviorId);

    /** 对候选进行仲裁；COUNTERMEASURE 按类型资源键合并，其余通道每资源键一个胜者。 */
    public Resolution resolve(List<RVP_GunnerBehaviorIntent> candidates) {
        List<RVP_GunnerBehaviorIntent> ordered = new ArrayList<>(candidates);
        ordered.sort(ORDER);
        Map<String, RVP_GunnerBehaviorIntent> winners = new LinkedHashMap<>();
        List<String> rejections = new ArrayList<>();
        List<RVP_GunnerBehaviorIntent> rejectedIntents = new ArrayList<>();
        for (RVP_GunnerBehaviorIntent intent : ordered) {
            String key = key(intent);
            RVP_GunnerBehaviorIntent winner = winners.putIfAbsent(key, intent);
            if (winner != null) {
                rejections.add(intent.debugName() + " rejected: occupied by " + winner.debugName());
                rejectedIntents.add(intent);
            }
        }
        return new Resolution(Collections.unmodifiableMap(new LinkedHashMap<>(winners)),
                List.copyOf(rejections), List.copyOf(rejectedIntents));
    }

    /** 生成通道与资源组成的稳定仲裁键。 */
    public static String key(RVP_GunnerBehaviorIntent intent) {
        return intent.channel().name() + "/" + intent.resourceKey();
    }
}
