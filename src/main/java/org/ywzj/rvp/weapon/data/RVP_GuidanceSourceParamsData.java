package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

/**
 * Per-source optional parameters for {@link RVP_GuidanceData.Source#params}.
 *
 * <p>通用字段（IOG/GPS/雷达弹等）与 {@link RVP_EnumGuidanceType#ARM} 专用字段均写在本对象内，
 * 按 source 类型取用。人在回路参数见 {@link RVP_HumanInTheLoopData}。</p>
 */
public class RVP_GuidanceSourceParamsData {

    @SerializedName("use_target_pos")
    private Boolean useTargetPos;

    @SerializedName("terminal_dive_angle")
    private Float terminalDiveAngle;

    @SerializedName("use_last_guidance")
    private Boolean useLastGuidance;

    @SerializedName("use_launch_heading")
    private Boolean useLaunchHeading;

    @SerializedName("vehicle_only")
    private Boolean vehicleOnly;

    @SerializedName("reacquire")
    private Boolean reacquire;

    @SerializedName("memory_tick")
    private Integer memoryTick;

    @SerializedName("require_illumination")
    private Boolean requireIllumination;

    @SerializedName("break_on_smoke")
    private Boolean breakOnSmoke;

    @SerializedName("use_weapon_unit_aim")
    private Boolean useWeaponUnitAim;

    @SerializedName("use_owner_look")
    private Boolean useOwnerLook;

    /** ARM：辐射源扫描间隔（tick）。 */
    @SerializedName("scan_interval_tick")
    private Integer scanIntervalTick;

    /** ARM：辐射脉冲记忆窗口（tick），用于再捕获。 */
    @SerializedName("radiation_pulse_memory_tick")
    private Integer radiationPulseMemoryTick;

    /** ARM：已锁定辐射源时的制导评分加成。 */
    @SerializedName("locked_bonus")
    private Float lockedBonus;

    public boolean useTargetPos(boolean defaultValue) {
        return useTargetPos != null ? useTargetPos : defaultValue;
    }

    public float terminalDiveAngle(float defaultValue) {
        return terminalDiveAngle != null ? terminalDiveAngle : defaultValue;
    }

    public boolean useLastGuidance(boolean defaultValue) {
        return useLastGuidance != null ? useLastGuidance : defaultValue;
    }

    public boolean useLaunchHeading(boolean defaultValue) {
        return useLaunchHeading != null ? useLaunchHeading : defaultValue;
    }

    public boolean vehicleOnly(boolean defaultValue) {
        return vehicleOnly != null ? vehicleOnly : defaultValue;
    }

    public boolean reacquire(boolean defaultValue) {
        return reacquire != null ? reacquire : defaultValue;
    }

    public int memoryTick(int defaultValue) {
        return memoryTick != null ? Math.max(memoryTick, 0) : defaultValue;
    }

    public boolean requireIllumination(boolean defaultValue) {
        return requireIllumination != null ? requireIllumination : defaultValue;
    }

    public boolean breakOnSmoke(boolean defaultValue) {
        return breakOnSmoke != null ? breakOnSmoke : defaultValue;
    }

    public boolean useWeaponUnitAim(boolean defaultValue) {
        return useWeaponUnitAim != null ? useWeaponUnitAim : defaultValue;
    }

    public boolean useOwnerLook(boolean defaultValue) {
        return useOwnerLook != null ? useOwnerLook : defaultValue;
    }

    public int scanIntervalTick(int defaultValue) {
        return scanIntervalTick != null ? Math.max(scanIntervalTick, 1) : defaultValue;
    }

    public int radiationPulseMemoryTick(int defaultValue) {
        return radiationPulseMemoryTick != null ? Math.max(radiationPulseMemoryTick, 0) : defaultValue;
    }

    public float lockedBonus(float defaultValue) {
        return lockedBonus != null ? lockedBonus : defaultValue;
    }
}
