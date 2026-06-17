package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 制导转向通用参数（刚性段、转向系数、预测等）。扩展包 JSON 写在
 * {@code guidance_data.stages[].steering_data}，由 {@link RVP_GuidanceStageData} 持有。
 */
public class RVP_GuidanceSteeringData {

    /**
     * 本阶段进入后的刚性飞行 tick 数：从阶段激活 tick 起算，此期间本阶段制导源不转向
     * （MCH {@code RigidityTime}）。
     */
    @SerializedName("rigidity_time")
    private Integer rigidityTime;

    /**
     * 每 tick 速度方向向目标插值系数，0–1；越大转向越猛（MCH {@code TurningFactor}）。
     */
    @SerializedName("turning_factor")
    private Double turningFactor;

    /**
     * 弹体相对速度矢量的最大偏转角（度），防止瞬时大角度机动。
     */
    @SerializedName("max_degree_of_missile")
    private Float maxDegreeOfMissile;

    /** 是否根据目标速度预测拦截点（比例导引前置）。 */
    @SerializedName("predict_target_pos")
    private Boolean predictTargetPos;

    /**
     * 末段纯追踪 tick 数：大于 0 时仅在寿命末 N tick 内执行制导（MCH {@code TickEndHoming}）。
     */
    @SerializedName("tick_end_homing")
    private Integer tickEndHoming;

    /**
     * 比例导引增益；0 或未写表示关闭，仅用 {@link #turningFactor} 插值。
     */
    @SerializedName("proportional_navigation_gain")
    private Double proportionalNavigationGain;

    /**
     * 最大横向过载（格/tick²）；与 {@link #maxDegreeOfMissile} 同时存在时取更严限制。
     */
    @SerializedName("max_lateral_accel")
    private Double maxLateralAccel;

    /**
     * 攻顶弹道俯冲角（度）。>0 时，本阶段制导向目标上方偏移，以指定角度俯冲攻击。
     * 典型值 30–60。0 或未写 = 不启用攻顶。
     */
    @SerializedName("terminal_dive_angle")
    private Double terminalDiveAngle;

    public int getRigidityTime() {
        return Math.max(rigidityTime != null ? rigidityTime : 0, 0);
    }

    public double getTurningFactor() {
        return turningFactor != null ? turningFactor : 0.5;
    }

    public float getMaxDegreeOfMissile() {
        return maxDegreeOfMissile != null ? maxDegreeOfMissile : 180f;
    }

    public boolean isPredictTargetPos() {
        return predictTargetPos == null || predictTargetPos;
    }

    public int getTickEndHoming() {
        return Math.max(tickEndHoming != null ? tickEndHoming : 0, 0);
    }

    public double getProportionalNavigationGain() {
        return proportionalNavigationGain != null ? Math.max(proportionalNavigationGain, 0.0) : 0.0;
    }

    public double getMaxLateralAccel() {
        return maxLateralAccel != null ? Math.max(maxLateralAccel, 0.0) : 0.0;
    }

    public double getTerminalDiveAngle() {
        return terminalDiveAngle != null ? Math.max(terminalDiveAngle, 0.0) : 0.0;
    }

    public RVP_GuidanceSteeringData copy() {
        RVP_GuidanceSteeringData copy = new RVP_GuidanceSteeringData();
        copy.rigidityTime = this.rigidityTime;
        copy.turningFactor = this.turningFactor;
        copy.maxDegreeOfMissile = this.maxDegreeOfMissile;
        copy.predictTargetPos = this.predictTargetPos;
        copy.tickEndHoming = this.tickEndHoming;
        copy.proportionalNavigationGain = this.proportionalNavigationGain;
        copy.maxLateralAccel = this.maxLateralAccel;
        copy.terminalDiveAngle = this.terminalDiveAngle;
        return copy;
    }

    public void applyOverride(RVP_GuidanceSteeringData override) {
        if (override == null) {
            return;
        }
        if (override.rigidityTime != null) {
            this.rigidityTime = override.rigidityTime;
        }
        if (override.turningFactor != null) {
            this.turningFactor = override.turningFactor;
        }
        if (override.maxDegreeOfMissile != null) {
            this.maxDegreeOfMissile = override.maxDegreeOfMissile;
        }
        if (override.predictTargetPos != null) {
            this.predictTargetPos = override.predictTargetPos;
        }
        if (override.tickEndHoming != null) {
            this.tickEndHoming = override.tickEndHoming;
        }
        if (override.proportionalNavigationGain != null) {
            this.proportionalNavigationGain = override.proportionalNavigationGain;
        }
        if (override.maxLateralAccel != null) {
            this.maxLateralAccel = override.maxLateralAccel;
        }
        if (override.terminalDiveAngle != null) {
            this.terminalDiveAngle = override.terminalDiveAngle;
        }
    }
}
