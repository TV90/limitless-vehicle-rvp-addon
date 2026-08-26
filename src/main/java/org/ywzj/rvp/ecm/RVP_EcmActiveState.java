package org.ywzj.rvp.ecm;

/**
 * 单台装备 ECM_ACTIVE 载具的主动电子战运行状态（服务端持有）。
 *
 * <p>状态机：就绪 → 释放后 active → 冷却 → 就绪；冷却期间再次按键无效。</p>
 */
public final class RVP_EcmActiveState {

    /** 剩余主动干扰时长（tick）：>0 表示正处于干扰状态（设计文档 200 tick 默认）。 */
    private int activeRemainTicks;

    /** 剩余冷却时长（tick）：>0 期间不可再次释放（设计文档 600 tick 默认）。 */
    private int cooldownRemainTicks;

    /** 剩余反辐射优先级窗口时长（tick）：>0 时为 ARM 最高优先级目标（默认 100 tick = 5 秒）。 */
    private int armPriorityRemainTicks;

    public int getActiveRemainTicks() {
        return activeRemainTicks;
    }

    public void setActiveRemainTicks(int activeRemainTicks) {
        this.activeRemainTicks = Math.max(0, activeRemainTicks);
    }

    public int getCooldownRemainTicks() {
        return cooldownRemainTicks;
    }

    public void setCooldownRemainTicks(int cooldownRemainTicks) {
        this.cooldownRemainTicks = Math.max(0, cooldownRemainTicks);
    }

    public int getArmPriorityRemainTicks() {
        return armPriorityRemainTicks;
    }

    public void setArmPriorityRemainTicks(int armPriorityRemainTicks) {
        this.armPriorityRemainTicks = Math.max(0, armPriorityRemainTicks);
    }

    /** 是否处于主动干扰状态。 */
    public boolean isActive() {
        return activeRemainTicks > 0;
    }

    /** 是否处于冷却中。 */
    public boolean isCoolingDown() {
        return cooldownRemainTicks > 0;
    }

    /** 是否处于 ARM 高优先级窗口。 */
    public boolean isArmPriority() {
        return armPriorityRemainTicks > 0;
    }
}
