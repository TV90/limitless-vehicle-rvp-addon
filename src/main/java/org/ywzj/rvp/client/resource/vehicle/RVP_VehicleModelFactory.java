package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;

import java.util.List;

public final class RVP_VehicleModelFactory {

    private RVP_VehicleModelFactory() {}

    public static VehicleBedrockModel createVehicleModel(BedrockModelPOJO pojo,
                                                         List<SpecialBoneEffect> effects,
                                                         RVP_BedrockBackend backend) {
        if (backend == RVP_BedrockBackend.RVP) {
            return new RVP_VehicleBedrockModel(pojo, effects);
        }
        return new VehicleBedrockModel(pojo, effects);
    }

    public static VehicleBedrockModel createEffectlessModel(BedrockModelPOJO pojo, RVP_BedrockBackend backend) {
        return createVehicleModel(pojo, List.of(), backend);
    }
}
