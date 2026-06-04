package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 反辐射（ARM）制导专用参数。JSON 键 {@code arm_data}，配合
 * {@link RVP_GuidanceData.Source} 类型 {@code ARM} 使用。
 */
public class RVP_ArmData {

    /** 扫描辐射源间隔（tick），越小搜索越频繁。 */
    @SerializedName("scan_interval_tick")
    private int scanIntervalTick = 2;

    /**
     * 丢失目标后仍沿末次方向制导的 tick 数；0 表示丢失即放弃 ARM 逻辑。
     */
    @SerializedName("memory_tick")
    private int memoryTick = 0;

    /**
     * 辐射脉冲记忆 tick：在此窗口内记住雷达辐射事件用于再捕获。
     */
    @SerializedName("radiation_pulse_memory_tick")
    private int radiationPulseMemoryTick = 25;

    /** 丢失锁定后是否允许重新搜索辐射源。 */
    @SerializedName("allow_reacquire")
    private boolean allowReacquire = true;

    /** 已锁定目标时的制导权重加成（相对扫描候选）。 */
    @SerializedName("locked_bonus")
    private float lockedBonus = 0.5f;

    public int getScanIntervalTick() {
        return Math.max(scanIntervalTick, 1);
    }

    public int getMemoryTick() {
        return Math.max(memoryTick, 0);
    }

    public int getRadiationPulseMemoryTick() {
        return Math.max(radiationPulseMemoryTick, 0);
    }

    public boolean isAllowReacquire() {
        return allowReacquire;
    }

    public float getLockedBonus() {
        return lockedBonus;
    }
}
