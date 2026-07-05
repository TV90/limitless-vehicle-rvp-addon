package org.ywzj.rvp.client.render;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.maydaymemory.mae.basic.Pose;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.ywzj.rvp.client.laser.RVP_LaserWeapons;
import org.ywzj.rvp.config.RVP_CustomMountConfig;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.rvp.mixin.accessor.WeaponUnitAccessor;
import org.ywzj.vehicle.client.render.animation.util.PoseHelper;
import org.ywzj.vehicle.client.resource.ClientAssetsManager;
import org.ywzj.vehicle.client.resource.vehicle.BaseDisplay;
import org.ywzj.vehicle.client.resource.vehicle.VehicleBedrockModel;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;
import org.ywzj.vehicle.vehicle.weapon.VehicleWeaponAgent;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.slf4j.Logger;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.ywzj.vehicle.client.render.animation.util.PoseBlenders.BLENDER;

public final class RVP_CustomMountRenderLogic {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, VehicleBedrockModel> MODEL_CACHE = new ConcurrentHashMap<>();
    private static final Map<PredictionKey, PredictedAmmo> PREDICTED_AMMO = new ConcurrentHashMap<>();
    private static final AtomicBoolean DEBUG_ENABLED = new AtomicBoolean(false);
    private static final Map<String, String> LAST_DEBUG_STATES = new ConcurrentHashMap<>();
    private static final Path DEBUG_LOG_PATH = FMLPaths.GAMEDIR.get().resolve("logs").resolve("custommountdebug.log");
    private static final DateTimeFormatter DEBUG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private RVP_CustomMountRenderLogic() {}

    public static void clearModelCache() {
        MODEL_CACHE.clear();
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
        if (!enabled) {
            PREDICTED_AMMO.clear();
        }
        appendDebugLog("toggle enabled=" + enabled);
    }

