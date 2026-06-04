package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 制导转向通用参数（比例导引、刚性段、预测等），由 {@link RVP_GuidanceData} 持有。
 */
public class RVP_GuidanceSteeringData {

    @SerializedName("rigidity_time")
    private int rigidityTime = 0;

    @SerializedName("turning_factor")
    private double turningFactor = 0.5;

    @SerializedName("max_degree_of_missile")
    private float maxDegreeOfMissile = 180f;

    @SerializedName("predict_target_pos")
    private boolean predictTargetPos = true;

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
