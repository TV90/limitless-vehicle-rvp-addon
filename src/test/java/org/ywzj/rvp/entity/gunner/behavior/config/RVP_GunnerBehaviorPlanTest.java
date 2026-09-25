package org.ywzj.rvp.entity.gunner.behavior.config;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorContext;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorRuntime;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerIntentSink;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_IGunnerBehavior;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 阶段 D 不可变固定计划的纯 Java 契约测试。 */
class RVP_GunnerBehaviorPlanTest {

    @Test
    void preservesDeclarationOrderAndFiltersByStageAndCapability() {
        RVP_IGunnerBehavior first = behavior("first", true,
                RVP_IGunnerBehavior.Stage.TARGET, RVP_IGunnerBehavior.Stage.SUPPORT);
        RVP_IGunnerBehavior disabled = behavior("disabled", false,
                RVP_IGunnerBehavior.Stage.TARGET);
        RVP_IGunnerBehavior last = behavior("last", true,
                RVP_IGunnerBehavior.Stage.TARGET);
        RVP_GunnerBehaviorPlan plan = new RVP_GunnerBehaviorPlan(List.of(first, disabled, last));

        assertEquals(List.of("first", "disabled", "last"),
                plan.behaviors().stream().map(RVP_IGunnerBehavior::id).toList());
        assertEquals(List.of("first", "last"),
                plan.applicable(RVP_IGunnerBehavior.Stage.TARGET, null).stream()
                        .map(RVP_IGunnerBehavior::id).toList());
        assertEquals(List.of("first"),
                plan.applicable(RVP_IGunnerBehavior.Stage.SUPPORT, null).stream()
                        .map(RVP_IGunnerBehavior::id).toList());
    }

    @Test
    void rejectsDuplicateBehaviorInstanceIds() {
        RVP_IGunnerBehavior first = behavior("duplicate", true, RVP_IGunnerBehavior.Stage.TARGET);
        RVP_IGunnerBehavior second = behavior("duplicate", true, RVP_IGunnerBehavior.Stage.SUPPORT);

        assertThrows(IllegalArgumentException.class,
                () -> new RVP_GunnerBehaviorPlan(List.of(first, second)));
    }

    /** 创建不接触 Minecraft 运行时的记录型测试行为。 */
    private static RVP_IGunnerBehavior behavior(String id, boolean applicable,
                                                 RVP_IGunnerBehavior.Stage... stages) {
        return new RVP_IGunnerBehavior() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<Stage> stages() {
                return Set.of(stages);
            }

            @Override
            public boolean isApplicable(RVP_GunnerBehaviorContext context) {
                return applicable;
            }

            @Override
            public void plan(Stage stage, RVP_GunnerBehaviorContext context,
                             RVP_GunnerBehaviorRuntime runtime, RVP_GunnerIntentSink sink) {
                // 计划结构测试不执行游戏行为。
            }
        };
    }
}