    public static void clearDebugLog() {
        LAST_DEBUG_STATES.clear();
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
        int ammoCost = resolution.displayWeaponUnit().getFiringMode() == WeaponUnitData.FiringMode.SALVO
                ? Math.max(1, resolution.displayWeaponUnit().aimContexts().size())
                : 1;
        PredictionKey key = new PredictionKey(
                resolution.displayWeaponUnit().getVehicle().getId(),
                resolution.displayWeaponUnit().getId(),
                resolution.currentWeapon().getData().getWeaponId()
        );
        int syncedAmmo = Math.max(0, resolution.currentWeapon().getRemainAmmo());
        PredictedAmmo currentPrediction = PREDICTED_AMMO.get(key);
        int baseAmmo = currentPrediction == null ? syncedAmmo : Math.min(syncedAmmo, currentPrediction.ammo());
        int predictedAmmo = Math.max(0, baseAmmo - ammoCost);
        PREDICTED_AMMO.put(key, new PredictedAmmo(predictedAmmo, System.currentTimeMillis()));
        if (DEBUG_ENABLED.get()) {
            appendDebugLog("clientFire vehicle=" + resolution.displayWeaponUnit().getVehicle().getVehicleId()
                    + " entityId=" + resolution.displayWeaponUnit().getVehicle().getId()
                    + " partUnit=" + resolution.displayWeaponUnit().getId()
                    + " weapon=" + resolution.currentWeapon().getData().getWeaponId()
                    + " syncedAmmo=" + syncedAmmo
                    + " ammoCost=" + ammoCost
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
        if (DEBUG_ENABLED.get()) {
            noteResolvedMountStates(vehicle, resolvedMounts);
        }
        for (RVP_CustomMountConfig config : configs) {
            ResolvedMount resolved = resolvedMounts.stream()
                    .filter(entry -> entry.config() == config)
                    .findFirst()
                    .orElse(null);
            if (resolved == null) {
                continue;
            }
            VehicleBedrockModel attachmentModel = getAttachmentModel(config.model());
            if (attachmentModel == null) {
                continue;
            }
            AttachmentTransform attachmentTransform = resolveAttachmentTransform(vehicle, vehicleModel, config);
            if (attachmentTransform == null) {
                continue;
            }

            // 计算需要隐藏的骨骼：rack_bones 全显/全隐，missile_bones 逐枚隐藏
            List<String> hiddenRackBones = resolved.shouldHideMissile() ? config.rackBones() : List.of();
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
            applyAttachmentPose(attachmentModel, hiddenRackBones, partiallyHiddenMissiles);
            if (DEBUG_ENABLED.get()) {
                noteRenderPose(vehicle, resolved);
            }
            poseStack.pushPose();
            try {
                if (attachmentTransform.bone() != null) {
                    poseStack.mulPoseMatrix(getFullBoneTransform(attachmentTransform.bone()));
                } else if (attachmentTransform.translation() != null) {
                    Vec3 translate = attachmentTransform.translation();
                    poseStack.translate(translate.x, translate.y, translate.z);
                }
                RVP_CustomMountConfig.Vec3fConfig offset = config.offset();
                poseStack.translate(offset.x(), offset.y(), offset.z());
                RVP_CustomMountConfig.Vec3fConfig rotation = config.rotationDeg();
                poseStack.mulPose(Axis.XP.rotationDegrees(rotation.x()));
                poseStack.mulPose(Axis.YP.rotationDegrees(rotation.y()));
                poseStack.mulPose(Axis.ZP.rotationDegrees(rotation.z()));
                RVP_CustomMountConfig.Vec3fConfig scale = config.scale();
                poseStack.scale(scale.x(), scale.y(), scale.z());
                attachmentModel.renderToBuffer(poseStack, bufferSource, config.texture(), vehicle.isDestroyed() ? 64 : packedLight);
            } finally {
                poseStack.popPose();
                attachmentModel.applyPose(attachmentModel.getBindPose());
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
                    visibleAmmo.predictedAmmo(), 0, false);
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
            if (weaponUnit.getId().equals(resolved.config().partUnitId())
                    && currentWeapon.getData().getWeaponId().equals(resolved.config().weaponId())) {
                return resolved;
            }
        }
        return null;
    }

    @Nullable
    private static AttachmentTransform resolveAttachmentTransform(AbstractVehicle vehicle,
                                                                 VehicleBedrockModel vehicleModel,
                                                                 RVP_CustomMountConfig config) {
        if (!config.attachPartUnitId().isEmpty()) {
            if (!(vehicle.getPartUnit(config.attachPartUnitId()).orElse(null) instanceof WeaponUnit mountUnit)) {
                return null;
            }
            VehicleCubeGroup xTurnGroup = ((WeaponUnitAccessor) mountUnit).getXTurnGroup();
            List<Bolt> bolts = mountUnit.getBolts();
            if (xTurnGroup == null || bolts.isEmpty()) {
                return null;
            }
            Bolt bolt = bolts.get(0);
            Vec3 translate = xTurnGroup.pivotOffset.add(bolt.offset).add(0.0, 0.0, bolt.barrelLength / 2.0);
            return new AttachmentTransform(null, translate);
        }
        BedrockBone attachBone = vehicleModel.getBone(config.attachBone());
        return attachBone == null ? null : new AttachmentTransform(attachBone, null);
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
        AbstractVehicleWeapon<?> currentWeapon = resolveCurrentWeapon(displayWeaponUnit);
        if (currentWeapon == null && displayWeaponUnit.getParentWeaponUnit() != null) {
            displayWeaponUnit = displayWeaponUnit.getParentWeaponUnit();
            currentWeapon = resolveCurrentWeapon(displayWeaponUnit);
        }
        return currentWeapon == null ? null : new WeaponResolution(displayWeaponUnit, currentWeapon);
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
            // ClientAssetsManager clears raw model POJOs after the standard resource reload completes,
            // so runtime lookups may miss even though displays were built successfully.
            VehicleBedrockModel persistedDisplayModel = ClientAssetsManager.INSTANCE.getDecorationDisplay(id)
                    .map(BaseDisplay::getModel)
                    .orElse(null);
            if (persistedDisplayModel != null) {
                return persistedDisplayModel;
            }
            persistedDisplayModel = ClientAssetsManager.INSTANCE.getWeaponDisplay(id)
                    .map(BaseDisplay::getModel)
                    .orElse(null);
            if (persistedDisplayModel != null) {
                return persistedDisplayModel;
            }
            return ClientAssetsManager.INSTANCE.getModel(id)
                    .map(modelPojo -> new VehicleBedrockModel(modelPojo, List.of()))
                    .orElse(null);
        });
    }

