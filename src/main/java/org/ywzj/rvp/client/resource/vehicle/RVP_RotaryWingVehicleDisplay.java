package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import org.ywzj.rvp.client.render.animation.runner.RVP_SwitchableRunnerFactory;
import org.ywzj.rvp.util.RadarUnitSwitchableAdapter;
import org.ywzj.vehicle.api.animation.IAnimationInstance;
import org.ywzj.vehicle.client.render.animation.context.RotaryWingVehicleContext;
import org.ywzj.vehicle.client.render.animation.controller.AnimationController;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplayPojo;
import org.ywzj.vehicle.client.resource.vehicle.RotaryWingVehicleDisplay;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RVP_RotaryWingVehicleDisplay extends RotaryWingVehicleDisplay {

    protected final RVP_BedrockBackend bedrockBackend;

    public RVP_RotaryWingVehicleDisplay(RVP_BaseDisplayPojo pojo) {
        super(pojo);
        this.bedrockBackend = RVP_BedrockBackend.fromString(pojo.bedrockBackend);
        if (bedrockBackend == RVP_BedrockBackend.RVP) {
            rebuildDisplayBackend(pojo);
        }
    }

    protected void rebuildDisplayBackend(BaseDisplayPojo pojo) {
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

    @Override
    public IAnimationInstance<RotaryWingVehicleContext> createAnimationInstance(RotaryWingVehicle entity) {
        IAnimationInstance<RotaryWingVehicleContext> result = super.createAnimationInstance(entity);
        if (result == null) {
            return null;
        }

        RotaryWingVehicleContext context = result.getContext();
        AnimationController<RotaryWingVehicleContext> controller = getAnimationController();
        if (controller == null) {
            return result;
        }

        for (var entry : controller.getSwitchableAnimations().entrySet()) {
            String key = entry.getKey();
            if (context.getSwitchableRunner(key) != null) {
                continue;
            }
            String partId = entry.getValue().getPartId();
            String animName = entry.getValue().getAnimation();
            boolean invert = entry.getValue().isInvert();
            BedrockAnimation anim = context.getAnimation(animName);
            if (anim == null) {
                continue;
            }
            entity.getPartUnit(partId).ifPresent(part -> {
                if (part instanceof RadarUnit radarUnit) {
                    context.addSwitchableRunner(key, RVP_SwitchableRunnerFactory.create(
                            new RadarUnitSwitchableAdapter(radarUnit), anim, invert));
                }
            });
        }
        return result;
    }

    public RVP_BedrockBackend getBedrockBackend() {
        return bedrockBackend;
    }
}
