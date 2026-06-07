package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.RVP_HitlSteeringMath;
import org.ywzj.rvp.guidance.RVP_TvVideoModeMask;

import java.util.List;

/**
 * 人在回路（弹载视角）配置。JSON 键 {@code guidance_data.human_in_the_loop}。
 *
 * <p>任意制导类型均可开启；与 {@link RVP_EnumGuidanceType#MCLOS} 组合为鼠标驾控，
 * 与 {@link RVP_EnumGuidanceType#SACLOS} 组合为屏幕点选制导。</p>
 */
public class RVP_HumanInTheLoopData {

    public static final RVP_HumanInTheLoopData DISABLED = new RVP_HumanInTheLoopData();

    @SerializedName("enabled")
    private Boolean enabled;

    @SerializedName("control_mode")
    private RVP_EnumHitlControlMode controlMode;

    /** 玩家可保持弹载视角的最大距离（格）。 */
    @SerializedName("control_range")
    private Float controlRange;

    /** 人在回路会话超时（tick）。 */
    @SerializedName("timeout_tick")
    private Integer timeoutTick;

    /**
     * HITL MOUSE：弹体/导引头每 tick 向鼠标指令航向转动的最大角度（度）。
     * 未写时默认 {@link org.ywzj.rvp.guidance.RVP_HitlSteeringMath#DEFAULT_MAX_TURN_DEG_PER_TICK}。
     */
    @SerializedName("max_turn_deg_per_tick")
    private Float maxTurnDegPerTick;

    /**
     * HITL DESIGNATE（电视 + SACLOS）：相对弹体轴向的鼠标视角偏移上限（度，单侧）。
     * 未写时取 SACLOS 阶段 {@code seeker.fov} 的一半。
     */
    @SerializedName("max_look_offset_deg")
    private Float maxLookOffsetDeg;

    /**
     * 可用画面模式 {@code COLOR}、{@code BW}/{@code MONO}、{@code THERMAL}/{@code IR}；
     * 空表示全部可用。
     */
    @SerializedName("video_modes")
    private List<String> videoModes;

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public RVP_EnumHitlControlMode getControlMode() {
        return controlMode;
    }

    public RVP_EnumHitlControlMode resolveControlMode(RVP_GuidanceData guidance) {
        if (controlMode != null) {
            return controlMode;
        }
        if (guidance.hasSourceType(RVP_EnumGuidanceType.MCLOS)) {
            return RVP_EnumHitlControlMode.MOUSE;
        }
        if (guidance.hasSourceType(RVP_EnumGuidanceType.SACLOS)) {
            return RVP_EnumHitlControlMode.DESIGNATE;
        }
        return RVP_EnumHitlControlMode.VIEW;
    }

    public float controlRange(float defaultValue) {
        return controlRange != null ? Math.max(controlRange, 1f) : defaultValue;
    }

    public int timeoutTick(int defaultValue) {
        return timeoutTick != null ? Math.max(timeoutTick, 1) : defaultValue;
    }

    public float maxTurnDegPerTick() {
        return maxTurnDegPerTick != null
                ? Math.max(maxTurnDegPerTick, 0.05f)
                : RVP_HitlSteeringMath.DEFAULT_MAX_TURN_DEG_PER_TICK;
    }

    public float maxLookOffsetDeg(float seekerHalfFovDeg) {
        if (maxLookOffsetDeg != null) {
            return Math.max(maxLookOffsetDeg, 1f);
        }
        return Math.max(seekerHalfFovDeg, 1f);
    }

    public int videoModeMask() {
        if (videoModes == null || videoModes.isEmpty()) {
            return RVP_TvVideoModeMask.ALL;
        }
        int mask = 0;
        for (String mode : videoModes) {
            mask |= RVP_TvVideoModeMask.parse(mode);
        }
        return mask != 0 ? mask : RVP_TvVideoModeMask.ALL;
    }

    public int defaultVideoMode() {
        if (videoModes != null) {
            for (String mode : videoModes) {
                int parsed = RVP_TvVideoModeMask.parse(mode);
                if (parsed != 0) {
                    return parsed;
                }
            }
        }
        return RVP_TvVideoModeMask.COLOR;
    }
}
