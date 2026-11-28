package org.ywzj.rvp.client.resource.vehicle;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.BoneIndexProvider;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockAnimationFile;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.FixedWingVehicleDisplayPojo;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.client.resource.vehicle.TrackedVehicleDisplayPojo;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.client.resource.vehicle.VehicleDisplayPojo;
import org.ywzj.vehicle.custom.serialize.GsonUtil;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** 保留资源入口，在载具初始化或预览首次使用时生成渲染模型和动画。 */
final class RVP_DeferredVehicleModel {
    private static final Logger LOGGER = LogUtils.getLogger();
    // 可重新从当前这次重载的资源入口读取，避免 display 长期持有完整网格 POJO。
    private final ResourceHandle<BedrockModelPOJO> modelSource;
    private final ResourceHandle<BedrockAnimationFile> animationSource;
    private final List<SpecialBoneEffect> effects;
    private final BaseDisplay display;
    // 将模型和动画一起交给本体的显示对象，控制器随后使用同一份骨骼索引。
    private final BiConsumer<VehicleBedrockModel, Map<String, BedrockAnimation>> install;
    private @Nullable Runnable initializeController;
    private @Nullable VehicleBedrockModel model;
    private boolean loading;
    private volatile boolean loaded;

    RVP_DeferredVehicleModel(BaseDisplay display, VehicleDisplayPojo pojo,
                             BiConsumer<VehicleBedrockModel, Map<String, BedrockAnimation>> install) {
        this.display = display;
        this.install = install;
        this.effects = pojo.specialBoneEffects == null ? List.of() : List.copyOf(pojo.specialBoneEffects);
        this.modelSource = new ResourceHandle<>(pojo.model, "models/bedrock", BedrockModelPOJO.class);
        this.animationSource = new ResourceHandle<>(pojo.animations, "animations/bedrock", BedrockAnimationFile.class);
    }

    @Nullable VehicleBedrockModel get() {
        if (!loaded) {
            load();
        }
        return model;
    }

    @Nullable BedrockModelPOJO getModelPojo() {
        return modelSource.read();
    }

    synchronized void initializeController(Runnable action) {
        if (loaded || loading) {
            action.run();
        } else {
            // 保存本体原有的动画控制器初始化入口，等动画及骨骼索引可用时调用。
            initializeController = action;
        }
    }

    private synchronized void load() {
        if (loaded || loading) {
            return;
        }
        loading = true;
        try {
            build();
        } catch (RuntimeException error) {
            // 延迟后此入口也可能从渲染触发。坏的资源沿用无模型回退并只记一次错误。
            LOGGER.warn("按需构建载具模型失败：{}", display.getModelPath(), error);
            model = null;
            install.accept(null, Map.of());
            initializeController = null;
        } finally {
            loading = false;
        }
        loaded = true;
    }

    private void build() {
        BedrockModelPOJO pojo = modelSource.read();
        Map<String, BedrockAnimation> animations = new HashMap<>();
        if (pojo != null) {
            // 直接使用 RVP 后端的模型工厂生成最终模型，并保持原有特殊骨处理。
            model = RVP_VehicleModelFactory.createVehicleModel(pojo, effects, RVP_BedrockBackend.RVP);
            BedrockAnimationFile source = animationSource.read();
            if (source != null) {
                BoneIndexProvider indices = model.getBakedModel();
                for (BedrockAnimation animation : BedrockAnimation.createAnimation(source, indices == null ? model : indices)) {
                    animations.put(animation.getName(), animation);
                }
            }
        }
        install.accept(model, animations.isEmpty() ? Map.of() : animations);
        if (model != null) {
            // 资源重载时的全量遍历会跳过未使用模型，这里补齐当前模型的渲染规则。
            RVP_DisplayBackendUtil.bindModel(display, model);
        }
        if (initializeController != null) {
            Runnable action = initializeController;
            initializeController = null;
            action.run();
        }
    }

