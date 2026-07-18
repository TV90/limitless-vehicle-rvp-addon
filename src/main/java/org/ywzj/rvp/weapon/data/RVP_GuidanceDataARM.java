package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

public class RVP_GuidanceDataARM extends RVP_GuidanceData {

    @SerializedName("radiation_pulse_memory_tick")
    private int radiationPulseMemoryTick = 30;

    @SerializedName("arm_memory_tick")
    private int armMemoryTick = 60;

    @SerializedName("arm_locked_emitter_bonus")
    private float armLockedEmitterBonus = 1f;

    public int getRadiationPulseMemoryTick() {
        return Math.max(radiationPulseMemoryTick, 0);
    }

    public int getArmMemoryTick() {
        return Math.max(armMemoryTick, 0);
    }

    public float getArmLockedEmitterBonus() {
        return Math.max(armLockedEmitterBonus, 0f);
    }
}