    /**
     * 应用挂架模型的骨骼姿态。
     *
     * @param model              挂架模型
     * @param hiddenBones        需要完全隐藏的骨骼列表（全显/全隐模式）
     * @param partiallyHiddenBones 需要逐枚隐藏的导弹骨骼及其可见数量；
     *                            为 null 或空时使用 hiddenBones 的全显/全隐逻辑
     */
    private static void applyAttachmentPose(VehicleBedrockModel model,
                                            List<String> hiddenBones,
                                            @Nullable List<PartiallyHiddenBone> partiallyHiddenBones) {
        if (hiddenBones.isEmpty() && (partiallyHiddenBones == null || partiallyHiddenBones.isEmpty())) {
            model.applyPose(model.getBindPose());
            return;
        }
        PoseHelper helper = new PoseHelper(model);
        for (String boneName : hiddenBones) {
            helper.hideBone(boneName);
        }
        if (partiallyHiddenBones != null) {
            for (PartiallyHiddenBone phb : partiallyHiddenBones) {
                if (!phb.visible()) {
                    helper.hideBone(phb.boneName());
                }
            }
        }
        Pose pose = BLENDER.blend(model.getBindPose(), helper.build());
        model.applyPose(pose);
    }

    /** 单枚导弹骨骼的可见性记录。 */
    private record PartiallyHiddenBone(String boneName, boolean visible) {}

    private static Matrix4f getFullBoneTransform(BedrockBone targetBone) {
        Matrix4f matrix = VehicleBedrockModel.getGlobalTransform(targetBone);
        matrix.scaleLocal(targetBone.xScale, targetBone.yScale, targetBone.zScale);
        matrix.rotateLocal(targetBone.rotation);
        matrix.translateLocal(targetBone.x / 16.0F, targetBone.y / 16.0F, targetBone.z / 16.0F);
        return matrix;
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
                        mount.predictedAmmo(), slot, hideMissile));
            } else {
                // 多枚导弹模式：计算该挂架上可见导弹数量
                int offset = (slot - 1) * missileCount;
                int visibleOnThisPylon = Math.max(0, Math.min(missileCount, mount.visibleAmmo() - offset));
                boolean hideMissile = visibleOnThisPylon <= 0;
                mounts.set(i, new ResolvedMount(mount.config(), mount.syncedAmmo(), mount.visibleAmmo(),
                        mount.predictedAmmo(), slot, hideMissile, visibleOnThisPylon));
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
                                 int visibleMissileCount) {

        /** 兼容旧调用点的便利构造器（单枚导弹模式，shouldHideMissile 与 visibleMissileCount 一致）。 */
        ResolvedMount(RVP_CustomMountConfig config,
                      int syncedAmmo,
                      int visibleAmmo,
                      @Nullable Integer predictedAmmo,
                      int ammoSlot,
                      boolean shouldHideMissile) {
            this(config, syncedAmmo, visibleAmmo, predictedAmmo, ammoSlot,
                    shouldHideMissile, shouldHideMissile ? 0 : 1);
        }
    }

    private record VisibleAmmo(int syncedAmmo, int visibleAmmo, @Nullable Integer predictedAmmo) {}

    private record AttachmentTransform(@Nullable BedrockBone bone, @Nullable Vec3 translation) {}

    private record PredictionKey(int vehicleEntityId, String partUnitId, ResourceLocation weaponId) {}

    private record PredictedAmmo(int ammo, long updateTimeMs) {}

    private record WeaponResolution(WeaponUnit displayWeaponUnit, AbstractVehicleWeapon<?> currentWeapon) {}
}
