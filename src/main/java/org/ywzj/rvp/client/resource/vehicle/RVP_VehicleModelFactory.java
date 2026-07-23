package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakerOptions;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RVP_VehicleModelFactory {

    private RVP_VehicleModelFactory() {}

    public static VehicleBedrockModel createVehicleModel(BedrockModelPOJO pojo,
                                                         List<SpecialBoneEffect> effects,
                                                         RVP_BedrockBackend backend) {
        BakerOptions bakerOptions = createStaticBakerOptions(pojo, effects, backend, Set.of());
        if (backend == RVP_BedrockBackend.RVP) {
            return new RVP_VehicleBedrockModel(pojo, effects, bakerOptions);
        }
        return new VehicleBedrockModel(pojo, effects, bakerOptions);
    }

    public static VehicleBedrockModel createVehicleModel(BedrockModelPOJO pojo,
                                                         List<SpecialBoneEffect> effects,
                                                         RVP_BedrockBackend backend,
                                                         Set<String> extraPreservedBones) {
        BakerOptions bakerOptions = createStaticBakerOptions(pojo, effects, backend, extraPreservedBones);
        if (backend == RVP_BedrockBackend.RVP) {
            return new RVP_VehicleBedrockModel(pojo, effects, bakerOptions);
        }
        return new VehicleBedrockModel(pojo, effects, bakerOptions);
    }

    public static VehicleBedrockModel createEffectlessModel(BedrockModelPOJO pojo, RVP_BedrockBackend backend) {
        return createVehicleModel(pojo, List.of(), backend);
    }

    private static BakerOptions createStaticBakerOptions(BedrockModelPOJO pojo,
                                                         List<SpecialBoneEffect> effects,
                                                         RVP_BedrockBackend backend,
                                                         Set<String> extraPreservedBones) {
        Set<String> preservedBones = new LinkedHashSet<>();
        if (backend == RVP_BedrockBackend.RVP && pojo != null) {
            preservedBones.addAll(new BedrockModel(pojo).getBoneMap().keySet());
        }
        if (extraPreservedBones != null) {
            for (String bone : extraPreservedBones) {
                if (bone != null && !bone.isBlank()) {
                    preservedBones.add(bone);
                }
            }
        }
        if (effects != null) {
            for (SpecialBoneEffect effect : effects) {
                if (effect != null && effect.isValid() && effect.bone != null && !effect.bone.isBlank()) {
                    preservedBones.add(effect.bone);
                }
            }
        }
        Set<String> animatedBones = backend == RVP_BedrockBackend.RVP ? preservedBones : Set.of();
        return new BakerOptions(animatedBones, preservedBones, Set.of(), true, false, true);
    }
}
