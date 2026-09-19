package org.ywzj.rvp.entity.gunner.behavior.action;

/**
 * Gunner 动作适配器的统一执行结果。
 *
 * <p>阶段 C 的行为管理器将仲裁拒绝和动作层结果统一送回意图提交者，用于可靠推进运行时状态。</p>
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
    NOT_DRIVER,
    /** 同通道资源已被更高优先级或更早固定计划意图占用。 */
    OCCUPIED
}
