package org.ywzj.rvp.entity.gunner.behavior.api;

import org.ywzj.rvp.entity.gunner.GunnerEntity;

import java.util.Set;

/**
 * Gunner 内建行为的只读规划接口。
 *
 * <p>行为只能读取单 tick 上下文、维护自身实例状态并提交意图；所有载具、武器、雷达、
 * 制导与反制写操作均由管理器仲裁后进入动作能力层。</p>
 */
public interface RVP_IGunnerBehavior {

    /** 固定计划执行阶段。 */
    enum Stage {
        /** 选择本 tick 唯一权威目标。 */ TARGET,
        /** 提交补给、雷达、制导和防御支持意图。 */ SUPPORT,
        /** 提交移动、常规交战和多通道状态机意图。 */ TACTICS
    }

    /** 返回稳定行为实例 ID。 */
    String id();

    /** 返回行为参与的固定阶段；集合不得在运行时修改。 */
    Set<Stage> stages();

    /** 判断当前 Context 是否具备运行该行为的硬能力。 */
    default boolean isApplicable(RVP_GunnerBehaviorContext context) {
        return true;
    }

    /** 行为首次进入当前固定计划或重新获得能力时初始化自身状态。 */
    default void onEnter(RVP_GunnerBehaviorContext context, RVP_GunnerBehaviorRuntime runtime) {
    }

    /** 在指定固定阶段读取 Context 并提交意图。 */
    void plan(Stage stage, RVP_GunnerBehaviorContext context,
              RVP_GunnerBehaviorRuntime runtime, RVP_GunnerIntentSink sink);

    /** 行为退出、换车或 Profile 重载时清理自身状态。 */
    default void onExit(GunnerEntity gunner, RVP_GunnerBehaviorRuntime runtime) {
        runtime.removeState(id());
    }
}
