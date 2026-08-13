package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 分段/复合制导配置。JSON 键 {@code guidance_data}。
 *
 * <p>主制导参数直接写在本对象中，可选末段参数写在 {@code terminal_guidance}。</p>
 */
public class RVP_GuidanceData {

    @SerializedName("guidance_type")
    private RVP_EnumGuidanceType guidanceType = RVP_EnumGuidanceType.NONE;

    @SerializedName("guidance_tick_range")
    private RVP_Range<Integer> guidanceTickRange;

    @SerializedName("guidance_target_distance_range")
    private RVP_Range<Float> guidanceTargetDistanceRange;

    @SerializedName("guidance_altitude_range")
    private RVP_Range<Float> guidanceAltitudeRange;

    @SerializedName("lock_target_distance_range")
    private RVP_Range<Float> lockTargetDistanceRange;

    @SerializedName("lock_altitude_range")
    private RVP_Range<Float> lockAltitudeRange;

    @SerializedName("enable_ir_hmd")
    private boolean enableIrHmd = true;

    @SerializedName("max_guidance_angle")
    private int maxGuidanceAngle = 60;

    @SerializedName("scan_interval_tick")
    private Integer scanIntervalTick;

    /** Full seeker FOV. Runtime cone checks must use half of this value. */
    @SerializedName("max_lock_angle")
    private int maxLockAngle = 5;

    /** Single-sided off-axis hold angle. */
    @SerializedName("max_off_axis_lock_angle")
    private int maxOffAxisLockAngle = 60;

    @SerializedName("predict_target_pos")
    private boolean predictTargetPos = false;

    @SerializedName("predict_target_pos_gain")
    private float predictTargetPosGain = 3.0f;

    @SerializedName("max_lateral_accel")
    private float maxLateralAccel = 0f;

    @SerializedName("predict_target_pos_start_tick")
    private int predictTargetPosStartTick = 10;

    @SerializedName("top_attack_height")
    private Float topAttackHeight;

    @SerializedName("cruise_start_tick")
    private Integer cruiseStartTick;

    @SerializedName("cruise_end_horizontal_dist")
    private float cruiseEndHorizontalDist = 10f;

    @SerializedName("cruise_gravity_scale")
    private float cruiseGravityScale = 1f;

    @SerializedName("cruise_leveling_factor")
    private float cruiseLevelingFactor = 0.15f;

    /** 弹道导弹巡航高度（相对发射点Y）；{@code > 0} 启用 PRESET 三段式弹道，默认 0 禁用。 */
    @SerializedName("preset_cruise_altitude")
    private float presetCruiseAltitude = 0f;

    /** 上升段前伸量上限，实际取 {@code min(值, 25%×水平距离)}。 */
    @SerializedName("preset_max_ascent_lead")
    private float presetMaxAscentLead = 64f;

    /** 上升段完成判定半径。 */
    @SerializedName("preset_ascent_radius")
    private float presetAscentRadius = 24f;

    /** 俯冲段最小启动水平距离。 */
    @SerializedName("preset_dive_radius")
    private float presetDiveRadius = 24f;

    /** 俯冲距离 = 高度差 × 因子。 */
    @SerializedName("preset_dive_altitude_factor")
    private float presetDiveAltitudeFactor = 0.75f;

    /** 俯冲距离 = 近似转弯半径 × 因子。 */
    @SerializedName("preset_dive_lead_factor")
    private float presetDiveLeadFactor = 1.5f;

    /** 高度闭环 P 增益。 */
    @SerializedName("preset_cruise_altitude_gain")
    private float presetCruiseAltitudeGain = 0.002f;

    /** 高度闭环 D 阻尼。 */
    @SerializedName("preset_cruise_vertical_damping")
    private float presetCruiseVerticalDamping = 0.05f;

    /** 垂直分量占速率比例上限。 */
    @SerializedName("preset_cruise_max_vertical_component")
    private float presetCruiseMaxVerticalComponent = 0.5f;

    /** 弹道中段战术机动（横向蛇形规避摆动）幅度，格；0 = 关闭。 */
    @SerializedName("preset_tactical_maneuver_amplitude")
    private float presetTacticalManeuverAmplitude = 0f;

    @SerializedName("lock_angle_gate")
    private Map<RVP_Range<Float>, RVP_Range<Float>> lockAngleGate;

    @SerializedName("guidance_angle_gate")
    private Map<RVP_Range<Float>, RVP_Range<Float>> guidanceAngleGate;

    @SerializedName("angle_gate_lock_out_tick")
    private int angleGateLockOutTick = 20;

    @SerializedName("active_radar_activation_range")
    private int activeRadarActivationRange = 256;

    @SerializedName("enable_inertial_guidance")
    private boolean enableInertialGuidance = false;

    @SerializedName("terminal_guidance")
    private RVP_TerminalGuidanceData terminalGuidance;

