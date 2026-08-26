package org.ywzj.rvp.weapon.damage;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CBoneModuleState;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.rvp.vehicle.BoneApsConfig;
import org.ywzj.rvp.vehicle.BoneEcmActiveConfig;
import org.ywzj.rvp.vehicle.BoneEcmPassiveConfig;
import org.ywzj.rvp.vehicle.BoneDircmConfig;
import org.ywzj.rvp.vehicle.BoneJammerConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.util.ResourceScanner;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_VehicleHitboxFactorManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public static final RVP_VehicleHitboxFactorManager INSTANCE = new RVP_VehicleHitboxFactorManager();

    private Map<ResourceLocation, VehicleHitboxConfig> configs = Map.of();
    private Set<ResourceLocation> hidePassengerVehicles = Set.of();

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return ResourceScanner.scanDirectory(resourceManager, "vehicles", GsonUtil.GSON);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler) {
        // 客户端单机时 AddReloadListenerEvent（数据仓库）可能扫不到 rvp 包（VehiclePackLoader 以资源包注册），
        // 空 map 不覆盖，避免清空客户端资源重载 / 服务端同步已填充的配置。
        if (map == null || map.isEmpty()) {
            return;
        }
        Map<ResourceLocation, VehicleHitboxConfig> loaded = new HashMap<>();
        Set<ResourceLocation> hideSet = new HashSet<>();
        map.forEach((vehicleId, json) -> {
            if (!json.isJsonObject()) {
                return;
            }
            JsonObject obj = json.getAsJsonObject();
            if (GsonHelper.getAsBoolean(obj, "hide_passenger", false)) {
                hideSet.add(vehicleId);
            }
            VehicleHitboxConfig cfg = VehicleHitboxConfig.parse(obj);
            if (cfg != null) {
                loaded.put(vehicleId, cfg);
            }
        });
        configs = Map.copyOf(loaded);
        hidePassengerVehicles = Set.copyOf(hideSet);
    }

    /**
     * 供客户端在收到服务端同步的配置包（{@code S2CVehicleRvpConfig}）后填充配置。
     * 专用服务器下客户端不触发 {@link AddReloadListenerEvent}，隐藏乘员（hide_passenger）
     * 依赖该配置，故必须复用同一套解析逻辑。
     */
    public void applyFromJsonMap(Map<ResourceLocation, JsonElement> jsonMap) {
        apply(jsonMap, null, null);
    }

    public boolean isHidePassenger(ResourceLocation vehicleId) {
        return hidePassengerVehicles.contains(vehicleId);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    public float resolveHitboxDamageFactor(AbstractVehicle vehicle, Vec3 segmentStart, Vec3 segmentEnd) {
        return resolveHitboxDamage(vehicle, segmentStart, segmentEnd).factor();
    }

    public float resolveCoreDistanceScaleMultiplier(AbstractVehicle vehicle) {
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null) {
            return 1f;
        }
        float m = cfg.coreDistanceScaleMultiplier;
        if (!Float.isFinite(m) || m < 0f) {
            return 1f;
        }
        return m;
    }

    public HitboxDamageResult resolveHitboxDamage(AbstractVehicle vehicle, Vec3 segmentStart, Vec3 segmentEnd) {
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || !cfg.isEnabled()) {
            return HitboxDamageResult.disabled();
        }
        sanitizeModuleState(vehicle, cfg);
        ResourceLocation structureModelId = cfg.structureModel;
        if (structureModelId == null) {
            return HitboxDamageResult.defaulted(cfg.defaultFactor, null, 0, Double.NaN);
        }
        BedrockModel model = CommonAssetsManager.structureModelManager().getStructureModel(structureModelId).orElse(null);
        if (model == null) {
            return HitboxDamageResult.defaulted(cfg.defaultFactor, structureModelId, 0, Double.NaN);
        }
        return cfg.resolve(model, vehicle, segmentStart, segmentEnd);
    }

    public boolean isEraActive(AbstractVehicle vehicle, @Nullable String boneName) {
        return RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), boneName, BoneModuleType.ERA);
    }

    /** 骨块的某模块是否已失效（供候选穿透判定与叠加骨块多属性查询）。 */
    public boolean isModuleDestroyed(UUID vehicleId, @Nullable String boneName, BoneModuleType type) {
        return RVP_BoneModuleStateTable.isModuleDestroyed(vehicleId, boneName, type);
    }

    /**
     * 返回车辆所有声明了干扰机设备（{@code jammer} 子对象）的骨块配置（骨块名 → 配置）。
     * 设备是否存活（对应 JAMMER 骨块被击毁）由调用方通过
     * {@link RVP_BoneModuleStateTable#isModuleActive} 判定；未配置干扰机的车辆返回 null。
     */
    public @Nullable Map<String, BoneJammerConfig> resolveJammerDevices(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneJammerConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneJammerConfig jammer = entry.getValue() == null ? null : entry.getValue().jammer();
            if (jammer != null) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), jammer);
            }
        }
        return out;
    }

    /**
     * 返回车辆所有声明了主动防护发射器（{@code aps} 子对象）的骨块配置（骨块名 → 配置）。
     * 设备是否存活（对应 APS 骨块被击毁）由调用方通过
     * {@link RVP_BoneModuleStateTable#isModuleActive} 判定；未配置 APS 的车辆返回 null。
     */
    public @Nullable Map<String, BoneApsConfig> resolveApsDevices(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneApsConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneApsConfig aps = entry.getValue() == null ? null : entry.getValue().aps();
            if (aps != null && aps.isEnabled()) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), aps);
            }
        }
        return out;
    }

    /**
     * 解析载具上全部存活的 DIRCM 照射模块（{@code bone_modules} 的 {@code dircm} 子对象）。
     * 返回 {@code Map<骨块名, 配置>}；无配置或全部禁用返回 null。
     * 供 {@code RVP_DircmRuntimeManager} 按骨块名 + {@code RVP_BoneModuleStateTable} 判定通道可用性。
     */
    public @Nullable Map<String, BoneDircmConfig> resolveDircmDevices(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneDircmConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneDircmConfig dircm = entry.getValue() == null ? null : entry.getValue().dircm();
            if (dircm != null && dircm.isEnabled()) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), dircm);
            }
        }
        return out;
    }

    /**
     * 解析载具上全部存活的主动电子战（{@code ecm_active}）骨块配置。
     *
     * <p>返回 {@code Map<骨块名, 配置>}；无配置返回 null。
     * 供 {@code RVP_EcmActiveManager} 判定载具是否具备主动ECM能力
     * （结合 {@code RVP_BoneModuleStateTable} 的骨块存活状态）。</p>
     */
    public @Nullable Map<String, BoneEcmActiveConfig> resolveEcmActiveDevices(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneEcmActiveConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneEcmActiveConfig active = entry.getValue() == null ? null : entry.getValue().ecmActive();
            if (active != null) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), active);
            }
        }
        return out;
    }

    /**
     * 解析载具上全部启用中的被动电子战（{@code ecm_passive}）骨块配置。
     *
     * <p>返回 {@code Map<骨块名, 配置>}；无配置返回 null。
     * 供 {@code RVP_EcmPassiveManager} 判定载具是否具备被动电子战能力
     * （结合 {@code RVP_BoneModuleStateTable} 的骨块存活状态）。</p>
     */
    public @Nullable Map<String, BoneEcmPassiveConfig> resolveEcmDevices(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return null;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return null;
        }
        Map<String, BoneEcmPassiveConfig> out = null;
        for (Map.Entry<String, BoneModuleConfig> entry : cfg.moduleByBoneName.entrySet()) {
            BoneEcmPassiveConfig ecm = entry.getValue() == null ? null : entry.getValue().ecmPassive();
            if (ecm != null && !ecm.bands().isEmpty()) {
                if (out == null) {
                    out = new HashMap<>();
                }
                out.put(entry.getKey(), ecm);
            }
        }
        return out;
    }

    /**
     * 直击消耗骨块上全部"可被弹药击毁"的模块（机制一：OBB 单发命中）。
     *
     * <p>泛化了原 {@code tryTriggerEra}：命中骨块若挂多个模块（如 ERA + JAMMER 叠加），
     * 触发阈值判定通过后一次性全部置失效，各模块独立写状态、独立同步。
     * 仅当其中包含 ERA 模块时播放爆炸粒子/音效（ERA 专属观感）。</p>
     *
     * @param vehicle        目标载具
     * @param result         命中判定结果（必须携带骨块模块信息）
     * @param triggerDamage  触发判定用伤害值（低于 {@code minTriggerDamage} 不消耗）
     * @return 是否消耗了至少一个模块
     */
    public boolean tryDestroyBoneModules(AbstractVehicle vehicle, @Nullable HitboxDamageResult result, float triggerDamage) {
        if (vehicle == null || result == null || result.modules().isEmpty()) {
            return false;
        }
        if (!(vehicle.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        String boneName = result.hitBoneName();
        if (boneName == null || !result.shouldTriggerModules(triggerDamage)) {
            return false;
        }
        UUID vehicleId = vehicle.getUUID();
        boolean destroyedEra = false;
        boolean anyDestroyed = false;
        for (BoneModuleType type : result.modules()) {
            if (RVP_BoneModuleStateTable.destroyModule(vehicleId, boneName, type)) {
                anyDestroyed = true;
                if (type == BoneModuleType.ERA) {
                    destroyedEra = true;
                }
            }
        }
        if (!anyDestroyed) {
            return false;
        }
        syncBoneModuleState(vehicle);
        if (destroyedEra) {
            Vec3 hitPoint = result.hitPoint() != null ? result.hitPoint() : vehicle.getBoundingBox().getCenter();
            float explosionScale = result.explosion() > 0f ? result.explosion() : 1f;
            serverLevel.sendParticles(ParticleTypes.EXPLOSION, hitPoint.x, hitPoint.y, hitPoint.z,
                    1, 0.02, 0.02, 0.02, 0.0);
            serverLevel.sendParticles(ParticleTypes.SMOKE, hitPoint.x, hitPoint.y, hitPoint.z,
                    Math.max(6, Math.round(8f * explosionScale)),
                    0.18 * explosionScale, 0.12 * explosionScale, 0.18 * explosionScale, 0.01);
            serverLevel.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z,
                    SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                    Math.min(2.0f, 0.7f + explosionScale * 0.35f), 1.15f);
        }
        return true;
    }

    public String resolveHitboxDisplayName(AbstractVehicle vehicle, @Nullable String boneName) {
        if (boneName == null || boneName.isBlank()) {
            // 载具未配置 RVP 命中箱骨块（倍率骨骼）时，显示载具名称的翻译 key
            // （如 entity.rvp.t90m → 客户端渲染为 "T-90M 突破3"），取代原 "default"。
            // 非直击爆炸不经过本方法（boneDisplayName 为空串，客户端显示"爆炸"）。
            if (vehicle == null || vehicle.getVehicleId() == null) {
                return "default";
            }
            ResourceLocation id = vehicle.getVehicleId();
            return "entity." + id.getNamespace() + "." + id.getPath();
        }
        if (vehicle == null) {
            return boneName;
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.aliasByBoneName == null || cfg.aliasByBoneName.isEmpty()) {
            return boneName;
        }
        return cfg.aliasByBoneName.getOrDefault(boneName, boneName);
    }

    /** 载具 JSON 顶层 {@code hit_indicator_rvp}（默认 true）：是否对命中该载具显示 RVP 命中提示。 */
    public boolean isHitIndicatorRvpEnabled(AbstractVehicle vehicle) {
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        return cfg == null || cfg.hitIndicatorRvp();
    }

    private void sanitizeModuleState(AbstractVehicle vehicle, VehicleHitboxConfig cfg) {
        if (RVP_BoneModuleStateTable.retainValidBones(vehicle.getUUID(), cfg.moduleByBoneName.keySet())
                && vehicle.level() instanceof ServerLevel) {
            syncBoneModuleState(vehicle);
        }
    }

    private void syncBoneModuleState(AbstractVehicle vehicle) {
        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> vehicle),
                S2CBoneModuleState.create(vehicle, RVP_BoneModuleStateTable.getInactiveModules(vehicle.getUUID()))
        );
    }

    // ─────────────────────────────────────────────────────────────
    // 机制二：按爆炸半径百分比破坏 ERA（HE 高爆弹 + 附近爆炸）
    // ─────────────────────────────────────────────────────────────

    /**
     * 按爆炸半径查阈值表，百分比破坏载具骨块上的"参与爆炸破坏"的模块（默认仅 ERA；
     * 叠加骨块如 ERA+JAMMER 只破坏 ERA，保留 JAMMER 功能）。
     *
     * @param vehicle          目标载具
     * @param explosionRadius  爆炸配置半径
     * @param hitPos           直击命中位（非直击传 null）
     * @param isDirectHit      true = 直击（有至少1块保底），false = 附近爆炸（无保底/距离衰减）
     */
    public static void destroyModulesByExplosionRadius(
            AbstractVehicle vehicle,
            float explosionRadius,
            @Nullable Vec3 hitPos,
            boolean isDirectHit
    ) {
        if (vehicle == null || vehicle.level().isClientSide()) {
            return;
        }
        VehicleHitboxConfig cfg = INSTANCE.configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.moduleByBoneName == null || cfg.moduleByBoneName.isEmpty()) {
            return;
        }
        UUID vehicleId = vehicle.getUUID();
        // 收集"参与爆炸破坏"的候选骨块（骨块 → 其中参与爆炸破坏且仍激活的模块）
        List<String> activeBones = new ArrayList<>();
        for (String boneName : cfg.moduleByBoneName.keySet()) {
            boolean anyBlastActive = false;
            for (BoneModuleType type : cfg.moduleByBoneName.get(boneName).modules()) {
                if (type.participatesInBlastDestruction()
                        && RVP_BoneModuleStateTable.isModuleActive(vehicleId, boneName, type)) {
                    anyBlastActive = true;
                    break;
                }
            }
            if (anyBlastActive) {
                activeBones.add(boneName);
            }
        }
        if (activeBones.isEmpty()) {
            return;
        }

        int totalActive = activeBones.size();
        int destroyCount = calcDestroyCount(explosionRadius, totalActive, isDirectHit);
        if (destroyCount <= 0) {
            return;
        }

        // 按位置排序或随机
        List<String> sorted;
        if (isDirectHit && hitPos != null) {
            sorted = sortBonesByDistance(cfg, vehicle, activeBones, hitPos);
        } else {
            sorted = new ArrayList<>(activeBones);
            java.util.Collections.shuffle(sorted);
        }

        if (!(vehicle.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        int destroyed = 0;
        for (int i = 0; i < Math.min(destroyCount, sorted.size()); i++) {
            String boneName = sorted.get(i);
            BoneModuleConfig eCfg = cfg.moduleByBoneName.get(boneName);
            boolean destroyedAny = false;
            for (BoneModuleType type : cfg.moduleByBoneName.get(boneName).modules()) {
                if (type.participatesInBlastDestruction()
                        && RVP_BoneModuleStateTable.destroyModule(vehicleId, boneName, type)) {
                    destroyedAny = true;
                }
            }
            if (destroyedAny) {
                float explosionScale = (eCfg != null && eCfg.explosion() > 0f) ? eCfg.explosion() : 1f;
                spawnEraEffect(serverLevel, vehicle, boneName, explosionScale);
                destroyed++;
            }
        }
        if (destroyed > 0) {
            INSTANCE.syncBoneModuleState(vehicle);
        }
    }

    /** 查阈值表决定破坏数量。 */
    private static int calcDestroyCount(float radius, int totalActive, boolean isDirectHit) {
        float percentage;
        if (radius <= 5f) {
            percentage = 0f;
        } else if (radius <= 8f) {
            percentage = 0.10f;
        } else if (radius <= 12f) {
            percentage = 0.25f;
        } else if (radius <= 18f) {
            percentage = 0.50f;
        } else {
            percentage = 1.0f; // 全毁
        }
        if (percentage <= 0f) {
            return 0;
        }
        int raw = (int) Math.floor(totalActive * percentage);
        if (isDirectHit && raw < 1) {
            raw = 1; // 直击至少 1 块保底
        }
        return Math.min(raw, totalActive);
    }

    /** 按每个 ERA bone 的 OBB 中心到 hitPos 的距离从小到大排序。 */
    private static List<String> sortBonesByDistance(
            VehicleHitboxConfig cfg, AbstractVehicle vehicle,
            List<String> boneNames, Vec3 hitPos
    ) {
        ResourceLocation structureId = cfg.structureModel;
        if (structureId == null) {
            return new ArrayList<>(boneNames);
        }
        BedrockModel model = CommonAssetsManager.structureModelManager().getStructureModel(structureId).orElse(null);
        if (model == null) {
            return new ArrayList<>(boneNames);
        }
        Map<String, BedrockBone> boneMap = model.getBoneMap();
        HashSet<BedrockBone> namedBones = new HashSet<>(boneMap.values());

        Map<String, Double> distances = new HashMap<>();
        for (String name : boneNames) {
            List<ResolvedObb> resolvedObbs = resolveBoneObbs(vehicle, boneMap, namedBones, name);
            if (resolvedObbs.isEmpty()) {
                distances.put(name, Double.MAX_VALUE);
                continue;
            }
            double minDist = Double.MAX_VALUE;
            for (ResolvedObb resolvedObb : resolvedObbs) {
                Vector3f center = resolvedObb.obb().center();
                double d = new Vec3(center).distanceToSqr(hitPos);
                if (d < minDist) {
                    minDist = d;
                }
            }
            distances.put(name, minDist);
        }

        List<String> result = new ArrayList<>(boneNames);
        result.sort(Comparator.comparingDouble(distances::get));
        return result;
    }

    public String dumpResolveDebug(AbstractVehicle vehicle) {
        StringBuilder sb = new StringBuilder();
        sb.append("vehicleId=").append(vehicle == null ? "<null>" : vehicle.getVehicleId()).append('\n');
        if (vehicle == null) {
            return sb.toString();
        }
        VehicleHitboxConfig cfg = configs.get(vehicle.getVehicleId());
        if (cfg == null) {
            sb.append("config=<missing>\n");
            return sb.toString();
        }
        sb.append("structureModel=").append(cfg.structureModel).append('\n');
        sb.append("defaultFactor=").append(cfg.defaultFactor).append('\n');
        sb.append("loadedFactorKeys=").append(new ArrayList<>(cfg.factorByBoneName.keySet())).append('\n');
        for (var entry : cfg.factorByBoneName.entrySet()) {
            sb.append("factor[").append(entry.getKey()).append("]=").append(entry.getValue()).append('\n');
        }
        for (var entry : cfg.aliasByBoneName.entrySet()) {
            sb.append("alias[").append(entry.getKey()).append("]=").append(entry.getValue()).append('\n');
        }
        BedrockModel model = cfg.structureModel == null
                ? null
                : CommonAssetsManager.structureModelManager().getStructureModel(cfg.structureModel).orElse(null);
        if (model == null) {
            sb.append("model=<missing>\n");
            return sb.toString();
        }
        Map<String, BedrockBone> boneMap = model.getBoneMap();
        HashSet<BedrockBone> namedBones = new HashSet<>(boneMap.values());
        Set<String> allConfigBones = new LinkedHashSet<>();
        allConfigBones.addAll(cfg.factorByBoneName.keySet());
        allConfigBones.addAll(cfg.moduleByBoneName.keySet());
        for (String boneName : allConfigBones) {
            Optional<PartUnit<?>> partUnitOptional = vehicle.getPartUnit(boneName);
            int partUnitObbCount = partUnitOptional.map(partUnit -> partUnit.getOBBs().size()).orElse(0);
            BedrockBone bone = boneMap.get(boneName);
            int boneObbCount = bone == null ? 0 : OBB.getOBBsFromBone(bone, vehicle, namedBones).size();
            List<ResolvedObb> resolvedObbs = resolveBoneObbs(vehicle, boneMap, namedBones, boneName);
            String source = resolvedObbs.isEmpty() ? "missing" : resolvedObbs.get(0).source();
            sb.append("bone=").append(boneName)
                    .append(" source=").append(source)
                    .append(" alias=").append(resolveHitboxDisplayName(vehicle, boneName))
                    .append(" resolvedObbs=").append(resolvedObbs.size())
                    .append(" partUnitPresent=").append(partUnitOptional.isPresent())
                    .append(" partUnitObbs=").append(partUnitObbCount)
                    .append(" boneExists=").append(bone != null)
                    .append(" boneObbs=").append(boneObbCount)
                    .append('\n');
        }
        return sb.toString();
    }

    private static List<ResolvedObb> resolveBoneObbs(
            AbstractVehicle vehicle,
            Map<String, BedrockBone> boneMap,
            HashSet<BedrockBone> namedBones,
            String boneName
    ) {
        Optional<PartUnit<?>> partUnitOptional = vehicle.getPartUnit(boneName);
        if (partUnitOptional.isPresent()) {
            List<OBB> partUnitObbs = partUnitOptional.get().getOBBs();
            if (partUnitObbs != null && !partUnitObbs.isEmpty()) {
                List<ResolvedObb> resolved = new ArrayList<>(partUnitObbs.size());
                for (OBB obb : partUnitObbs) {
                    resolved.add(new ResolvedObb(obb, "part_unit"));
                }
                return resolved;
            }
        }
        BedrockBone bone = boneMap.get(boneName);
        if (bone == null) {
            return List.of();
        }
        List<ResolvedObb> resolved = new ArrayList<>();
        for (OBB.CubeOBB cubeObb : OBB.getOBBsFromBone(bone, vehicle, namedBones)) {
            resolved.add(new ResolvedObb(cubeObb.obb(), "bone_fallback"));
        }
        return resolved;
    }

    /** 播放松散 ERA 特效。 */
    private static void spawnEraEffect(ServerLevel serverLevel, AbstractVehicle vehicle,
                                        String boneName, float explosionScale) {
        Vec3 pos = vehicle.getBoundingBox().getCenter();
        serverLevel.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z,
                1, 0.02, 0.02, 0.02, 0.0);
        serverLevel.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y, pos.z,
                Math.max(6, Math.round(8f * explosionScale)),
                0.18 * explosionScale, 0.12 * explosionScale, 0.18 * explosionScale, 0.01);
        serverLevel.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                Math.min(2.0f, 0.7f + explosionScale * 0.35f), 1.15f);
    }

    private static String fmt(float v) {
        if (!Float.isFinite(v)) {
            return "NaN";
        }
        return String.format("%.2f", v);
    }

    public record HitboxDamageResult(
            boolean enabled,
            float factor,
            float defaultFactor,
            @Nullable String hitBoneName,
            @Nullable ResourceLocation structureModel,
            int missingConfigBones,
            double hitDistance,
            @Nullable Vec3 hitPoint,
            Set<BoneModuleType> modules,
            Set<BoneModuleType> destroyedModules,
            float minTriggerDamage,
            float explosion
    ) {
        public static HitboxDamageResult disabled() {
            return new HitboxDamageResult(false, 1f, 1f, null, null, 0, Double.NaN,
                    null, Set.of(), Set.of(), Float.POSITIVE_INFINITY, 0f);
        }

        public static HitboxDamageResult defaulted(float defaultFactor, @Nullable ResourceLocation structureModel,
                                                   int missingBones, double hitDistance) {
            float def = Float.isFinite(defaultFactor) ? defaultFactor : 1f;
            return new HitboxDamageResult(true, Math.max(0f, def), def, null, structureModel,
                    missingBones, hitDistance, null, Set.of(), Set.of(), Float.POSITIVE_INFINITY, 0f);
        }

        /** 命中骨块是否挂有模块（ERA/TRACK/JAMMER 等）。 */
        public boolean hasModules() {
            return !modules.isEmpty();
        }

        /** 命中骨块是否仍存在"可被弹药击毁"的激活模块。 */
        public boolean hasActiveModules() {
            return modules.size() > destroyedModules.size();
        }

        /**
         * 骨块是否已失去对后续弹药的阻挡（ERA 模块失效 → 穿透）。
         * 由 resolve 候选循环用 passThrough() 判定，其余模块失效不穿透。
         */
        public boolean passThrough() {
            return destroyedModules.contains(BoneModuleType.ERA);
        }

        /** 兼容旧语义：该骨块是否包含 ERA 模块。 */
        public boolean era() {
            return modules.contains(BoneModuleType.ERA);
        }

        /** 触发阈值判定：有可击毁的激活模块且伤害超过阈值。 */
        public boolean shouldTriggerModules(float triggerDamage) {
            return hasActiveModules() && Float.isFinite(triggerDamage) && triggerDamage > minTriggerDamage;
        }
    }

    private record VehicleHitboxConfig(
            @Nullable ResourceLocation structureModel,
            float defaultFactor,
            Map<String, Float> factorByBoneName,
            Map<String, BoneModuleConfig> moduleByBoneName,
            Map<String, String> aliasByBoneName,
            float coreDistanceScaleMultiplier,
            boolean hitIndicatorRvp
    ) {
        boolean isEnabled() {
            return defaultFactor != 1f
                    || (factorByBoneName != null && !factorByBoneName.isEmpty())
                    || (aliasByBoneName != null && !aliasByBoneName.isEmpty())
                    || (moduleByBoneName != null && !moduleByBoneName.isEmpty());
        }

        HitboxDamageResult resolve(BedrockModel model, AbstractVehicle vehicle, Vec3 segmentStart, Vec3 segmentEnd) {
            if ((factorByBoneName == null || factorByBoneName.isEmpty())
                    && (moduleByBoneName == null || moduleByBoneName.isEmpty())) {
                return HitboxDamageResult.defaulted(defaultFactor, structureModel, 0, Double.NaN);
            }
            if (!RVP_PhysicsOnlyCollisionHelper.getPhysicsOnlyCubes(vehicle).isEmpty()
                    && RVP_PhysicsOnlyCollisionHelper.closestNonPhysicsOnlyHitPosition(vehicle, segmentStart, segmentEnd) == null
                    && RVP_PhysicsOnlyCollisionHelper.closestPhysicsOnlyHitPosition(vehicle, segmentStart, segmentEnd) != null) {
                return HitboxDamageResult.disabled();
            }
            Vector3f from = segmentStart.toVector3f();
            Vector3f to = segmentEnd.toVector3f();
            Map<String, BedrockBone> boneMap = model.getBoneMap();
            HashSet<BedrockBone> namedBones = new HashSet<>(boneMap.values());
            int missingBones = 0;

            List<HitCandidate> candidates = new ArrayList<>();
            Set<String> allConfigBones = new LinkedHashSet<>();
            allConfigBones.addAll(factorByBoneName.keySet());
            allConfigBones.addAll(moduleByBoneName.keySet());

            for (String boneName : allConfigBones) {
                BoneModuleConfig moduleConfig = moduleByBoneName.get(boneName);
                float factor = moduleConfig != null
                        ? moduleConfig.damageFactor()
                        : factorByBoneName.getOrDefault(boneName, defaultFactor);
                List<ResolvedObb> resolvedObbs = resolveBoneObbs(vehicle, boneMap, namedBones, boneName);
                if (resolvedObbs.isEmpty()) {
                    if (boneMap.get(boneName) == null && vehicle.getPartUnit(boneName).isEmpty()) {
                        missingBones++;
                    }
                    continue;
                }
                for (ResolvedObb resolvedObb : resolvedObbs) {
                    Vector3f hit = resolvedObb.obb().clip(from, to).orElse(null);
                    if (hit == null) {
                        continue;
                    }
                    Vec3 hitPoint = new Vec3(hit);
                    double distance = hitPoint.distanceTo(segmentStart);
                    candidates.add(new HitCandidate(
                            distance,
                            hitPoint,
                            boneName,
                            factor,
                            moduleConfig,
                            vehicle.getUUID()
                    ));
                }
            }

            if (candidates.isEmpty()) {
                return HitboxDamageResult.defaulted(defaultFactor, structureModel, missingBones, Double.NaN);
            }
            candidates.sort(Comparator.comparingDouble(HitCandidate::distance));
            for (HitCandidate candidate : candidates) {
                if (candidate.passThrough()) {
                    // ERA 模块失效 → 该骨块不再阻拦弹药（穿透）；其余模块失效不穿透
                    continue;
                }
                float factor = Float.isFinite(candidate.factor()) ? candidate.factor() : defaultFactor;
                BoneModuleConfig moduleConfig = candidate.moduleConfig();
                if (moduleConfig != null && moduleConfig.hasModules()) {
                    Set<BoneModuleType> destroyed = candidate.destroyedModules();
                    return new HitboxDamageResult(
                            true,
                            Math.max(0f, factor),
                            defaultFactor,
                            candidate.boneName(),
                            structureModel,
                            missingBones,
                            candidate.distance(),
                            candidate.hitPoint(),
                            moduleConfig.modules(),
                            destroyed,
                            moduleConfig.minTriggerDamage(),
                            moduleConfig.explosion()
                    );
                }
                return new HitboxDamageResult(
                        true,
                        Math.max(0f, factor),
                        defaultFactor,
                        candidate.boneName(),
                        structureModel,
                        missingBones,
                        candidate.distance(),
                        candidate.hitPoint(),
                        Set.of(),
                        Set.of(),
                        Float.POSITIVE_INFINITY,
                        0f
                );
            }
            return HitboxDamageResult.defaulted(defaultFactor, structureModel, missingBones, Double.NaN);
        }

        static @Nullable VehicleHitboxConfig parse(JsonObject obj) {
            ResourceLocation structure = parseId(GsonHelper.getAsString(obj, "structure_model", null));
            float def = GsonHelper.getAsFloat(obj, "hitbox_damage_factor_default", 1f);
            Map<String, Float> map = parseFactorMap(obj.get("hitbox_damage_factor"));
            // 新配置 bone_modules 优先；旧配置 hitbox_era 兼容为仅 ERA 模块，两者按骨块合并
            Map<String, BoneModuleConfig> moduleMap = parseBoneModuleMap(obj.get("bone_modules"));
            Map<String, BoneModuleConfig> eraCompatMap = parseEraCompatMap(obj.get("hitbox_era"));
            if (eraCompatMap != null) {
                if (moduleMap == null) {
                    moduleMap = new HashMap<>();
                }
                moduleMap.putAll(eraCompatMap);
            }
            Map<String, String> aliasMap = parseAliasMap(obj.get("hitbox_display_name"));
            float coreM = GsonHelper.getAsFloat(obj, "core_distance_scale_multiplier", 1f);
            boolean hitIndicatorRvp = GsonHelper.getAsBoolean(obj, "hit_indicator_rvp", true);
            // 顶层无骨骼的 ecm_active（无骨骼ECM）：直接挂到虚拟骨骼 __vehicle__，始终存活
            BoneEcmActiveConfig vehicleEcmActive = BoneEcmActiveConfig.parse(obj.get("ecm_active"));
            if (vehicleEcmActive != null) {
                if (moduleMap == null) {
                    moduleMap = new HashMap<>();
                }
                java.util.Set<BoneModuleType> modules = java.util.EnumSet.of(BoneModuleType.ECM_ACTIVE);
                BoneModuleConfig synthetic = new BoneModuleConfig(1f, Float.POSITIVE_INFINITY, 0f, modules,
                        null, null, null, null, vehicleEcmActive);
                moduleMap.put("__vehicle__", synthetic);
            }
            if ((map == null || map.isEmpty())
                    && (moduleMap == null || moduleMap.isEmpty())
                    && (aliasMap == null || aliasMap.isEmpty())
                    && def == 1f
                    && coreM == 1f
                    && hitIndicatorRvp) {
                return null;
            }
            return new VehicleHitboxConfig(
                    structure,
                    def,
                    map == null ? Map.of() : Map.copyOf(map),
                    moduleMap == null ? Map.of() : Map.copyOf(moduleMap),
                    aliasMap == null ? Map.of() : Map.copyOf(aliasMap),
                    coreM,
                    hitIndicatorRvp
            );
        }

        private static @Nullable ResourceLocation parseId(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String s = raw.trim();
            if (!s.contains(":")) {
                s = RVP_MOD.MOD_ID + ":" + s;
            }
            return ResourceLocation.tryParse(s);
        }

        private static @Nullable Map<String, Float> parseFactorMap(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            Map<String, Float> map = new HashMap<>();
            for (var entry : obj.entrySet()) {
                String key = normalizeBone(entry.getKey());
                if (key == null) {
                    continue;
                }
                Optional<Float> val = tryFloat(entry.getValue());
                if (val.isPresent()) {
                    map.put(key, val.get());
                }
            }
            return map;
        }

        private static @Nullable Map<String, String> parseAliasMap(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            Map<String, String> map = new HashMap<>();
            for (var entry : obj.entrySet()) {
                String key = normalizeBone(entry.getKey());
                if (key == null) {
                    continue;
                }
                if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String alias = entry.getValue().getAsString();
                if (alias == null) {
                    continue;
                }
                alias = alias.trim();
                if (!alias.isEmpty()) {
                    map.put(key, alias);
                }
            }
            return map;
        }

        /** 解析新配置 {@code bone_modules}：每个骨块可挂多个模块（era/track/jammer 叠加）。 */
        private static @Nullable Map<String, BoneModuleConfig> parseBoneModuleMap(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            Map<String, BoneModuleConfig> map = new HashMap<>();
            for (var entry : obj.entrySet()) {
                String key = normalizeBone(entry.getKey());
                if (key == null) {
                    continue;
                }
                BoneModuleConfig config = BoneModuleConfig.parse(entry.getValue());
                if (config != null && config.hasModules()) {
                    map.put(key, config);
                }
            }
            return map.isEmpty() ? null : map;
        }

        /** 兼容旧配置 {@code hitbox_era}：映射为仅 ERA 模块的骨块。 */
        private static @Nullable Map<String, BoneModuleConfig> parseEraCompatMap(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            Map<String, BoneModuleConfig> map = new HashMap<>();
            for (var entry : obj.entrySet()) {
                String key = normalizeBone(entry.getKey());
                if (key == null) {
                    continue;
                }
                BoneModuleConfig config = BoneModuleConfig.parseEraCompat(entry.getValue());
                if (config != null) {
                    map.put(key, config);
                }
            }
            return map.isEmpty() ? null : map;
        }

        private static @Nullable String normalizeBone(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        private static Optional<Float> tryFloat(@Nullable JsonElement element) {
            if (element == null) {
                return Optional.empty();
            }
            try {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                    return Optional.of(element.getAsFloat());
                }
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    String s = element.getAsString();
                    if (s == null || s.isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(Float.parseFloat(s.trim()));
                }
            } catch (Exception ignored) {
            }
            return Optional.empty();
        }
    }

    private record HitCandidate(
            double distance,
            Vec3 hitPoint,
            String boneName,
            float factor,
            @Nullable BoneModuleConfig moduleConfig,
            UUID vehicleId
    ) {
        /** 骨块是否已失去对后续弹药的阻挡（ERA 模块失效 → 穿透）。 */
        boolean passThrough() {
            return moduleConfig != null
                    && moduleConfig.modules().contains(BoneModuleType.ERA)
                    && INSTANCE.isModuleDestroyed(vehicleId, boneName, BoneModuleType.ERA);
        }

        /** 该骨块已失效的模块集合（用于结果携带，供 tryDestroyBoneModules 判定剩余激活模块）。 */
        Set<BoneModuleType> destroyedModules() {
            if (moduleConfig == null) {
                return Set.of();
            }
            Set<BoneModuleType> destroyed = java.util.EnumSet.noneOf(BoneModuleType.class);
            for (BoneModuleType type : moduleConfig.modules()) {
                if (INSTANCE.isModuleDestroyed(vehicleId, boneName, type)) {
                    destroyed.add(type);
                }
            }
            return destroyed;
        }
    }

    /**
     * 单块骨块的模块配置：伤害倍率 + 触发阈值 + ERA 特效 + 模块集合。
     * 一个骨块可挂多个模块（如 {@code ["era","jammer"]} 叠加），各模块独立失效。
     */
    private record BoneModuleConfig(
            float damageFactor,
            float minTriggerDamage,
            float explosion,
            Set<BoneModuleType> modules,
            @Nullable BoneJammerConfig jammer,
            @Nullable BoneApsConfig aps,
            @Nullable BoneDircmConfig dircm,
            @Nullable BoneEcmPassiveConfig ecmPassive,
            @Nullable BoneEcmActiveConfig ecmActive
    ) {
        boolean hasModules() {
            return modules != null && !modules.isEmpty();
        }

        /** 新配置 {@code bone_modules} 条目：显式 modules 数组，缺省视为 [ERA]。 */
        static @Nullable BoneModuleConfig parse(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            float damageFactor = GsonHelper.getAsFloat(obj, "damage_factor", 1f);
            float minTriggerDamage = parseMinTriggerDamage(obj);
            float explosion = GsonHelper.getAsFloat(obj, "explosion", 0f);
            Set<BoneModuleType> modules = parseModules(obj.get("modules"));
            if (modules == null || modules.isEmpty()) {
                modules = java.util.EnumSet.noneOf(BoneModuleType.class);
                modules.add(BoneModuleType.ERA);
            }
            if (!Float.isFinite(minTriggerDamage) || minTriggerDamage < 0f) {
                minTriggerDamage = Float.POSITIVE_INFINITY;
            }
            if (!Float.isFinite(explosion) || explosion < 0f) {
                explosion = 0f;
            }
            return new BoneModuleConfig(Math.max(0f, damageFactor), minTriggerDamage, explosion, modules,
                    BoneJammerConfig.parse(obj.get("jammer")), BoneApsConfig.parse(obj.get("aps")),
                    BoneDircmConfig.parse(obj.get("dircm")),
                    BoneEcmPassiveConfig.parse(obj.get("ecm_passive")),
                    BoneEcmActiveConfig.parse(obj.get("ecm_active")));
        }

        /** 兼容旧配置 {@code hitbox_era} 条目：始终仅 ERA 模块。 */
        static @Nullable BoneModuleConfig parseEraCompat(@Nullable JsonElement element) {
            if (element == null) {
                return null;
            }
            if (element.isJsonPrimitive()) {
                Optional<Float> factor = VehicleHitboxConfig.tryFloat(element);
                return factor.map(value -> {
                    Set<BoneModuleType> modules = java.util.EnumSet.noneOf(BoneModuleType.class);
                    modules.add(BoneModuleType.ERA);
                    return new BoneModuleConfig(Math.max(0f, value), Float.POSITIVE_INFINITY, 0f, modules, null, null, null, null, null);
                }).orElse(null);
            }
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            float damageFactor = GsonHelper.getAsFloat(obj, "damage_factor", 1f);
            float minTriggerDamage = parseMinTriggerDamage(obj);
            float explosion = GsonHelper.getAsFloat(obj, "explosion", 0f);
            if (!Float.isFinite(minTriggerDamage) || minTriggerDamage < 0f) {
                minTriggerDamage = Float.POSITIVE_INFINITY;
            }
            if (!Float.isFinite(explosion) || explosion < 0f) {
                explosion = 0f;
            }
            Set<BoneModuleType> modules = java.util.EnumSet.noneOf(BoneModuleType.class);
            modules.add(BoneModuleType.ERA);
            return new BoneModuleConfig(Math.max(0f, damageFactor), minTriggerDamage, explosion, modules, null, null, null, null, null);
        }

        /** 通用触发阈值：优先 {@code min_damage}（新通用字段），回退 {@code min_trigger_damage}（旧 ERA 字段）。 */
        private static float parseMinTriggerDamage(JsonObject obj) {
            if (obj.has("min_damage")) {
                return GsonHelper.getAsFloat(obj, "min_damage", Float.POSITIVE_INFINITY);
            }
            return GsonHelper.getAsFloat(obj, "min_trigger_damage", Float.POSITIVE_INFINITY);
        }

        private static @Nullable Set<BoneModuleType> parseModules(@Nullable JsonElement element) {
            if (element == null || !element.isJsonArray()) {
                return null;
            }
            Set<BoneModuleType> modules = java.util.EnumSet.noneOf(BoneModuleType.class);
            for (JsonElement item : element.getAsJsonArray()) {
                if (item == null || !item.isJsonPrimitive()) {
                    continue;
                }
                BoneModuleType type = BoneModuleType.byName(item.getAsString());
                if (type != null) {
                    modules.add(type);
                }
            }
            return modules;
        }
    }

    private static final class ResolvedObb {
        private final OBB obb;
        private final String source;

        private ResolvedObb(OBB obb, String source) {
            this.obb = obb;
            this.source = source;
        }

        private OBB obb() {
            return obb;
        }

        private String source() {
            return source;
        }
    }
}
