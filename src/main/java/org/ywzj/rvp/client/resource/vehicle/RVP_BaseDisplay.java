package org.ywzj.rvp.client.resource.vehicle;

import net.minecraft.sounds.SoundEvent;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;

import java.util.List;

public class RVP_BaseDisplay extends BaseDisplay {

    private final org.ywzj.rvp.config.RVP_DisplayFireSound fireSound;

    public org.ywzj.rvp.config.RVP_DisplayFireSound getFireSoundSettings() { return fireSound; }

    protected final RVP_BedrockBackend bedrockBackend;

    protected final List<String> noCullBones;

    protected final List<RVP_StateHiddenBone> stateHiddenBones;

    protected final List<RVP_DistanceHiddenBone> distanceHiddenBones;

    protected final List<RVP_LodModel> lodModels;

    public RVP_BaseDisplay(RVP_BaseDisplayPojo pojo) {
        this(pojo, RVP_BedrockBackend.fromString(pojo.bedrockBackend));
    }

    protected RVP_BaseDisplay(RVP_BaseDisplayPojo pojo, RVP_BedrockBackend backend) {
        // 走 BaseDisplay() 无参构造：BaseDisplay(pojo) 会先按本体参数完整烘焙一份模型，
        // 而 RVP 武器 display 随后必然用 RVP_VehicleModelFactory 再烘一份并覆盖，
        // 第一份模型从未被渲染，是启动/F3+T 重载峰值里纯浪费的一次 parse+bake。
        // 这里只补齐武器 display 实际使用的基类字段（模型由 RVP_WeaponDisplay 负责）。
        super();
        initBaseFields(pojo);
        var fire = getSoundEvents().get("fire");
        var far = getSoundEvents().get("fire_far");
        this.fireSound = org.ywzj.rvp.config.RVP_DisplayFireSound.bind(pojo.fireSound,
                fire == null ? null : fire.getLocation(), far == null ? null : far.getLocation());
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

    /**
     * 手工复制 {@code BaseDisplay(BaseDisplayPojo)} 中与武器 display 有关的字段初始化，
     * 唯一省略的是模型烘焙（由 RVP 工厂完成）和动画表构建（由 {@code RVP_WeaponDisplay} 完成）。
     */
    private void initBaseFields(RVP_BaseDisplayPojo pojo) {
        this.displayGroup = pojo.displayGroup;
        this.modelPath = pojo.model;
        if (pojo.model != null) {
            // 调用本体资源管理器取已缓存的模型 POJO，供渲染/调试读取 modelPojo。
            ClientAssetsManager.INSTANCE.getModel(pojo.model).ifPresent(model -> this.modelPojo = model);
        }
        this.texture = pojo.texture;
        this.slotTexture = pojo.slotTexture;
        this.animationControllerPath = pojo.animationController;
        this.specialBoneEffects = pojo.specialBoneEffects == null ? List.of() : List.copyOf(pojo.specialBoneEffects);
        if (pojo.sounds != null) {
            // 与本体构造同款：把 sounds 配置转成可变范围音效，供 fire/fire_far 绑定与渲染读取。
            pojo.sounds.forEach((name, location) ->
                    this.soundEvents.put(name, SoundEvent.createVariableRangeEvent(location)));
        }
        this.description = pojo.description;
        this.tabName = pojo.tabName;
        this.tabIndex = pojo.tabIndex;
        this.tabIcon = pojo.tabIcon;
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
