package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.guidance.RVP_EnumCompositeMode;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumPhaseResolvePolicy;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * 分段/复合制导配置。JSON 键 {@code guidance_data}。
 *
 * <p>制导阶段列表 {@code stages}：每阶段见 {@link RVP_GuidanceStageData}。
 * 多阶段激活窗口重叠时进入复合制导（见 {@link org.ywzj.rvp.guidance.RVP_GuidanceCompositeCompatibility}）。</p>
 */
public class RVP_GuidanceData {

    /** 多阶段重叠且制导类型不兼容时的消解策略。 */
    @SerializedName("phase_resolve_policy")
    private RVP_EnumPhaseResolvePolicy phaseResolvePolicy = RVP_EnumPhaseResolvePolicy.HIGHEST_SPECIFICITY;

    @SerializedName(value = "stages", alternate = {"phases"})
    private List<RVP_GuidanceStageData> stages = Collections.emptyList();

    /** 人在回路弹载视角；与 MCLOS/SACLOS 等制导源组合使用。 */
    @SerializedName("human_in_the_loop")
    private RVP_HumanInTheLoopData humanInTheLoop;

    public RVP_EnumPhaseResolvePolicy getPhaseResolvePolicy() {
        return phaseResolvePolicy == null ? RVP_EnumPhaseResolvePolicy.HIGHEST_SPECIFICITY : phaseResolvePolicy;
    }

    public List<RVP_GuidanceStageData> getStages() {
        return stages == null ? Collections.emptyList() : stages;
    }

    public RVP_HumanInTheLoopData getHumanInTheLoop() {
        return humanInTheLoop != null ? humanInTheLoop : RVP_HumanInTheLoopData.DISABLED;
    }

    public boolean isHumanInTheLoopEnabled() {
        return humanInTheLoop != null && humanInTheLoop.isEnabled();
    }

    public boolean hasSourceType(RVP_EnumGuidanceType type) {
        if (type == null) {
            return false;
        }
        return getStages().stream()
                .flatMap(stage -> stage.getSources().stream())
                .anyMatch(source -> source.getType() == type);
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
