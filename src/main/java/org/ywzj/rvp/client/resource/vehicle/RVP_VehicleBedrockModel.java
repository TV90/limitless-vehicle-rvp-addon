package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;

import java.util.List;

public class RVP_VehicleBedrockModel extends VehicleBedrockModel {

    public RVP_VehicleBedrockModel(BedrockModelPOJO pojo, List<SpecialBoneEffect> specialBoneEffects) {
        super(pojo, specialBoneEffects);
    }

    public RVP_VehicleBedrockModel(BedrockModelPOJO pojo,
                                   List<SpecialBoneEffect> specialBoneEffects,
                                   BakerOptions bakerOptions) {
        super(pojo, specialBoneEffects, bakerOptions);
    }
}
