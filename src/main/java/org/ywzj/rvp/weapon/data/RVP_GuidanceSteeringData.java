package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 制导转向通用参数（刚性段、转向系数、预测等）。扩展包 JSON 写在
 * {@code guidance_data.steering_data}，由 {@link RVP_GuidanceData} 持有。
 */
public class RVP_GuidanceSteeringData {

    /**
     * 发射后刚性飞行 tick 数：此期间不应用制导转向（MCH {@code RigidityTime}）。
     */
    @SerializedName("rigidity_time")
    private int rigidityTime = 0;

    /**
     * 每 tick 速度方向向目标插值系数，0–1；越大转向越猛（MCH {@code TurningFactor}）。
     */
    @SerializedName("turning_factor")
    private double turningFactor = 0.5;

    /**
     * 弹体相对速度矢量的最大偏转角（度），防止瞬时大角度机动。
     */
    @SerializedName("max_degree_of_missile")
    private float maxDegreeOfMissile = 180f;

    /** 是否根据目标速度预测拦截点（比例导引前置）。 */
    @SerializedName("predict_target_pos")
    private boolean predictTargetPos = true;

    /**
     * 末段纯追踪 tick 数：大于 0 时在寿命末段强制指向目标（MCH {@code TickEndHoming}）。
     */
    @SerializedName("tick_end_homing")
    private int tickEndHoming = 0;

    public int getRigidityTime() {
        return Math.max(rigidityTime, 0);
    }

    public double getTurningFactor() {
        return turningFactor;
    }

    public float getMaxDegreeOfMissile() {
        return maxDegreeOfMissile;
    }

    public boolean isPredictTargetPos() {
        return predictTargetPos;
    }

    public int getTickEndHoming() {
        return Math.max(tickEndHoming, 0);
    }
}
