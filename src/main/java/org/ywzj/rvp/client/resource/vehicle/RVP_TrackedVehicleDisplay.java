package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import org.ywzj.vehicle.api.scripts.ScriptContextFactory;
import org.ywzj.vehicle.client.resource.ScriptManager;
import org.ywzj.rvp.client.render.animation.runner.RVP_RadarLoopSwitchableRunner;
import org.ywzj.rvp.client.render.animation.runner.RVP_SwitchableRunnerFactory;
import org.ywzj.rvp.util.RadarUnitSwitchableAdapter;
import org.ywzj.vehicle.client.render.animation.VehicleAnimationInstance;
import org.ywzj.vehicle.client.render.animation.context.TrackedVehicleContext;
import org.ywzj.vehicle.client.render.animation.controller.AnimationController;
import org.ywzj.vehicle.client.resource.vehicle.TrackConfig;
import org.ywzj.vehicle.client.resource.vehicle.TrackedVehicleDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

public class RVP_TrackedVehicleDisplay extends TrackedVehicleDisplay implements RVP_DeferredDisplay {

    protected final RVP_BedrockBackend bedrockBackend;
    /** 主体模型和动画控制器的按需加载入口。 */
    private final RVP_DeferredVehicleModel deferredModel;

    protected final List<String> noCullBones;

    protected final List<RVP_StateHiddenBone> stateHiddenBones;

    protected final List<RVP_DistanceHiddenBone> distanceHiddenBones;

    protected final List<RVP_LodModel> lodModels;

    private final TrackConfig trackConfig;
    // 由显示配置指定哪些扫描动画需要等待收拢轨道。
    private final Map<String, RVP_TrackedVehicleDisplayPojo.RadarScanGate> radarScanGates;
    private BedrockAnimation leftTrackAnimation;
    private BedrockAnimation rightTrackAnimation;

    public RVP_TrackedVehicleDisplay(RVP_TrackedVehicleDisplayPojo pojo) {
        super(RVP_DeferredVehicleModel.trackedBase(pojo, pojo.bedrockBackend));
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
        this.deferredModel = bedrockBackend == RVP_BedrockBackend.RVP
                ? new RVP_DeferredVehicleModel(this, pojo, this::installModel) : null;
        if (deferredModel != null) {
            this.modelPath = pojo.model;
        }
        this.trackConfig = pojo.trackConfig;
        this.radarScanGates = pojo.radarScanGates == null ? Map.of() : pojo.radarScanGates;
        if (deferredModel == null) {
            bindTrackAnimations();
        }
    }

    @Override
    public VehicleBedrockModel getModel() {
        return deferredModel == null ? super.getModel() : deferredModel.get();
    }

    @Override
    public VehicleBedrockModel getLoadedModel() {
        return super.getModel();
    }

    @Override
    public BedrockModelPOJO getModelPojo() {
        return deferredModel == null ? super.getModelPojo() : deferredModel.getModelPojo();
    }

    @Override
    public Map<String, BedrockAnimation> getAnimations() {
        getModel();
        return super.getAnimations();
    }

    @Override
    public void initializeAnimationController(ScriptManager scripts, ScriptContextFactory factory) {
        if (deferredModel == null) {
            super.initializeAnimationController(scripts, factory);
        } else {
            deferredModel.initializeController(() -> super.initializeAnimationController(scripts, factory));
        }
    }

    private void installModel(VehicleBedrockModel loadedModel, Map<String, BedrockAnimation> loadedAnimations) {
        this.model = loadedModel;
        this.animations = loadedAnimations;
        bindTrackAnimations();
    }

    private void bindTrackAnimations() {
        if (trackConfig != null && trackConfig.isValid()) {
            leftTrackAnimation = animations.get(trackConfig.leftTrack);
            rightTrackAnimation = animations.get(trackConfig.rightTrack);
        }
    }

    @Override
    public VehicleAnimationInstance<TrackedVehicleContext> createAnimationInstance(TrackedVehicle entity) {
        // 本体创建动画实例前先就绪该车的模型和控制器。
        getModel();
        VehicleAnimationInstance<TrackedVehicleContext> result = super.createAnimationInstance(entity);
        if (result == null) {
            return null;
        }

        TrackedVehicleContext context = result.getContext();
        AnimationController<TrackedVehicleContext> controller = animationController;
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
                    RadarUnitSwitchableAdapter adapter = new RadarUnitSwitchableAdapter(radarUnit);
                    var gate = radarScanGates.get(key);
                    if (!invert && gate != null && gate.switchable() != null && !key.equals(gate.switchable())
                            && Float.isFinite(gate.maxProgressSeconds()) && gate.maxProgressSeconds() >= 0) {
                        // 按引用读取同车的基座轨道，收拢和扫描的装配顺序可以独立。
                        context.addSwitchableRunner(key, new RVP_RadarLoopSwitchableRunner(adapter, anim, false,
                                () -> context.getSwitchableRunner(gate.switchable())
                                        instanceof RVP_RadarLoopSwitchableRunner fold
                                        && fold.getProgressInSecond() <= gate.maxProgressSeconds(),
                                gate.sweepReturnSeconds()));
                    } else {
                        // 让未配置门槛的轨道使用原有播放器。
                        context.addSwitchableRunner(key, RVP_SwitchableRunnerFactory.create(adapter, anim, invert));
                    }
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
        getModel();
        return leftTrackAnimation;
    }

    @Nullable
    public BedrockAnimation getRightTrackAnimation() {
        getModel();
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
