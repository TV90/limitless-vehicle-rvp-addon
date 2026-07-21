package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RVP_BaseDisplay extends BaseDisplay {

    protected final RVP_BedrockBackend bedrockBackend;

    public RVP_BaseDisplay(RVP_BaseDisplayPojo pojo) {
        this(pojo, RVP_BedrockBackend.fromString(pojo.bedrockBackend));
    }

    protected RVP_BaseDisplay(RVP_BaseDisplayPojo pojo, RVP_BedrockBackend backend) {
        super();
        this.bedrockBackend = backend;
        initializeBaseDisplay(pojo);
    }

    protected void initializeBaseDisplay(RVP_BaseDisplayPojo pojo) {
        var modelPojo = ClientAssetsManager.INSTANCE.getModel(pojo.model);
        this.modelPath = pojo.model;

        if (pojo.specialBoneEffects != null && !pojo.specialBoneEffects.isEmpty()) {
            this.specialBoneEffects = pojo.specialBoneEffects;
            modelPojo.ifPresent(bedrockModelPOJO ->
                    this.model = RVP_VehicleModelFactory.createVehicleModel(
                            bedrockModelPOJO,
                            pojo.specialBoneEffects,
                            bedrockBackend
                    ));
        } else {
            modelPojo.ifPresent(bedrockModelPOJO ->
                    this.model = RVP_VehicleModelFactory.createEffectlessModel(bedrockModelPOJO, bedrockBackend));
        }

        this.texture = pojo.texture;
        this.slotTexture = pojo.slotTexture;
        this.animationControllerPath = pojo.animationController;

        if (pojo.animations != null) {
            var animationPojo = ClientAssetsManager.INSTANCE.getAnimation(pojo.animations);
            var animations = animationPojo
                    .map(animationPOJO -> BedrockAnimation.createAnimation(animationPOJO, model))
                    .orElse(List.of());
            var map = new HashMap<String, BedrockAnimation>();
            for (var anim : animations) {
                map.put(anim.getName(), anim);
            }
            this.animations = map;
        } else {
            this.animations = Map.of();
        }

        if (pojo.sounds != null) {
            pojo.sounds.forEach((soundName, soundResourceLocation) ->
                    soundEvents.put(soundName, SoundEvent.createVariableRangeEvent(soundResourceLocation)));
        }

        this.description = pojo.description;
        this.tabIndex = pojo.tabIndex;
    }

    public RVP_BedrockBackend getBedrockBackend() {
        return bedrockBackend;
    }
}
