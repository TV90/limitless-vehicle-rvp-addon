package org.ywzj.rvp.entity.gunner.behavior.api;

/** 固定计划和后续行为提交意图的唯一入口。 */
@FunctionalInterface
public interface RVP_GunnerIntentSink {
    /** 提交一个只在当前 tick 有效的意图。 */
    void submit(RVP_GunnerBehaviorIntent intent);
}