    /** 只将轻量配置交给本体构造方法。原 POJO 仍供 RVP 延迟读取主体，舱内模型沿用本体流程。 */
    static VehicleDisplayPojo base(VehicleDisplayPojo pojo, RVP_BedrockBackend backend) {
        return backend == RVP_BedrockBackend.RVP ? copyMetadata(pojo, new VehicleDisplayPojo()) : pojo;
    }

    static TrackedVehicleDisplayPojo trackedBase(TrackedVehicleDisplayPojo pojo, String backend) {
        if (!isRvp(backend)) {
            return pojo;
        }
        TrackedVehicleDisplayPojo result = copyMetadata(pojo, new TrackedVehicleDisplayPojo());
        result.trackConfig = pojo.trackConfig;
        return result;
    }

    static FixedWingVehicleDisplayPojo fixedWingBase(FixedWingVehicleDisplayPojo pojo, String backend) {
        FixedWingVehicleDisplayPojo result = copyMetadata(pojo, new FixedWingVehicleDisplayPojo());
        if (!isRvp(backend)) {
            result.model = pojo.model;
        }
        // 固定翼由 RVP 子类创建尾焰，本体这一步只保留尾焰贴图地址。
        result.afterburnerModel = null;
        result.afterburnerAnimations = null;
        result.afterburnerTexture = pojo.afterburnerTexture;
        return result;
    }

    private static boolean isRvp(String backend) {
        return RVP_BedrockBackend.fromString(backend) == RVP_BedrockBackend.RVP;
    }

    private static <T extends VehicleDisplayPojo> T copyMetadata(VehicleDisplayPojo pojo, T result) {
        result.displayGroup = pojo.displayGroup;
        result.texture = pojo.texture;
        result.slotTexture = pojo.slotTexture;
        // 主体模型为空时本体不会生成主体动画，保留动画地址供独立的舱内模型使用。
        result.animations = pojo.animations;
        result.animationController = pojo.animationController;
        result.specialBoneEffects = pojo.specialBoneEffects;
        result.sounds = pojo.sounds;
        result.description = pojo.description;
        result.tabName = pojo.tabName;
        result.tabIndex = pojo.tabIndex;
        result.tabIcon = pojo.tabIcon;
        result.cabinModel = pojo.cabinModel;
        result.cabinTexture = pojo.cabinTexture;
        return result;
    }

    /** 资源重载期间可复用本体所读的对象，重载完成后仅用弱引用复用临时解析结果。 */
    private static final class ResourceHandle<T> {
        private final @Nullable ResourceLocation file;
        private final @Nullable Resource resource;
        private final Class<T> type;
        // 仅为本体注册但没有独立资源文件的模型保留对象，以兼容运行时生成的资源。
        private final @Nullable T generated;
        private WeakReference<T> parsed;
        private boolean failed;

        private ResourceHandle(@Nullable ResourceLocation id, String directory, Class<T> type) {
            this.type = type;
            this.file = id == null ? null : id.withPath(path -> directory + "/" + path + ".json");
            this.resource = file == null ? null : Minecraft.getInstance().getResourceManager().getResource(file).orElse(null);
            Object existing = id == null ? null : type == BedrockModelPOJO.class
                    ? ClientAssetsManager.INSTANCE.getModel(id).orElse(null)
                    : ClientAssetsManager.INSTANCE.getAnimation(id).orElse(null);
            T initial = type.cast(existing);
            this.generated = resource == null ? initial : null;
            this.parsed = new WeakReference<>(initial);
        }

        private synchronized @Nullable T read() {
            if (resource == null) {
                return generated;
            }
            T existing = parsed.get();
            if (existing != null || failed) {
                return existing;
            }
            try (var reader = resource.openAsReader()) {
                T result = GsonUtil.GSON.fromJson(reader, type);
                parsed = new WeakReference<>(result);
                failed = result == null;
                return result;
            } catch (IOException | JsonParseException | IllegalStateException error) {
                failed = true;
                LOGGER.warn("读取按需载具资源失败：{}", file, error);
                return null;
            }
        }
    }
}
