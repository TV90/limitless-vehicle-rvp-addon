package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.guidance.RVP_EnumCompositeMode;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumPhaseResolvePolicy;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 分段/复合制导配置。JSON 键 {@code guidance_data}。
 *
 * <p>制导阶段列表 {@code stages}：每阶段见 {@link RVP_GuidanceStageData}。
 * 多阶段激活窗口重叠时进入复合制导（见 {@link org.ywzj.rvp.guidance.RVP_GuidanceCompositeCompatibility}）。</p>
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

    @SerializedName("ignore_flares")
    private boolean ignoreFlares = false;

    @SerializedName("ignore_chaff")
    private boolean ignoreChaff = false;

    @SerializedName("jam_resistance")
    private float jamResistance = 0f;

    @SerializedName("dircm_resistance")
    private float dircmResistance = 0f;

    @SerializedName("home_on_jam")
    private boolean homeOnJam = false;

    @SerializedName("decoy_filter")
    private float decoyFilter = 0f;

    @SerializedName("radiation_pulse_memory_tick")
    private int radiationPulseMemoryTick = 40;

    @SerializedName("arm_memory_tick")
    private int armMemoryTick = 120;

    @SerializedName("arm_locked_emitter_bonus")
    private float armLockedEmitterBonus = 0f;

    @SerializedName("terminal_guidance")
    private RVP_TerminalGuidanceData terminalGuidance;

    /** 多阶段重叠且制导类型不兼容时的消解策略。 */
    @SerializedName("phase_resolve_policy")
    private RVP_EnumPhaseResolvePolicy phaseResolvePolicy = RVP_EnumPhaseResolvePolicy.HIGHEST_SPECIFICITY;

    @SerializedName(value = "stages", alternate = {"phases"})
    private List<RVP_GuidanceStageData> stages = Collections.emptyList();

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

    public boolean isIgnoreFlares() {
        return ignoreFlares;
    }

    public boolean isIgnoreChaff() {
        return ignoreChaff;
    }

    public float getJamResistance() {
        return Math.max(jamResistance, 0f);
    }

    public float getDircmResistance() {
        return Math.max(dircmResistance, 0f);
    }

    public boolean isHomeOnJam() {
        return homeOnJam;
    }

    public float getDecoyFilter() {
        return Math.max(decoyFilter, 0f);
    }

    public int getRadiationPulseMemoryTick() {
        return Math.max(radiationPulseMemoryTick, 0);
    }

    public int getArmMemoryTick() {
        return Math.max(armMemoryTick, 0);
    }

    public float getArmLockedEmitterBonus() {
        return armLockedEmitterBonus;
    }

    @Nullable
    public RVP_TerminalGuidanceData getTerminalGuidance() {
        return terminalGuidance;
    }

    public RVP_EnumPhaseResolvePolicy getPhaseResolvePolicy() {
        return phaseResolvePolicy == null ? RVP_EnumPhaseResolvePolicy.HIGHEST_SPECIFICITY : phaseResolvePolicy;
    }

    public List<RVP_GuidanceStageData> getStages() {
        return stages == null ? Collections.emptyList() : stages;
    }

    public boolean hasSourceType(RVP_EnumGuidanceType type) {
        if (type == null) {
            return false;
        }
        return getStages().stream()
                .flatMap(stage -> stage.getSources().stream())
                .anyMatch(source -> source.getType() == type);
    }

    public boolean usesGuidanceType(RVP_EnumGuidanceType type) {
        if (type == null) {
            return false;
        }
        if (hasSourceType(type) || getGuidanceType() == type) {
            return true;
        }
        return terminalGuidance != null && terminalGuidance.getGuidanceType() == type;
    }

    /** 全阶段中 priority 最高的指定类型 source；无则 {@code null}。 */
    @Nullable
    public Source findPrimarySource(RVP_EnumGuidanceType type) {
        if (type == null) {
            return null;
        }
        Source best = null;
        for (RVP_GuidanceStageData stage : getStages()) {
            for (Source source : stage.getSources()) {
                if (source.getType() != type) {
                    continue;
                }
                if (best == null || source.getPriority() > best.getPriority()) {
                    best = source;
                }
            }
        }
        return best;
    }

    public RVP_GuidanceSourceParamsData findPrimarySourceParams(RVP_EnumGuidanceType type) {
        Source source = findPrimarySource(type);
        return source != null ? source.getParams() : new RVP_GuidanceSourceParamsData();
    }

    public static class Source {

        @SerializedName("type")
        private RVP_EnumGuidanceType type = RVP_EnumGuidanceType.NONE;

        @SerializedName("priority")
        private int priority = 0;

        @SerializedName("composite_mode")
        private RVP_EnumCompositeMode compositeMode = RVP_EnumCompositeMode.PRIMARY;

        @SerializedName("fallback_on_jammed")
        private boolean fallbackOnJammed = true;

        @SerializedName("take_over_motion")
        private boolean takeOverMotion = false;

        @SerializedName("weight")
        private double weight = 1.0;

        @SerializedName("steering_data")
        private RVP_GuidanceSteeringData steeringData;

        @SerializedName("params")
        private RVP_GuidanceSourceParamsData params = new RVP_GuidanceSourceParamsData();

        public RVP_EnumGuidanceType getType() {
            return type == null ? RVP_EnumGuidanceType.NONE : type;
        }

        public int getPriority() {
            return priority;
        }

        public RVP_EnumCompositeMode getCompositeMode() {
            return compositeMode == null ? RVP_EnumCompositeMode.PRIMARY : compositeMode;
        }

        public boolean isFallbackOnJammed() {
            return fallbackOnJammed;
        }

        public boolean isTakeOverMotion() {
            return takeOverMotion;
        }

        public double getWeight() {
            return weight <= 0.0 ? 1.0 : weight;
        }

        public RVP_GuidanceSteeringData getSteeringData() {
            return steeringData;
        }

        public RVP_GuidanceSourceParamsData getParams() {
            return params == null ? new RVP_GuidanceSourceParamsData() : params;
        }
    }
}
