package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 弹药/武器 display：按 bedrock_backend=rvp 重建烘焙模型，并让 mesh 无视阈值进入共享通道。 */
public class RVP_WeaponDisplay extends RVP_BaseDisplay {

    public RVP_WeaponDisplay(RVP_BaseDisplayPojo pojo) {
        super(pojo);
        if (getBedrockBackend() == RVP_BedrockBackend.RVP) {
            rebuildWeaponBackend(pojo);
        }
    }

    private void rebuildWeaponBackend(RVP_BaseDisplayPojo pojo) {
        var modelPojo = ClientAssetsManager.INSTANCE.getModel(pojo.model);
        this.modelPath = pojo.model;
        if (pojo.specialBoneEffects != null && !pojo.specialBoneEffects.isEmpty()) {
            this.specialBoneEffects = pojo.specialBoneEffects;
            modelPojo.ifPresent(bedrockModelPOJO -> this.model = RVP_VehicleModelFactory.createWeaponModel(
                    bedrockModelPOJO, pojo.specialBoneEffects, getBedrockBackend()));
        } else {
            modelPojo.ifPresent(bedrockModelPOJO -> this.model = RVP_VehicleModelFactory.createWeaponModel(
                    bedrockModelPOJO, List.of(), getBedrockBackend()));
        }

        if (pojo.animations != null) {
            var animationPojo = ClientAssetsManager.INSTANCE.getAnimation(pojo.animations);
            var animationIndexProvider = getAnimationIndexProvider();
            var loadedAnimations = animationPojo
                    .map(animationPOJO -> animationIndexProvider == null
                            ? List.<BedrockAnimation>of()
                            : BedrockAnimation.createAnimation(animationPOJO, animationIndexProvider))
                    .orElse(List.of());
            var map = new HashMap<String, BedrockAnimation>();
            for (var anim : loadedAnimations) {
                map.put(anim.getName(), anim);
            }
            this.animations = map;
        } else {
            this.animations = Map.of();
        }
    }
}
