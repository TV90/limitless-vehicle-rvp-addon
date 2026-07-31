package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import org.ywzj.rvp.client.render.animation.runner.RVP_SwitchableRunnerFactory;
import org.ywzj.rvp.util.RadarUnitSwitchableAdapter;
import org.ywzj.vehicle.api.animation.IAnimationInstance;
import org.ywzj.vehicle.client.render.animation.context.TrackedVehicleContext;
import org.ywzj.vehicle.client.render.animation.controller.AnimationController;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplayPojo;
import org.ywzj.vehicle.client.resource.vehicle.TrackConfig;
import org.ywzj.vehicle.client.resource.vehicle.TrackedVehicleDisplay;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RVP_TrackedVehicleDisplay extends TrackedVehicleDisplay {

    protected final RVP_BedrockBackend bedrockBackend;

    protected final List<String> noCullBones;

    private final TrackConfig trackConfig;
    private BedrockAnimation leftTrackAnimation;
    private BedrockAnimation rightTrackAnimation;

    public RVP_TrackedVehicleDisplay(RVP_TrackedVehicleDisplayPojo pojo) {
        super(pojo);
        this.bedrockBackend = RVP_BedrockBackend.fromString(pojo.bedrockBackend);
        this.noCullBones = pojo.noCullBones == null ? List.of() : List.copyOf(pojo.noCullBones);
        if (bedrockBackend == RVP_BedrockBackend.RVP) {
            rebuildDisplayBackend(pojo);
        }
        this.trackConfig = pojo.trackConfig;
        if (trackConfig != null && trackConfig.isValid()) {
            leftTrackAnimation = animations.get(trackConfig.leftTrack);
            rightTrackAnimation = animations.get(trackConfig.rightTrack);
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
    public IAnimationInstance<TrackedVehicleContext> createAnimationInstance(TrackedVehicle entity) {
        IAnimationInstance<TrackedVehicleContext> result = super.createAnimationInstance(entity);
        if (result == null) {
            return null;
        }

        TrackedVehicleContext context = result.getContext();
        AnimationController<TrackedVehicleContext> controller = getAnimationController();
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

    @Nullable
    public TrackConfig getTrackConfig() {
        return trackConfig;
    }

    @Nullable
    public BedrockAnimation getLeftTrackAnimation() {
        return leftTrackAnimation;
    }

    @Nullable
    public BedrockAnimation getRightTrackAnimation() {
        return rightTrackAnimation;
    }

    public boolean hasTrackConfig() {
        return trackConfig != null && trackConfig.isValid();
    }

    public RVP_BedrockBackend getBedrockBackend() {
        return bedrockBackend;
    }

    public List<String> getNoCullBones() {
        return noCullBones;
    }
}