    /** 干扰物干扰数据（仅对 IR/AIR/SARH/ARH 生效）；null 表示不启用（按默认简化语义）。 */
    @SerializedName("interference_data")
    private RVP_InterferenceData interferenceData;

    /** 多阶段重叠且制导类型不兼容时的消解策略。 */
    public RVP_EnumGuidanceType getGuidanceType() {
        return guidanceType == null ? RVP_EnumGuidanceType.NONE : guidanceType;
    }

    public RVP_Range<Integer> getGuidanceTickRange() {
        return guidanceTickRange;
    }

    public RVP_Range<Float> getGuidanceTargetDistanceRange() {
        return guidanceTargetDistanceRange;
    }

    public RVP_Range<Float> getGuidanceAltitudeRange() {
        return guidanceAltitudeRange;
    }

    public RVP_Range<Float> getLockTargetDistanceRange() {
        return lockTargetDistanceRange;
    }

    public RVP_Range<Float> getLockAltitudeRange() {
        return lockAltitudeRange;
    }

    public boolean isEnableIrHmd() {
        return enableIrHmd;
    }

    public int getMaxGuidanceAngle() {
        return Math.max(maxGuidanceAngle, 0);
    }

    public Integer getScanIntervalTick() {
        return scanIntervalTick == null ? null : Math.max(scanIntervalTick, 1);
    }

    public int getMaxLockAngle() {
        return Math.max(maxLockAngle, 0);
    }

    public float getMaxLockHalfAngle() {
        return getMaxLockAngle() * 0.5f;
    }

    public int getMaxOffAxisLockAngle() {
        return Math.max(maxOffAxisLockAngle, 0);
    }

    public boolean isPredictTargetPos() {
        return predictTargetPos;
    }

    public float getPredictTargetPosGain() {
        return Math.max(predictTargetPosGain, 0f);
    }

    public float getMaxLateralAccel() {
        return Math.max(maxLateralAccel, 0f);
    }

    public int getPredictTargetPosStartTick() {
        return Math.max(predictTargetPosStartTick, 0);
    }

    public Float getTopAttackHeight() {
        return topAttackHeight;
    }

    public Integer getCruiseStartTick() {
        return cruiseStartTick == null ? null : Math.max(cruiseStartTick, 0);
    }

    public float getCruiseEndHorizontalDist() {
        return Math.max(cruiseEndHorizontalDist, 0f);
    }

    public float getCruiseGravityScale() {
        return cruiseGravityScale;
    }

    public float getCruiseLevelingFactor() {
        return Math.max(cruiseLevelingFactor, 0f);
    }

    /** @return 弹道导弹巡航高度（相对发射点Y）；{@code > 0} 启用 PRESET 三段式弹道。 */
    public float getPresetCruiseAltitude() {
        return Math.max(presetCruiseAltitude, 0f);
    }

    public float getPresetMaxAscentLead() {
        return Math.max(presetMaxAscentLead, 0f);
    }

    public float getPresetAscentRadius() {
        return Math.max(presetAscentRadius, 0f);
    }

    public float getPresetDiveRadius() {
        return Math.max(presetDiveRadius, 0f);
    }

    public float getPresetDiveAltitudeFactor() {
        return Math.max(presetDiveAltitudeFactor, 0f);
    }

    public float getPresetDiveLeadFactor() {
        return Math.max(presetDiveLeadFactor, 0f);
    }

    public float getPresetCruiseAltitudeGain() {
        return Math.max(presetCruiseAltitudeGain, 0f);
    }

    public float getPresetCruiseVerticalDamping() {
        return Math.max(presetCruiseVerticalDamping, 0f);
    }

    public float getPresetCruiseMaxVerticalComponent() {
        return Math.max(presetCruiseMaxVerticalComponent, 0f);
    }

    public float getPresetTacticalManeuverAmplitude() {
        return Math.max(presetTacticalManeuverAmplitude, 0f);
    }

    public Map<RVP_Range<Float>, RVP_Range<Float>> getLockAngleGate() {
        return lockAngleGate;
    }

    public Map<RVP_Range<Float>, RVP_Range<Float>> getGuidanceAngleGate() {
        return guidanceAngleGate;
    }

    public int getAngleGateLockOutTick() {
        return Math.max(angleGateLockOutTick, 0);
    }

    public int getActiveRadarActivationRange() {
        return Math.max(activeRadarActivationRange, 0);
    }

    public boolean isEnableInertialGuidance() {
        return enableInertialGuidance;
    }

    @Nullable
    public RVP_TerminalGuidanceData getTerminalGuidance() {
        return terminalGuidance;
    }

    @Nullable
    public RVP_InterferenceData getInterferenceData() {
        return interferenceData;
    }

    public boolean usesGuidanceType(RVP_EnumGuidanceType type) {
        if (type == null) {
            return false;
        }
        if (getGuidanceType() == type) {
            return true;
        }
        return terminalGuidance != null && terminalGuidance.getGuidanceType() == type;
    }

    /** 全阶段中 priority 最高的指定类型 source；无则 {@code null}。 */
}
