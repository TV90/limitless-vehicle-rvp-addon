package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.client.resource.vehicle.RVP_BedrockBackend;
import org.ywzj.rvp.client.resource.vehicle.RVP_VehicleModelFactory;
import org.ywzj.rvp.config.RVP_CustomMountConfig;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.DisplayManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.SpecialBoneEffect;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.slf4j.Logger;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RVP_CustomMountRenderLogic {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, VehicleBedrockModel> MODEL_CACHE = new ConcurrentHashMap<>();
    private static final Map<PredictionKey, PredictedAmmo> PREDICTED_AMMO = new ConcurrentHashMap<>();
    private static final AtomicBoolean DEBUG_ENABLED = new AtomicBoolean(false);
    private static final Map<String, String> LAST_DEBUG_STATES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> AUTO_TRACE_KEYS = new ConcurrentHashMap<>();
    private static final Path DEBUG_LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("custommountdebug.log");
    private static final DateTimeFormatter DEBUG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private RVP_CustomMountRenderLogic() {}

    public static void clearModelCache() {
        MODEL_CACHE.clear();
        AUTO_TRACE_KEYS.clear();
    }

    public static boolean isDebugEnabled() {
        return DEBUG_ENABLED.get();
    }

    public static Path getDebugLogPath() {
        return DEBUG_LOG_PATH;
    }

    public static void setDebugEnabled(boolean enabled) {
        DEBUG_ENABLED.set(enabled);
        LAST_DEBUG_STATES.clear();
        AUTO_TRACE_KEYS.clear();
        if (!enabled) {
            PREDICTED_AMMO.clear();
        }
        appendDebugLog("toggle enabled=" + enabled);
    }

    public static void clearDebugLog() {
        LAST_DEBUG_STATES.clear();
        AUTO_TRACE_KEYS.clear();
        try {
            Files.deleteIfExists(DEBUG_LOG_PATH);
        } catch (IOException e) {
            LOGGER.error("[RVP][CustomMountDebug] Failed to clear {}", DEBUG_LOG_PATH, e);
        }
    }

    public static String dumpDebugSnapshot(@Nullable AbstractVehicle vehicle) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== RVP Custom Mount Debug ===\n");
        sb.append("enabled=").append(isDebugEnabled()).append('\n');
        sb.append("logPath=").append(DEBUG_LOG_PATH).append('\n');
        if (vehicle == null) {
            sb.append("vehicle=<null>\n");
            return sb.toString();
        }
        sb.append("vehicleEntityId=").append(vehicle.getId()).append('\n');
        sb.append("vehicleId=").append(vehicle.getVehicleId()).append('\n');
        List<RVP_CustomMountConfig> configs = RVP_CustomMountConfigCache.get(vehicle.getVehicleId());
        sb.append("configCount=").append(configs.size()).append('\n');
        for (ResolvedMount mount : resolveMounts(vehicle, configs)) {
            sb.append(formatResolvedMountState(vehicle, mount)).append('\n');
        }
        return sb.toString();
    }

    public static void noteClientFire(WeaponUnit weaponUnit) {
        WeaponResolution resolution = resolveCurrentWeaponForDisplay(weaponUnit);
        if (resolution == null || resolution.currentWeapon().getData() == null
                || resolution.currentWeapon().getData().getWeaponId() == null
                || resolution.displayWeaponUnit().getVehicle() == null) {
            return;
        }
        PredictionKey key = new PredictionKey(
                resolution.displayWeaponUnit().getVehicle().getId(),
                resolution.displayWeaponUnit().getId(),
                resolution.currentWeapon().getData().getWeaponId()
        );
        // consumeAmmo() 已在 shoot() 中本地扣减 remainAmmo，
        // 预测值直接取当前 remainAmmo 即可，不再额外扣减。
        int predictedAmmo = Math.max(0, resolution.currentWeapon().getRemainAmmo());
        PREDICTED_AMMO.put(key, new PredictedAmmo(predictedAmmo, System.currentTimeMillis()));
        if (DEBUG_ENABLED.get()) {
            appendDebugLog("clientFire vehicle=" + resolution.displayWeaponUnit().getVehicle().getVehicleId()
                    + " entityId=" + resolution.displayWeaponUnit().getVehicle().getId()
                    + " partUnit=" + resolution.displayWeaponUnit().getId()
                    + " weapon=" + resolution.currentWeapon().getData().getWeaponId()
                    + " remainAmmo=" + predictedAmmo
                    + " predictedAmmo=" + predictedAmmo
                    + " sourceUnit=" + weaponUnit.getId());
        }
    }

    public static void render(AbstractVehicle vehicle,
                              VehicleBedrockModel vehicleModel,
                              PoseStack poseStack,
                              MultiBufferSource bufferSource,
                              int packedLight) {
        List<RVP_CustomMountConfig> configs = RVP_CustomMountConfigCache.get(vehicle.getVehicleId());
        if (configs.isEmpty()) {
            return;
        }
        List<ResolvedMount> resolvedMounts = resolveMounts(vehicle, configs);
        autoTraceRuntimeState(vehicle, vehicleModel, configs, resolvedMounts);
        if (DEBUG_ENABLED.get()) {
            noteResolvedMountStates(vehicle, resolvedMounts);
        }
        int actualLight = vehicle.isDestroyed() ? 64 : packedLight;
        boolean localPlayerVehicle = vehicle == org.ywzj.vehicle.vehicle.LocalVehiclePlayer.instance.getVehicle();
        for (RVP_CustomMountConfig config : configs) {
            ResolvedMount resolved = resolvedMounts.stream()
                    .filter(entry -> entry.config() == config)
                    .findFirst()
                    .orElse(null);
            if (resolved == null) {
                if (DEBUG_ENABLED.get()) {
                    appendDebugLog("render SKIP: unresolved vehicle=" + vehicle.getVehicleId()
                            + " partUnit=" + config.partUnitId()
                            + " attachPart=" + config.attachPartUnitId()
                            + " weapon=" + config.weaponId());
                }
                continue;
            }
            VehicleBedrockModel attachmentModel = getAttachmentModel(config.model());
            if (attachmentModel == null || !attachmentModel.hasBakedModel()) {
                if (DEBUG_ENABLED.get()) {
                    appendDebugLog("render SKIP: attachmentModel null vehicle=" + vehicle.getVehicleId()
                            + " model=" + config.model()
                            + " weapon=" + config.weaponId());
                }
                continue;
            }
            AttachmentTransform attachmentTransform = resolveAttachmentTransform(vehicle, vehicleModel, config);
            if (attachmentTransform == null) {
                if (DEBUG_ENABLED.get()) {
                    appendDebugLog("render SKIP: attachmentTransform null vehicle=" + vehicle.getVehicleId()
                            + " partUnit=" + config.partUnitId()
                            + " attachPart=" + config.attachPartUnitId()
                            + " attachBone=" + config.attachBone());
                }
                continue;
            }

            // 计算需要隐藏的骨骼：rack_bones 全显/全隐，missile_bones 逐枚隐藏
            List<String> hiddenRackBones = List.of();
            List<PartiallyHiddenBone> partiallyHiddenMissiles = null;
            if (config.missileBones().size() > 1) {
                // 多枚导弹模式：逐枚隐藏
                int visibleCount = resolved.visibleMissileCount();
                partiallyHiddenMissiles = new ArrayList<>(config.missileBones().size());
                for (int bi = 0; bi < config.missileBones().size(); bi++) {
                    // missile_bones 按顺序对应弹药发射顺序：index 0 先发射先隐藏
                    boolean visible = bi < visibleCount;
                    partiallyHiddenMissiles.add(new PartiallyHiddenBone(config.missileBones().get(bi), visible));
                }
            } else {
                // 单枚导弹模式：保持原有全显/全隐
                if (resolved.shouldHideMissile()) {
                    hiddenRackBones = new ArrayList<>(hiddenRackBones);
                    hiddenRackBones.addAll(config.missileBones());
                }
            }
            BakedModelInstance attachmentInstance = attachmentModel.createBakedInstance();
            applyAttachmentPose(attachmentInstance, hiddenRackBones, partiallyHiddenMissiles);
            if (DEBUG_ENABLED.get()) {
                noteRenderPose(vehicle, resolved);
            }
            poseStack.pushPose();
            try {
                poseStack.mulPoseMatrix(attachmentTransform.transform());
                RVP_CustomMountConfig.Vec3fConfig offset = config.offset();
                poseStack.translate(offset.x(), offset.y(), offset.z());
                RVP_CustomMountConfig.Vec3fConfig rotation = config.rotationDeg();
                poseStack.mulPose(Axis.XP.rotationDegrees(rotation.x()));
                poseStack.mulPose(Axis.YP.rotationDegrees(rotation.y()));
                poseStack.mulPose(Axis.ZP.rotationDegrees(rotation.z()));
                RVP_CustomMountConfig.Vec3fConfig scale = config.scale();
                poseStack.scale(scale.x(), scale.y(), scale.z());
                attachmentModel.renderToBuffer(attachmentInstance, poseStack, bufferSource, config.texture(), actualLight);
                attachmentModel.renderSpecialBones(
                        attachmentInstance,
                        poseStack,
                        bufferSource,
                        actualLight,
                        OverlayTexture.NO_OVERLAY,
                        localPlayerVehicle
                );
            } finally {
                poseStack.popPose();
            }
        }
    }

    public static boolean shouldSuppressDefaultWeaponDisplay(WeaponUnit weaponUnit) {
        ResolvedMount resolved = resolveForCurrentWeapon(weaponUnit);
        return resolved != null && resolved.config().replaceWeaponDisplay();
    }

    private static List<ResolvedMount> resolveMounts(AbstractVehicle vehicle, List<RVP_CustomMountConfig> configs) {
        List<ResolvedMount> resolved = new ArrayList<>();
        Map<GroupKey, List<ResolvedMount>> grouped = new HashMap<>();
        for (RVP_CustomMountConfig config : configs) {
            if (!(vehicle.getPartUnit(config.partUnitId()).orElse(null) instanceof WeaponUnit weaponUnit)) {
                continue;
            }
            WeaponResolution resolution = resolveCurrentWeaponForDisplay(weaponUnit);
            if (resolution == null || resolution.currentWeapon().getData() == null
                    || resolution.currentWeapon().getData().getWeaponId() == null) {
                continue;
            }
            AbstractVehicleWeapon<?> currentWeapon = resolution.currentWeapon();
            if (!config.weaponId().equals(currentWeapon.getData().getWeaponId())) {
                continue;
            }
            GroupKey key = new GroupKey(config.partUnitId(), config.weaponId());
            VisibleAmmo visibleAmmo = resolveVisibleAmmo(vehicle, config.partUnitId(), currentWeapon);
            ResolvedMount entry = new ResolvedMount(config, visibleAmmo.syncedAmmo(), visibleAmmo.visibleAmmo(),
                    visibleAmmo.predictedAmmo(), 0, false, resolution.displayWeaponUnit().getFiringMode());
            resolved.add(entry);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        grouped.values().forEach(RVP_CustomMountRenderLogic::assignAmmoVisibility);
        Map<RVP_CustomMountConfig, ResolvedMount> updatedByConfig = new HashMap<>();
        for (List<ResolvedMount> mounts : grouped.values()) {
            for (ResolvedMount mount : mounts) {
                updatedByConfig.put(mount.config(), mount);
            }
        }
        List<ResolvedMount> finalResolved = new ArrayList<>(resolved.size());
        for (ResolvedMount mount : resolved) {
            finalResolved.add(updatedByConfig.getOrDefault(mount.config(), mount));
        }
        return finalResolved;
    }

    @Nullable
    private static ResolvedMount resolveForCurrentWeapon(WeaponUnit weaponUnit) {
        ResourceLocation vehicleId = weaponUnit.getVehicle() != null ? weaponUnit.getVehicle().getVehicleId() : null;
        if (vehicleId == null) {
            return null;
        }
        WeaponResolution resolution = resolveCurrentWeaponForDisplay(weaponUnit);
        if (resolution == null || resolution.currentWeapon().getData() == null
                || resolution.currentWeapon().getData().getWeaponId() == null) {
            return null;
        }
        AbstractVehicleWeapon<?> currentWeapon = resolution.currentWeapon();
        for (ResolvedMount resolved : resolveMounts(weaponUnit.getVehicle(), RVP_CustomMountConfigCache.get(vehicleId))) {
            if (matchesConfiguredPartUnit(weaponUnit, resolved.config().partUnitId())
                    && currentWeapon.getData().getWeaponId().equals(resolved.config().weaponId())) {
                return resolved;
            }
        }
        return null;
    }

    @Nullable
    private static Field X_TURN_GROUP_FIELD;

    /**
     * [RVP] 挂架锚点骨组。B1 移除了 WeaponUnitAccessor（getXTurnGroup），当时用 getStructureGroup() 近似；
     * 但结构模型中仅存在 structure_bone + "_barrel" 骨（如 variable_aam_1_barrel），无 structure_bone 本体骨，
     * 导致 structureGroup 恒为 null、挂架整体不渲染。此处用反射读取实例 xTurnGroup 恢复原行为，
     * 反射不可用时回退 structureGroup（仅功能降级，不崩溃）。
     */
    @Nullable
    private static VehicleCubeGroup resolveMountAnchorGroup(WeaponUnit mountUnit) {
        if (X_TURN_GROUP_FIELD == null) {
            try {
                X_TURN_GROUP_FIELD = ObfuscationReflectionHelper.findField(WeaponUnit.class, "xTurnGroup");
                X_TURN_GROUP_FIELD.setAccessible(true);
            } catch (Throwable t) {
                LOGGER.warn("[RVP] 无法解析 WeaponUnit.xTurnGroup 字段，挂架锚点退化为 structureGroup", t);
                return mountUnit.getStructureGroup();
            }
        }
        try {
            VehicleCubeGroup group = (VehicleCubeGroup) X_TURN_GROUP_FIELD.get(mountUnit);
            if (group != null) {
                return group;
            }
        } catch (Throwable t) {
            LOGGER.debug("[RVP] 读取 WeaponUnit.xTurnGroup 失败，退化为 structureGroup", t);
        }
        return mountUnit.getStructureGroup();
    }

    @Nullable
    private static AttachmentTransform resolveAttachmentTransform(AbstractVehicle vehicle,
                                                                 VehicleBedrockModel vehicleModel,
                                                                 RVP_CustomMountConfig config) {
        if (!config.attachPartUnitId().isEmpty()) {
            if (!(vehicle.getPartUnit(config.attachPartUnitId()).orElse(null) instanceof WeaponUnit mountUnit)) {
                return null;
            }
            // [RVP] accessor 已移除：优先反射读实例 xTurnGroup（= structure_bone + "_barrel" 骨组，挂架锚点），失败退化为 structureGroup
            VehicleCubeGroup xTurnGroup = resolveMountAnchorGroup(mountUnit);
            List<Bolt> bolts = mountUnit.getBolts();
            if (xTurnGroup == null) {
                return null;
            }
            Vec3 translate;
            if (bolts.isEmpty()) {
                translate = xTurnGroup.pivotOffset;
            } else {
                Bolt bolt = bolts.get(0);
                translate = xTurnGroup.pivotOffset.add(bolt.offset).add(0.0, 0.0, bolt.barrelLength / 2.0);
            }
            Matrix4f matrix = new Matrix4f().translation((float) translate.x, (float) translate.y, (float) translate.z);
            return new AttachmentTransform(matrix);
        }
        BakedModelInstance vehicleModelInstance = vehicle.getModelInstance();
        int attachBoneIndex = vehicleModelInstance.getIndex(config.attachBone());
        if (attachBoneIndex < 0 || vehicleModelInstance.getBone(attachBoneIndex) == null) {
            return null;
        }
        return new AttachmentTransform(vehicleModelInstance.getGlobalTransform(attachBoneIndex));
    }

    @Nullable
    private static AbstractVehicleWeapon<?> resolveCurrentWeapon(WeaponUnit weaponUnit) {
        AbstractVehicleWeapon<?> weapon = weaponUnit.getCurrentWeapon().orElse(null);
        while (weapon != null) {
            AbstractVehicleWeapon<?> unwrapped = RVP_LaserWeapons.unwrap(weapon);
            if (unwrapped == weapon) {
                return weapon;
            }
            weapon = unwrapped;
            if (!(weapon instanceof VehicleWeaponAgent) && !(weapon instanceof VehicleMultiWeapons)) {
                return weapon;
            }
        }
        return null;
    }

    @Nullable
    private static WeaponResolution resolveCurrentWeaponForDisplay(WeaponUnit weaponUnit) {
        WeaponUnit displayWeaponUnit = weaponUnit;
        while (displayWeaponUnit != null) {
            AbstractVehicleWeapon<?> currentWeapon = resolveCurrentWeapon(displayWeaponUnit);
            if (currentWeapon != null) {
                return new WeaponResolution(displayWeaponUnit, currentWeapon);
            }
            displayWeaponUnit = displayWeaponUnit.getParentWeaponUnit();
        }
        return null;
    }

    private static VisibleAmmo resolveVisibleAmmo(AbstractVehicle vehicle,
                                                  String partUnitId,
                                                  AbstractVehicleWeapon<?> currentWeapon) {
        int syncedAmmo = Math.max(0, currentWeapon.getRemainAmmo());
        if (currentWeapon.getData() == null || currentWeapon.getData().getWeaponId() == null) {
            return new VisibleAmmo(syncedAmmo, syncedAmmo, null);
        }
        PredictionKey key = new PredictionKey(vehicle.getId(), partUnitId, currentWeapon.getData().getWeaponId());
        PredictedAmmo predicted = PREDICTED_AMMO.get(key);
        if (predicted == null) {
            return new VisibleAmmo(syncedAmmo, syncedAmmo, null);
        }
        if (syncedAmmo <= predicted.ammo() || syncedAmmo >= currentWeapon.getMaxCapacity()) {
            PREDICTED_AMMO.remove(key);
            return new VisibleAmmo(syncedAmmo, syncedAmmo, null);
        }
        return new VisibleAmmo(syncedAmmo, Math.min(syncedAmmo, predicted.ammo()), predicted.ammo());
    }

    @Nullable
    private static VehicleBedrockModel getAttachmentModel(ResourceLocation modelId) {
        return MODEL_CACHE.computeIfAbsent(modelId, id -> {
            BaseDisplay persistedDisplay = resolveAttachmentDisplay(id);
            if (persistedDisplay != null) {
                VehicleBedrockModel rebuiltDisplayModel = rebuildAttachmentModelFromDisplay(id, persistedDisplay);
                if (rebuiltDisplayModel != null) {
                    return rebuiltDisplayModel;
                }
                if (persistedDisplay.getModel() != null) {
                    return persistedDisplay.getModel();
                }
            }
            Set<String> preservedBones = collectCustomMountPreservedBones(id);
            VehicleBedrockModel directModel = ClientAssetsManager.INSTANCE.getModel(id)
                    .map(modelPojo -> RVP_VehicleModelFactory.createVehicleModel(
                            modelPojo,
                            List.of(),
                            RVP_BedrockBackend.RVP,
                            preservedBones
                    ))
                    .orElseGet(() -> loadAttachmentModelDirect(id, List.of(), preservedBones));
            if (directModel != null) {
                return directModel;
            }
            return null;
        });
    }

    @Nullable
    private static BaseDisplay resolveAttachmentDisplay(ResourceLocation modelId) {
        BaseDisplay byDecorationId = ClientAssetsManager.INSTANCE.getDecorationDisplay(modelId).orElse(null);
        if (byDecorationId != null) {
            return byDecorationId;
        }
        BaseDisplay byWeaponId = ClientAssetsManager.INSTANCE.getWeaponDisplay(modelId).orElse(null);
        if (byWeaponId != null) {
            return byWeaponId;
        }
        BaseDisplay byDecorationModelPath = ClientAssetsManager.INSTANCE.getDecorationDisplays().stream()
                .filter(display -> modelId.equals(display.getModelPath()))
                .findFirst()
                .orElse(null);
        if (byDecorationModelPath != null) {
            return byDecorationModelPath;
        }
        return getWeaponDisplays().stream()
                .filter(display -> modelId.equals(display.getModelPath()))
                .findFirst()
                .orElse(null);
    }

    private static List<BaseDisplay> getWeaponDisplays() {
        try {
            Field field = ClientAssetsManager.class.getDeclaredField("weaponDisplayManager");
            field.setAccessible(true);
            Object value = field.get(ClientAssetsManager.INSTANCE);
            if (value instanceof DisplayManager manager) {
                return new ArrayList<>(manager.getDisplayMap().values());
            }
        } catch (ReflectiveOperationException exception) {
            if (DEBUG_ENABLED.get()) {
                appendDebugLog("weaponDisplay reflect FAIL reason=" + exception.getClass().getSimpleName());
            }
        }
        return List.of();
    }

    @Nullable
    private static VehicleBedrockModel rebuildAttachmentModelFromDisplay(ResourceLocation modelId,
                                                                         BaseDisplay display) {
        Set<String> preservedBones = collectCustomMountPreservedBones(modelId);
        if (preservedBones.isEmpty() && display.getModel() != null && display.getModel().hasBakedModel()) {
            return display.getModel();
        }
        ResourceLocation modelPath = display.getModelPath();
        if (modelPath == null) {
            return display.getModel();
        }
        List<SpecialBoneEffect> effects = display.getSpecialBoneEffects() == null
                ? List.of()
                : display.getSpecialBoneEffects();
        VehicleBedrockModel directModel = ClientAssetsManager.INSTANCE.getModel(modelPath)
                .map(modelPojo -> RVP_VehicleModelFactory.createVehicleModel(
                        modelPojo,
                        effects,
                        RVP_BedrockBackend.RVP,
                        preservedBones
                ))
                .orElseGet(() -> loadAttachmentModelDirect(modelPath, effects, preservedBones));
        return directModel != null ? directModel : display.getModel();
    }

    @Nullable
    private static VehicleBedrockModel loadAttachmentModelDirect(ResourceLocation modelId,
                                                                 List<SpecialBoneEffect> effects,
                                                                 Set<String> preservedBones) {
        ResourceLocation resourcePath = new ResourceLocation(
                modelId.getNamespace(),
                "models/bedrock/" + modelId.getPath() + ".json"
        );
        try {
            var resourceOptional = Minecraft.getInstance().getResourceManager().getResource(resourcePath);
            if (resourceOptional.isEmpty()) {
                return null;
            }
            try (Reader reader = resourceOptional.get().openAsReader()) {
                BedrockModelPOJO pojo = GsonUtil.GSON.fromJson(reader, BedrockModelPOJO.class);
                return pojo == null ? null : RVP_VehicleModelFactory.createVehicleModel(
                        pojo,
                        effects == null ? List.of() : effects,
                        RVP_BedrockBackend.RVP,
                        preservedBones
                );
            }
        } catch (Exception exception) {
            if (DEBUG_ENABLED.get()) {
                appendDebugLog("attachmentModel direct-load FAIL model=" + modelId + " reason=" + exception.getClass().getSimpleName());
            }
            return null;
        }
    }

    /**
     * 应用挂架模型的骨骼姿态。
     *
     * @param model              挂架模型
     * @param hiddenBones        需要完全隐藏的骨骼列表（全显/全隐模式）
     * @param partiallyHiddenBones 需要逐枚隐藏的导弹骨骼及其可见数量；
     *                            为 null 或空时使用 hiddenBones 的全显/全隐逻辑
     */
    private static void applyAttachmentPose(BakedModelInstance instance,
                                            List<String> hiddenBones,
                                            @Nullable List<PartiallyHiddenBone> partiallyHiddenBones) {
        for (String boneName : hiddenBones) {
            setBoneVisible(instance, boneName, false);
        }
        if (partiallyHiddenBones != null) {
            for (PartiallyHiddenBone phb : partiallyHiddenBones) {
                if (!phb.visible()) {
                    setBoneVisible(instance, phb.boneName(), false);
                }
            }
        }
    }

    private static Set<String> collectCustomMountPreservedBones(ResourceLocation modelId) {
        Set<String> preservedBones = new java.util.LinkedHashSet<>();
        for (List<RVP_CustomMountConfig> configs : RVP_CustomMountConfigCache.all().values()) {
            for (RVP_CustomMountConfig config : configs) {
                if (!modelId.equals(config.model())) {
                    continue;
                }
                preservedBones.addAll(config.rackBones());
                preservedBones.addAll(config.missileBones());
            }
        }
        return preservedBones;
    }

    /** 单枚导弹骨骼的可见性记录。 */
    private record PartiallyHiddenBone(String boneName, boolean visible) {}

    private static void setBoneVisible(BakedModelInstance instance, String boneName, boolean visible) {
        int boneIndex = instance.getIndex(boneName);
        if (boneIndex < 0) {
            return;
        }
        var bone = instance.getBone(boneIndex);
        if (bone != null) {
            bone.visible = visible;
        }
    }

    /**
     * 根据总剩余弹药数为每个挂架分配可见导弹数量。
     *
     * <p>对于单枚导弹挂架（{@code missileBones.size() <= 1}），保持原有逻辑：
     * {@code hideMissile = visibleAmmo <= 0 || visibleAmmo < slot}。</p>
     *
     * <p>对于多枚导弹挂架（{@code missileBones.size() > 1}），按如下规则分配：
     * <ul>
     *   <li>每个挂架的弹药起始偏移 = {@code (ammoSlot - 1) × missileBones.size()}</li>
     *   <li>该挂架可见导弹数 = {@code max(0, min(missileBones.size(), visibleAmmo - offset))}</li>
     * </ul>
     * 例如 AASM 三联挂架：2 个挂架 × 3 枚导弹 = 6 发总弹药
     * <ul>
     *   <li>ammo=6 → 挂架1=3, 挂架2=3</li>
     *   <li>ammo=5 → 挂架1=3, 挂架2=2</li>
     *   <li>ammo=4 → 挂架1=3, 挂架2=1</li>
     *   <li>ammo=3 → 挂架1=3, 挂架2=0</li>
     *   <li>ammo=2 → 挂架1=2, 挂架2=0</li>
     *   <li>ammo=1 → 挂架1=1, 挂架2=0</li>
     *   <li>ammo=0 → 挂架1=0, 挂架2=0</li>
     * </ul>
     * </p>
     */
    private static void assignAmmoVisibility(List<ResolvedMount> mounts) {
        mounts.sort(Comparator
                .comparingInt((ResolvedMount mount) -> mount.config().ammoSlot() > 0 ? mount.config().ammoSlot() : Integer.MAX_VALUE)
                .thenComparingInt(mount -> mount.config().configOrder()));
        int fallbackSlot = 1;
        for (int i = 0; i < mounts.size(); i++) {
            ResolvedMount mount = mounts.get(i);
            int slot = mount.config().ammoSlot() > 0 ? mount.config().ammoSlot() : fallbackSlot++;
            int missileCount = mount.config().missileBones().size();

            if (missileCount <= 1) {
                // 单枚导弹模式：保持原有逻辑
                boolean hideMissile = mount.visibleAmmo() <= 0 || mount.visibleAmmo() < slot;
                mounts.set(i, new ResolvedMount(mount.config(), mount.syncedAmmo(), mount.visibleAmmo(),
                        mount.predictedAmmo(), slot, hideMissile, mount.firingMode()));
            } else {
                // 多枚导弹模式：计算该挂架上可见导弹数量
                int visibleOnThisPylon;
                if (mount.firingMode() == WeaponUnitData.FiringMode.SALVO && mounts.size() > 1) {
                    int available = mount.visibleAmmo() - (slot - 1);
                    visibleOnThisPylon = available <= 0 ? 0
                            : Math.min(missileCount, (available + mounts.size() - 1) / mounts.size());
                } else {
                    int offset = (slot - 1) * missileCount;
                    visibleOnThisPylon = Math.max(0, Math.min(missileCount, mount.visibleAmmo() - offset));
                }
                boolean hideMissile = visibleOnThisPylon <= 0;
                mounts.set(i, new ResolvedMount(mount.config(), mount.syncedAmmo(), mount.visibleAmmo(),
                        mount.predictedAmmo(), slot, hideMissile, visibleOnThisPylon, mount.firingMode()));
            }
        }
    }

    private static void noteResolvedMountStates(AbstractVehicle vehicle, List<ResolvedMount> mounts) {
        for (ResolvedMount mount : mounts) {
            String key = debugKey(vehicle, mount);
            String state = formatResolvedMountState(vehicle, mount);
            String previous = LAST_DEBUG_STATES.put(key, state);
            if (!state.equals(previous)) {
                appendDebugLog("state " + state);
            }
        }
    }

    private static void autoTraceRuntimeState(AbstractVehicle vehicle,
                                              VehicleBedrockModel vehicleModel,
                                              List<RVP_CustomMountConfig> configs,
                                              List<ResolvedMount> resolvedMounts) {
        if (configs.isEmpty()) {
            return;
        }
        String currentWeaponId = "<none>";
        WeaponUnit localWeaponUnit = org.ywzj.vehicle.vehicle.LocalVehiclePlayer.instance != null
                ? org.ywzj.vehicle.vehicle.LocalVehiclePlayer.instance.getWeaponUnit()
                : null;
        if (localWeaponUnit != null) {
            WeaponResolution resolution = resolveCurrentWeaponForDisplay(localWeaponUnit);
            if (resolution != null && resolution.currentWeapon() != null && resolution.currentWeapon().getData() != null
                    && resolution.currentWeapon().getData().getWeaponId() != null) {
                currentWeaponId = resolution.currentWeapon().getData().getWeaponId().toString();
            }
        }
        String traceKey = vehicle.getId() + "|" + vehicle.getVehicleId() + "|" + currentWeaponId;
        if (AUTO_TRACE_KEYS.putIfAbsent(traceKey, Boolean.TRUE) != null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== AUTO TRACE ===\n");
        sb.append("vehicle=").append(vehicle.getVehicleId()).append('\n');
        sb.append("entityId=").append(vehicle.getId()).append('\n');
        sb.append("currentWeapon=").append(currentWeaponId).append('\n');
        sb.append("configCount=").append(configs.size()).append('\n');
        sb.append("resolvedCount=").append(resolvedMounts.size()).append('\n');
        sb.append("vehicleModelClass=").append(vehicleModel == null ? "<null>" : vehicleModel.getClass().getName()).append('\n');

        for (RVP_CustomMountConfig config : configs) {
            sb.append("-- config --\n");
            sb.append("partUnit=").append(config.partUnitId()).append('\n');
            sb.append("attachPart=").append(config.attachPartUnitId()).append('\n');
            sb.append("attachBone=").append(config.attachBone()).append('\n');
            sb.append("weaponId=").append(config.weaponId()).append('\n');
            sb.append("model=").append(config.model()).append('\n');

            PartUnit<?> partUnit = vehicle.getPartUnit(config.partUnitId()).orElse(null);
            sb.append("partUnitClass=").append(partUnit == null ? "<null>" : partUnit.getClass().getName()).append('\n');
            if (partUnit instanceof WeaponUnit weaponUnit) {
                WeaponResolution resolution = resolveCurrentWeaponForDisplay(weaponUnit);
                sb.append("partCurrentWeapon=")
                        .append(resolution == null || resolution.currentWeapon() == null || resolution.currentWeapon().getData() == null
                                || resolution.currentWeapon().getData().getWeaponId() == null
                                ? "<null>"
                                : resolution.currentWeapon().getData().getWeaponId())
                        .append('\n');
            }

            PartUnit<?> attachPart = config.attachPartUnitId().isEmpty() ? null : vehicle.getPartUnit(config.attachPartUnitId()).orElse(null);
            sb.append("attachPartClass=").append(attachPart == null ? "<null>" : attachPart.getClass().getName()).append('\n');
            if (attachPart instanceof WeaponUnit attachWeaponUnit) {
                sb.append("attachPartBolts=").append(attachWeaponUnit.getBolts().size()).append('\n');
                sb.append("attachPartPivot=").append(attachWeaponUnit.getPivotOffset()).append('\n');
            }

            BaseDisplay display = resolveAttachmentDisplay(config.model());
            sb.append("displayFound=").append(display != null).append('\n');
            if (display != null) {
                sb.append("displayClass=").append(display.getClass().getName()).append('\n');
                sb.append("displayModelClass=").append(display.getModel() == null ? "<null>" : display.getModel().getClass().getName()).append('\n');
                sb.append("displayModelPath=").append(display.getModelPath()).append('\n');
            }

            VehicleBedrockModel attachmentModel = getAttachmentModel(config.model());
            sb.append("attachmentModelClass=").append(attachmentModel == null ? "<null>" : attachmentModel.getClass().getName()).append('\n');

            AttachmentTransform transform = resolveAttachmentTransform(vehicle, vehicleModel, config);
            sb.append("attachmentTransform=").append(transform == null ? "<null>" : transform.transform()).append('\n');

            ResolvedMount resolvedMount = resolvedMounts.stream()
                    .filter(entry -> entry.config() == config)
                    .findFirst()
                    .orElse(null);
            sb.append("resolvedMount=").append(resolvedMount != null).append('\n');
            if (resolvedMount != null) {
                sb.append("visibleAmmo=").append(resolvedMount.visibleAmmo()).append('\n');
                sb.append("visibleMissileCount=").append(resolvedMount.visibleMissileCount()).append('\n');
                sb.append("hideMissile=").append(resolvedMount.shouldHideMissile()).append('\n');
            }
        }

        appendTraceLog(sb.toString().trim());
    }

    private static void noteRenderPose(AbstractVehicle vehicle, ResolvedMount mount) {
        String key = debugKey(vehicle, mount) + "|pose";
        String hiddenBones;
        if (mount.config().missileBones().size() > 1) {
            List<String> hidden = new ArrayList<>();
            for (int i = 0; i < mount.config().missileBones().size(); i++) {
                if (i >= mount.visibleMissileCount()) {
                    hidden.add(mount.config().missileBones().get(i));
                }
            }
            hiddenBones = hidden.toString();
        } else {
            hiddenBones = mount.shouldHideMissile() ? mount.config().missileBones().toString() : "[]";
        }
        String state = "render vehicle=" + vehicle.getVehicleId()
                + " partUnit=" + mount.config().partUnitId()
                + " attachPart=" + mount.config().attachPartUnitId()
                + " weapon=" + mount.config().weaponId()
                + " visibleMissileCount=" + mount.visibleMissileCount()
                + " hiddenBones=" + hiddenBones;
        String previous = LAST_DEBUG_STATES.put(key, state);
        if (!state.equals(previous)) {
            appendDebugLog(state);
        }
    }

    private static String debugKey(AbstractVehicle vehicle, ResolvedMount mount) {
        return vehicle.getId() + "|" + mount.config().partUnitId() + "|" + mount.config().attachPartUnitId()
                + "|" + mount.config().weaponId() + "|" + mount.ammoSlot();
    }

    private static boolean matchesConfiguredPartUnit(WeaponUnit queryUnit, String configuredPartUnitId) {
        WeaponUnit current = queryUnit;
        while (current != null) {
            if (configuredPartUnitId.equals(current.getId())) {
                return true;
            }
            current = current.getParentWeaponUnit();
        }
        if (queryUnit.getVehicle() == null) {
            return false;
        }
        if (!(queryUnit.getVehicle().getPartUnit(configuredPartUnitId).orElse(null) instanceof WeaponUnit configuredUnit)) {
            return false;
        }
        current = configuredUnit.getParentWeaponUnit();
        while (current != null) {
            if (current == queryUnit) {
                return true;
            }
            current = current.getParentWeaponUnit();
        }
        return false;
    }

    private static String formatResolvedMountState(AbstractVehicle vehicle, ResolvedMount mount) {
        return "vehicle=" + vehicle.getVehicleId()
                + " entityId=" + vehicle.getId()
                + " partUnit=" + mount.config().partUnitId()
                + " attachPart=" + mount.config().attachPartUnitId()
                + " weapon=" + mount.config().weaponId()
                + " ammoSlot=" + mount.ammoSlot()
                + " syncedAmmo=" + mount.syncedAmmo()
                + " predictedAmmo=" + (mount.predictedAmmo() == null ? "<none>" : mount.predictedAmmo())
                + " visibleAmmo=" + mount.visibleAmmo()
                + " hideMissile=" + mount.shouldHideMissile()
                + " visibleMissileCount=" + mount.visibleMissileCount()
                + " missileBones=" + mount.config().missileBones();
    }

    private static synchronized void appendDebugLog(String message) {
        if (!DEBUG_ENABLED.get() && !message.startsWith("toggle")) {
            return;
        }
        appendLogInternal(message);
    }

    private static synchronized void appendTraceLog(String message) {
        appendLogInternal(message);
    }

    private static void appendLogInternal(String message) {
        try {
            Files.createDirectories(DEBUG_LOG_PATH.getParent());
            String line = "[" + LocalDateTime.now().format(DEBUG_TIME_FORMAT) + "] " + message + System.lineSeparator();
            Files.writeString(DEBUG_LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("[RVP][CustomMountDebug] Failed to append debug log to {}", DEBUG_LOG_PATH, e);
        }
    }

    private record GroupKey(String partUnitId, ResourceLocation weaponId) {}

    private record ResolvedMount(RVP_CustomMountConfig config,
                                 int syncedAmmo,
                                 int visibleAmmo,
                                 @Nullable Integer predictedAmmo,
                                 int ammoSlot,
                                 boolean shouldHideMissile,
                                 int visibleMissileCount,
                                 WeaponUnitData.FiringMode firingMode) {

        /** 兼容旧调用点的便利构造器（单枚导弹模式，shouldHideMissile 与 visibleMissileCount 一致）。 */
        ResolvedMount(RVP_CustomMountConfig config,
                      int syncedAmmo,
                      int visibleAmmo,
                      @Nullable Integer predictedAmmo,
                      int ammoSlot,
                      boolean shouldHideMissile,
                      WeaponUnitData.FiringMode firingMode) {
            this(config, syncedAmmo, visibleAmmo, predictedAmmo, ammoSlot,
                    shouldHideMissile, shouldHideMissile ? 0 : 1, firingMode);
        }
    }

    private record VisibleAmmo(int syncedAmmo, int visibleAmmo, @Nullable Integer predictedAmmo) {}

    private record AttachmentTransform(Matrix4f transform) {}

    private record PredictionKey(int vehicleEntityId, String partUnitId, ResourceLocation weaponId) {}

    private record PredictedAmmo(int ammo, long updateTimeMs) {}

    private record WeaponResolution(WeaponUnit displayWeaponUnit, AbstractVehicleWeapon<?> currentWeapon) {}
}
