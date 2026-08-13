package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 干扰物干扰数据模型（RVP_InterferenceData，仅对 IR / AIR / SARH / ARH 导弹生效）。
 *
 * <p>字段说明见 {@code docs/plan/RVP武器数据模型/RVP 火炮导弹火箭武器数据模型文档.md}
 * 的 RVP_InterferenceData 表。JSON 键 {@code interference_data}（嵌套在 {@code guidance_data} 内）。</p>
 */
public final class RVP_InterferenceData {

    /**
     * 导引头（fov）视场内的极限干扰物数量，<b>超过</b>该数量后导弹脱锁并转向最近的干扰物。
     */
    @SerializedName("seeker_jam_limit")
    private int seekerJamLimit = 8;

    /**
     * 导引头跟踪目标时的 fov 倍率；实际检测 fov = {@code maxLockAngle * seekerFovShrinkFactor}（全角）。
     */
    @SerializedName("seeker_fov_shrink_factor")
    private float seekerFovShrinkFactor = 1.0F;

    /**
     * 导引头关闭时间（tick）；导弹失去制导（被干扰）后关闭 {@code seekerShutOffTime} tick，
     * 之后重启并主动搜寻复锁；null 表示不启用关闭（失锁后立即恢复搜索）。
     */
    @SerializedName("seeker_shut_off_time")
    private Integer seekerShutOffTime;

    public int getSeekerJamLimit() {
        return Math.max(1, seekerJamLimit);
    }

    public float getSeekerFovShrinkFactor() {
        return Float.isFinite(seekerFovShrinkFactor) ? Math.max(0.1F, seekerFovShrinkFactor) : 1.0F;
    }

    public Integer getSeekerShutOffTime() {
        return seekerShutOffTime == null ? null : Math.max(0, seekerShutOffTime);
    }
}
