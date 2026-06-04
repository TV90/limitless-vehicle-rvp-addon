package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 反辐射（ARM）制导专用参数，配合 {@code guidance_data} 中 {@code ARM} 源使用。
 */
public class RVP_ArmData {

    @SerializedName("scan_interval_tick")
    private int scanIntervalTick = 2;

    @SerializedName("memory_tick")
    private int memoryTick = 0;

    @SerializedName("radiation_pulse_memory_tick")
    private int radiationPulseMemoryTick = 25;

    @SerializedName("allow_reacquire")
    private boolean allowReacquire = true;

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
