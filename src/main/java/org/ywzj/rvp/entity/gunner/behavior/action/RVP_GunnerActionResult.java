package org.ywzj.rvp.entity.gunner.behavior.action;

/**
 * Gunner 动作适配器的统一执行结果。
 *
 * <p>阶段 B 尚未引入意图仲裁，因此当前结果用于把“已提交”“门控拒绝”和“不支持”显式传回
 * 现行 {@code GunnerBrain}。后续行为管理器可以直接复用这些结果推进运行时状态。</p>
 */
public enum RVP_GunnerActionResult {
    /** 动作已由本适配器完整执行。 */
    EXECUTED,
    /** 动作已提交给不返回真实发射结果的本体接口，最终是否生成弹体由本体权威链决定。 */
    DISPATCHED,
    /** 动作因冷却、弹药、锁定或射界等临时条件被拒绝。 */
    GATED,
    /** 当前载具、武器站或目标不具备该能力。 */
    UNSUPPORTED,
    /** 输入对象已失效或不完整。 */
    INVALID,
    /** 提交者不是当前载具司机，移动动作被拒绝。 */
    NOT_DRIVER
}
