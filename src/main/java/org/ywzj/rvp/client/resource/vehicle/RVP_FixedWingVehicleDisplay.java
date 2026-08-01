package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import net.minecraft.resources.ResourceLocation;
import org.ywzj.rvp.client.render.animation.runner.RVP_SwitchableRunnerFactory;
import org.ywzj.rvp.util.RadarUnitSwitchableAdapter;
import org.ywzj.vehicle.api.animation.IAnimationInstance;
import org.ywzj.vehicle.client.render.animation.context.FixedWingVehicleContext;
import org.ywzj.vehicle.client.render.animation.controller.AnimationController;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplayPojo;
import org.ywzj.vehicle.client.resource.vehicle.FixedWingVehicleDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RVP_FixedWingVehicleDisplay extends FixedWingVehicleDisplay {

    protected final RVP_BedrockBackend bedrockBackend;

    protected final List<String> noCullBones;

    protected final List<RVP_StateHiddenBone> stateHiddenBones;

    protected final List<RVP_DistanceHiddenBone> distanceHiddenBones;

    protected final List<RVP_LodModel> lodModels;

    private final VehicleBedrockModel afterburnerModel;
    private final ResourceLocation afterburnerTexture;
    private final Map<String, BedrockAnimation> afterburnerAnimations;

    public RVP_FixedWingVehicleDisplay(RVP_FixedWingVehicleDisplayPojo pojo) {
        super(pojo);
        this.bedrockBackend = RVP_BedrockBackend.fromString(pojo.bedrockBackend);
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
        if (bedrockBackend == RVP_BedrockBackend.RVP) {
            rebuildDisplayBackend(pojo);
        }

        if (pojo.afterburnerModel != null) {
            var modelPojo = ClientAssetsManager.INSTANCE.getModel(pojo.afterburnerModel);
            this.afterburnerModel = modelPojo
                    .map(bedrockModelPOJO -> RVP_VehicleModelFactory.createEffectlessModel(bedrockModelPOJO, bedrockBackend))
                    .orElse(null);
            if (this.afterburnerModel != null) {
                this.afterburnerModel.getBoneMap().values().forEach(bone -> bone.illuminated = true);
            }
        } else {
            this.afterburnerModel = null;
        }

        this.afterburnerTexture = pojo.afterburnerTexture;

        if (pojo.afterburnerAnimations != null && this.afterburnerModel != null) {
            var animPojo = ClientAssetsManager.INSTANCE.getAnimation(pojo.afterburnerAnimations);
            var anims = animPojo
                    .map(animationPOJO -> BedrockAnimation.createAnimation(animationPOJO, this.afterburnerModel))
                    .orElse(java.util.List.of());
            var map = new HashMap<String, BedrockAnimation>();
            for (var anim : anims) {
                map.put(anim.getName(), anim);
            }
            this.afterburnerAnimations = map;
        } else {
            this.afterburnerAnimations = Map.of();
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
    public IAnimationInstance<FixedWingVehicleContext> createAnimationInstance(FixedWingVehicle entity) {
        IAnimationInstance<FixedWingVehicleContext> result = super.createAnimationInstance(entity);
        if (result == null) {
            return null;
        }

        FixedWingVehicleContext context = result.getContext();
        AnimationController<FixedWingVehicleContext> controller = getAnimationController();
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

    public VehicleBedrockModel getAfterburnerModel() {
        return afterburnerModel;
    }

    public ResourceLocation getAfterburnerTexture() {
        return afterburnerTexture;
    }

    public Map<String, BedrockAnimation> getAfterburnerAnimations() {
        return afterburnerAnimations;
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
