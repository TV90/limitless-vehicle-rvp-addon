package org.ywzj.rvp.client.resource.vehicle;

import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;

import java.util.List;

public class RVP_BaseDisplay extends BaseDisplay {

    protected final RVP_BedrockBackend bedrockBackend;

    protected final List<String> noCullBones;

    protected final List<RVP_StateHiddenBone> stateHiddenBones;

    protected final List<RVP_DistanceHiddenBone> distanceHiddenBones;

    protected final List<RVP_LodModel> lodModels;

    public RVP_BaseDisplay(RVP_BaseDisplayPojo pojo) {
        this(pojo, RVP_BedrockBackend.fromString(pojo.bedrockBackend));
    }

    protected RVP_BaseDisplay(RVP_BaseDisplayPojo pojo, RVP_BedrockBackend backend) {
        super(pojo);
        this.bedrockBackend = backend == null ? RVP_BedrockBackend.VEHICLE : backend;
        this.noCullBones = pojo.noCullBones == null ? List.of() : List.copyOf(pojo.noCullBones);
        this.stateHiddenBones = pojo.stateHiddenBones == null ? List.of()
                : pojo.stateHiddenBones.stream()
                        .filter(p -> p != null && p.state != null && !p.state.isBlank()
                                && p.bones != null && !p.bones.isEmpty())
                        .map(RVP_StateHiddenBone.Pojo::toRule)
                        .toList();
        this.distanceHiddenBones = pojo.distanceHiddenBones == null ? List.of()
                : pojo.distanceHiddenBones.stream()
                        .filter(p -> p != null && p.bones != null && !p.bones.isEmpty())
                        .map(RVP_DistanceHiddenBone.Pojo::toRule)
                        .toList();
        this.lodModels = RVP_LodModel.parse(pojo.lodModels);
    }

    public RVP_BedrockBackend getBedrockBackend() {
        return bedrockBackend;
    }

    public List<String> getNoCullBones() {
        return noCullBones;
    }

    public List<RVP_StateHiddenBone> getStateHiddenBones() {
        return stateHiddenBones;
    }

    public List<RVP_DistanceHiddenBone> getDistanceHiddenBones() {
        return distanceHiddenBones;
    }

    public List<RVP_LodModel> getLodModels() {
        return lodModels;
    }
}
