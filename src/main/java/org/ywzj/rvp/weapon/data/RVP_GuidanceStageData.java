package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 单个制导阶段。JSON 写在 {@link RVP_GuidanceData#getStages()} 数组元素内。
 *
 * <p>每阶段自带 {@link #steeringData}、{@link #seeker}、{@link #activation} 与 {@link #sources}。</p>
 */
public class RVP_GuidanceStageData {

    @SerializedName("name")
    private String name = "stage";

    @SerializedName("activation")
    private RVP_GuidanceActivationData activation = new RVP_GuidanceActivationData();

    @SerializedName("seeker")
    private RVP_GuidanceSeekerData seeker = new RVP_GuidanceSeekerData();

    @SerializedName("steering_data")
    private RVP_GuidanceSteeringData steeringData;

    @SerializedName("sources")
    private List<RVP_GuidanceData.Source> sources = Collections.emptyList();

    /** 复合重叠时本阶段参与合成的默认权重。 */
    @SerializedName("composite_weight")
    private Double compositeWeight = 1.0;

    public String getName() {
        return name == null ? "stage" : name;
    }

    public RVP_GuidanceActivationData getActivation() {
        return activation == null ? new RVP_GuidanceActivationData() : activation;
    }

    public RVP_GuidanceSeekerData getSeeker() {
        return seeker == null ? new RVP_GuidanceSeekerData() : seeker;
    }

    public RVP_GuidanceSteeringData getSteeringData() {
        return steeringData;
    }

    public List<RVP_GuidanceData.Source> getSources() {
        return sources == null ? Collections.emptyList() : sources;
    }

    public double getCompositeWeight() {
        return compositeWeight == null || compositeWeight <= 0 ? 1.0 : compositeWeight;
    }

    public RVP_EnumGuidanceType getPrimaryGuidanceType() {
        return getSources().stream()
                .max(Comparator.comparingInt(RVP_GuidanceData.Source::getPriority))
                .map(RVP_GuidanceData.Source::getType)
                .orElse(RVP_EnumGuidanceType.NONE);
    }
}
