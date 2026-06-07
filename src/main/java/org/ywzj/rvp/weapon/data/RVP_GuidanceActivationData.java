package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 制导阶段激活条件。各字段为 AND 关系；未写或 0 表示该维度不限制。
 *
 * <p>运行时由 {@link org.ywzj.rvp.guidance.activation.RVP_GuidanceActivationEvaluator} 通过策略链求值。</p>
 */
public class RVP_GuidanceActivationData {

    @SerializedName("start_tick")
    private Integer startTick;

    @SerializedName("end_tick")
    private Integer endTick;

    /** 与制导目标点/记忆点的距离下限（格）。 */
    @SerializedName("min_target_distance")
    private Float minTargetDistance;

    /** 与制导目标点/记忆点的距离上限（格）。 */
    @SerializedName("max_target_distance")
    private Float maxTargetDistance;

    /** 与锁定实体中心的距离下限（格）；无实体时不满足（除非未配置）。 */
    @SerializedName("min_entity_distance")
    private Float minEntityDistance;

    /** 与锁定实体中心的距离上限（格）。 */
    @SerializedName("max_entity_distance")
    private Float maxEntityDistance;

    /** 离地高度下限（格）。 */
    @SerializedName("min_altitude_agl")
    private Float minAltitudeAgl;

    /** 离地高度上限（格）。 */
    @SerializedName("max_altitude_agl")
    private Float maxAltitudeAgl;

    @SerializedName("require_target")
    private Boolean requireTarget;

    @SerializedName("require_entity_target")
    private Boolean requireEntityTarget;

    @SerializedName("require_illumination")
    private Boolean requireIllumination;

    /** 进入后粘性保持（见 {@link RVP_GuidanceData#getPhaseResolvePolicy()}）。 */
    @SerializedName("enter_once")
    private Boolean enterOnce;

    public int getStartTick() {
        return Math.max(startTick != null ? startTick : 0, 0);
    }

    public int getEndTick() {
        return endTick != null ? endTick : -1;
    }

    public float getMinTargetDistance() {
        return Math.max(minTargetDistance != null ? minTargetDistance : 0f, 0f);
    }

    public float getMaxTargetDistance() {
        return Math.max(maxTargetDistance != null ? maxTargetDistance : 0f, 0f);
    }

    public float getMinEntityDistance() {
        return Math.max(minEntityDistance != null ? minEntityDistance : 0f, 0f);
    }

    public float getMaxEntityDistance() {
        return Math.max(maxEntityDistance != null ? maxEntityDistance : 0f, 0f);
    }

    public float getMinAltitudeAgl() {
        return Math.max(minAltitudeAgl != null ? minAltitudeAgl : 0f, 0f);
    }

    public float getMaxAltitudeAgl() {
        return Math.max(maxAltitudeAgl != null ? maxAltitudeAgl : 0f, 0f);
    }

    public boolean isRequireTarget() {
        return requireTarget != null && requireTarget;
    }

    public boolean isRequireEntityTarget() {
        return requireEntityTarget != null && requireEntityTarget;
    }

    public boolean isRequireIllumination() {
        return requireIllumination != null && requireIllumination;
    }

    public boolean isEnterOnce() {
        return enterOnce != null && enterOnce;
    }

    public boolean hasTargetDistanceCondition() {
        return getMinTargetDistance() > 0f || getMaxTargetDistance() > 0f;
    }

    public boolean hasEntityDistanceCondition() {
        return getMinEntityDistance() > 0f || getMaxEntityDistance() > 0f;
    }

    public boolean hasAltitudeCondition() {
        return getMinAltitudeAgl() > 0f || getMaxAltitudeAgl() > 0f;
    }

    public boolean hasTickCondition() {
        return getStartTick() > 0 || getEndTick() >= 0;
    }

    public int specificityScore() {
        int score = 0;
        if (hasTickCondition()) {
            score += 1;
        }
        if (hasTargetDistanceCondition()) {
            score += 2;
        }
        if (hasEntityDistanceCondition()) {
            score += 2;
        }
        if (hasAltitudeCondition()) {
            score += 1;
        }
        if (isRequireTarget() || isRequireEntityTarget() || isRequireIllumination()) {
            score += 1;
        }
        return score;
    }
}
