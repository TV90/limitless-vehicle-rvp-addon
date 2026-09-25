package org.ywzj.rvp.entity.gunner.behavior.config;

import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorContext;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_IGunnerBehavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 D 使用的不可变固定行为计划。
 *
 * <p>Profile 行为列表会在阶段 F 接入；当前计划仍由代码固定，但每项已经是独立行为实例，
 * 管理器不再调用旧的战术总编排。</p>
 */
public final class RVP_GunnerBehaviorPlan {

    /** 按固定顺序保存的全部行为实例。 */
    private final List<RVP_IGunnerBehavior> behaviors;
    /** 行为实例 ID 索引，用于生命周期退出与诊断。 */
    private final Map<String, RVP_IGunnerBehavior> behaviorsById;
    /** 各固定阶段的行为顺序。 */
    private final Map<RVP_IGunnerBehavior.Stage, List<RVP_IGunnerBehavior>> byStage;

    public RVP_GunnerBehaviorPlan(List<RVP_IGunnerBehavior> behaviors) {
        List<RVP_IGunnerBehavior> ordered = List.copyOf(behaviors);
        Map<String, RVP_IGunnerBehavior> ids = new LinkedHashMap<>();
        Map<RVP_IGunnerBehavior.Stage, List<RVP_IGunnerBehavior>> stages =
                new EnumMap<>(RVP_IGunnerBehavior.Stage.class);
        for (RVP_IGunnerBehavior.Stage stage : RVP_IGunnerBehavior.Stage.values()) {
            stages.put(stage, new ArrayList<>());
        }
        for (RVP_IGunnerBehavior behavior : ordered) {
            if (ids.putIfAbsent(behavior.id(), behavior) != null) {
                throw new IllegalArgumentException("重复 Gunner 行为实例 ID: " + behavior.id());
            }
            for (RVP_IGunnerBehavior.Stage stage : behavior.stages()) {
                stages.get(stage).add(behavior);
            }
        }
        Map<RVP_IGunnerBehavior.Stage, List<RVP_IGunnerBehavior>> immutableStages =
                new EnumMap<>(RVP_IGunnerBehavior.Stage.class);
        stages.forEach((stage, values) -> immutableStages.put(stage, List.copyOf(values)));
        this.behaviors = ordered;
        this.behaviorsById = Collections.unmodifiableMap(ids);
        this.byStage = Collections.unmodifiableMap(immutableStages);
    }

    /** 返回全部固定行为，保持计划声明顺序。 */
    public List<RVP_IGunnerBehavior> behaviors() {
        return behaviors;
    }

    /** 返回指定阶段中当前能力适用的行为。 */
    public List<RVP_IGunnerBehavior> applicable(RVP_IGunnerBehavior.Stage stage,
                                                RVP_GunnerBehaviorContext context) {
        return byStage.get(stage).stream().filter(behavior -> behavior.isApplicable(context)).toList();
    }

    /** 按实例 ID 查找行为；不存在时返回 null。 */
    public RVP_IGunnerBehavior behavior(String id) {
        return behaviorsById.get(id);
    }
}
