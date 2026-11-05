package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import java.util.Map;

public class RVP_TerminalGuidanceData {

    @SerializedName("guidance_type")
    private RVP_EnumGuidanceType guidanceType = RVP_EnumGuidanceType.NONE;

    /**
     * 末段导引头未捕获时保留主制导，默认 true，无单位；仅 IR（含 AIR）、ARH、ARM 末段生效。
     * <p>搜索时继续执行主段完整参数及其干扰规则；真实捕获后单向交接，后续脱锁不退回主段。
     * 设为 false 时保留进入末段即接管的行为及原有开机提示。</p>
     */
    @SerializedName("retain_main_guidance_until_capture")
    private boolean retainMainGuidanceUntilCapture = true;

    /**
     * 末段首次截获的搜索距离区间，单位格，默认 null，沿用 guidance_target_distance_range。
     * <p>只约束捕获/重捕获与候选扫描，不改变持续跟踪距离或 max_guidance_angle；
     * 显式配置时在已加载目标中按本区间搜索，不再受旧重锁扫描256格封顶限制。</p>
     */
    @SerializedName("seeker_search_distance_range")
    private RVP_Range<Float> seekerSearchDistanceRange;

    public boolean isRetainMainGuidanceUntilCapture() {
        return retainMainGuidanceUntilCapture;
    }

    public RVP_Range<Float> getSeekerSearchDistanceRange() {
        return seekerSearchDistanceRange;
    }

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

    /**
     * 末段自动实体拦截开关，无单位，默认 false；有效 IR/AIR/ARH/SARH 导弹实体观测走 PPN。
     * 增益和起始总飞行 Tick 继承主段；没有有效实体、仍在搜索或被门控暂停时不进入 PPN。
     * 其他末段路径保持原有行为，不把坐标或人工指令自动升级为实体寻的。
     */
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

    /**
     * 返回末段实际使用的制导类型；AIR 与 IR 统一执行红外末制导。
     *
     * <p>能力查询、导引头管理和制导分派共用此出口，避免末段 AIR 继续进入主动雷达共用链。
     * 主制导 AIR 由 RVP_GuidanceData 单独解析，不受本类路由影响。</p>
     */
    public RVP_EnumGuidanceType getGuidanceType() {
        if (guidanceType == RVP_EnumGuidanceType.AIR) {
            return RVP_EnumGuidanceType.IR;
        }
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
