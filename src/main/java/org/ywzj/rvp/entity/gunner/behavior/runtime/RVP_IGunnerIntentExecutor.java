package org.ywzj.rvp.entity.gunner.behavior.runtime;

import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionResult;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorContext;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorIntent;

/** 管理器的动作执行边界；测试可用记录型 fake 验证唯一胜者。 */
@FunctionalInterface
public interface RVP_IGunnerIntentExecutor {
    /** 执行一个已经通过仲裁的意图。 */
    RVP_GunnerActionResult execute(RVP_GunnerBehaviorContext context, RVP_GunnerBehaviorIntent intent);
}
