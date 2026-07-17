package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import java.util.Map;

public class RVP_TerminalGuidanceData {

    @SerializedName("guidance_type")
    private RVP_EnumGuidanceType guidanceType = RVP_EnumGuidanceType.NONE;

    @SerializedName("active_radar_activation_range")
    private int activeRadarActivationRange = 256;

    @SerializedName("max_lock_angle")
    private int maxLockAngle = 5;

    @SerializedName("guidance_target_distance_range")
    private RVP_Range<Float> guidanceTargetDistanceRange;

    @SerializedName("guidance_start_tick")
    private Integer guidanceStartTick;

    @SerializedName("guidance_start_dist")
    private Float guidanceStartDist;

    @SerializedName("guidance_start_horizontal_dist")
    private Float guidanceStartHorizontalDist;

    @SerializedName("guidance_altitude_range")
    private RVP_Range<Float> guidanceAltitudeRange;

    @SerializedName("max_guidance_angle")
    private int maxGuidanceAngle = 60;

    @SerializedName("scan_interval_tick")
    private Integer scanIntervalTick;

    @SerializedName("predict_target_pos")
    private boolean predictTargetPos = false;

    @SerializedName("top_attack_height")
    private Float topAttackHeight;

    @SerializedName("guidance_angle_gate")
    private Map<RVP_Range<Float>, RVP_Range<Float>> guidanceAngleGate;

    @SerializedName("angle_gate_lock_out_tick")
    private int angleGateLockOutTick = 20;

    @SerializedName("enable_inertial_guidance")
    private boolean enableInertialGuidance = false;

    public RVP_EnumGuidanceType getGuidanceType() {
        return guidanceType == null ? RVP_EnumGuidanceType.NONE : guidanceType;
    }

    public int getActiveRadarActivationRange() {
        return Math.max(activeRadarActivationRange, 0);
    }

    public int getMaxLockAngle() {
        return Math.max(maxLockAngle, 0);
    }

    public float getMaxLockHalfAngle() {
        return getMaxLockAngle() * 0.5f;
    }

    public RVP_Range<Float> getGuidanceTargetDistanceRange() {
        return guidanceTargetDistanceRange;
    }

    public Integer getGuidanceStartTick() {
        return guidanceStartTick == null ? null : Math.max(guidanceStartTick, 0);
    }

    public Float getGuidanceStartDist() {
        return guidanceStartDist == null ? null : Math.max(guidanceStartDist, 0f);
    }

    public Float getGuidanceStartHorizontalDist() {
        return guidanceStartHorizontalDist == null ? null : Math.max(guidanceStartHorizontalDist, 0f);
    }

    public RVP_Range<Float> getGuidanceAltitudeRange() {
        return guidanceAltitudeRange;
    }

    public int getMaxGuidanceAngle() {
        return Math.max(maxGuidanceAngle, 0);
    }

    public Integer getScanIntervalTick() {
        return scanIntervalTick == null ? null : Math.max(scanIntervalTick, 1);
    }

    public boolean isPredictTargetPos() {
        return predictTargetPos;
    }

    public Float getTopAttackHeight() {
        return topAttackHeight;
    }

    public Map<RVP_Range<Float>, RVP_Range<Float>> getGuidanceAngleGate() {
        return guidanceAngleGate;
    }

    public int getAngleGateLockOutTick() {
        return Math.max(angleGateLockOutTick, 0);
    }

    public boolean isEnableInertialGuidance() {
        return enableInertialGuidance;
    }
}
