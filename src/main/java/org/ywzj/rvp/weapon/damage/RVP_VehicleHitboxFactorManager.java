package org.ywzj.rvp.weapon.damage;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.ext.RVPEraStateAccess;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CVehicleEraState;
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
    private final Map<UUID, Long> lastDebugAtMsByPlayer = new HashMap<>();

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return ResourceScanner.scanDirectory(resourceManager, "vehicles", GsonUtil.GSON);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler) {
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
        sanitizeEraState(vehicle, cfg);
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
        RVPEraStateAccess access = eraAccess(vehicle);
        return access == null || access.rvp_isEraActive(boneName);
    }

    public boolean tryTriggerEra(AbstractVehicle vehicle, @Nullable HitboxDamageResult result, float triggerDamage) {
        if (vehicle == null || result == null || !result.era()) {
            return false;
        }
        if (!(vehicle.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        String boneName = result.hitBoneName();
        if (boneName == null || !result.shouldTriggerEra(triggerDamage)) {
            return false;
        }
        RVPEraStateAccess access = eraAccess(vehicle);
        if (access == null || !access.rvp$consumeEra(boneName)) {
            return false;
        }
        syncEraState(vehicle, access);
        Vec3 hitPoint = result.hitPoint() != null ? result.hitPoint() : vehicle.getBoundingBox().getCenter();
        float explosionScale = result.eraExplosion() > 0f ? result.eraExplosion() : 1f;
        serverLevel.sendParticles(ParticleTypes.EXPLOSION, hitPoint.x, hitPoint.y, hitPoint.z,
                1, 0.02, 0.02, 0.02, 0.0);
        serverLevel.sendParticles(ParticleTypes.SMOKE, hitPoint.x, hitPoint.y, hitPoint.z,
                Math.max(6, Math.round(8f * explosionScale)),
                0.18 * explosionScale, 0.12 * explosionScale, 0.18 * explosionScale, 0.01);
        serverLevel.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z,
                SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                Math.min(2.0f, 0.7f + explosionScale * 0.35f), 1.15f);
        return true;
    }

    public void maybeSendHitboxDebug(
            Player player,
            AbstractVehicle vehicle,
            float damageBefore,
            float damageAfter,
            HitboxDamageResult result,
            float coreFalloffScale,
            float coreFalloffMultiplier
    ) {
        if (player == null || vehicle == null || result == null || !result.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUUID();
        Long last = lastDebugAtMsByPlayer.get(id);
        if (last != null && now - last < 250) {
            return;
        }
        lastDebugAtMsByPlayer.put(id, now);

        String bone = result.hitBoneName() == null ? "default" : result.hitBoneName();
        if (result.era()) {
            bone += " ERA";
        }
        String coreInfo = "";
        if (Float.isFinite(coreFalloffScale) && Float.isFinite(coreFalloffMultiplier) && coreFalloffMultiplier != 1f) {
            coreInfo = " core=" + fmt(coreFalloffScale) + " m=" + fmt(coreFalloffMultiplier);
        }
        Component msg = Component.literal(
                "HBX " + bone
                        + " x" + fmt(result.factor())
                        + " (" + fmt(damageBefore) + " -> " + fmt(damageAfter) + ")"
                        + coreInfo
                        + (result.missingConfigBones() > 0 ? " missing=" + result.missingConfigBones() : "")
        );
        player.displayClientMessage(msg, true);
    }

    private void sanitizeEraState(AbstractVehicle vehicle, VehicleHitboxConfig cfg) {
        RVPEraStateAccess access = eraAccess(vehicle);
        if (access == null) {
            return;
        }
        if (access.rvp$retainEraBones(cfg.eraByBoneName.keySet()) && vehicle.level() instanceof ServerLevel) {
            syncEraState(vehicle, access);
        }
    }

    private void syncEraState(AbstractVehicle vehicle, RVPEraStateAccess access) {
        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> vehicle),
                S2CVehicleEraState.create(vehicle, access.rvp$getInactiveEraBones())
        );
    }

    // ─────────────────────────────────────────────────────────────
    // 机制二：按爆炸半径百分比破坏 ERA（HE 高爆弹 + 附近爆炸）
    // ─────────────────────────────────────────────────────────────

    /**
     * 按爆炸半径查阈值表，百分比破坏载具的 ERA。
     *
     * @param vehicle          目标载具
     * @param explosionRadius  爆炸配置半径
     * @param hitPos           直击命中位（非直击传 null）
     * @param isDirectHit      true = 直击（有至少1块保底），false = 附近爆炸（无保底/距离衰减）
     */
    public static void destroyEraByExplosionRadius(
            AbstractVehicle vehicle,
            float explosionRadius,
            @Nullable Vec3 hitPos,
            boolean isDirectHit
    ) {
        if (vehicle == null || vehicle.level().isClientSide()) {
            return;
        }
        VehicleHitboxConfig cfg = INSTANCE.configs.get(vehicle.getVehicleId());
        if (cfg == null || cfg.eraByBoneName == null || cfg.eraByBoneName.isEmpty()) {
            return;
        }
        if (!(vehicle instanceof RVPEraStateAccess access)) {
            return;
        }
        // 过滤活跃的 ERA bone
        List<String> activeBones = new ArrayList<>();
        for (String boneName : cfg.eraByBoneName.keySet()) {
            if (access.rvp_isEraActive(boneName)) {
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
            if (access.rvp$consumeEra(boneName)) {
                EraConfig eCfg = cfg.eraByBoneName.get(boneName);
                float explosionScale = (eCfg != null && eCfg.explosion() > 0f) ? eCfg.explosion() : 1f;
                spawnEraEffect(serverLevel, vehicle, boneName, explosionScale);
                destroyed++;
            }
        }
        if (destroyed > 0) {
            INSTANCE.syncEraState(vehicle, access);
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
        allConfigBones.addAll(cfg.eraByBoneName.keySet());
        for (String boneName : allConfigBones) {
            Optional<PartUnit<?>> partUnitOptional = vehicle.getPartUnit(boneName);
            int partUnitObbCount = partUnitOptional.map(partUnit -> partUnit.getOBBs().size()).orElse(0);
            BedrockBone bone = boneMap.get(boneName);
            int boneObbCount = bone == null ? 0 : OBB.getOBBsFromBone(bone, vehicle, namedBones).size();
            List<ResolvedObb> resolvedObbs = resolveBoneObbs(vehicle, boneMap, namedBones, boneName);
            String source = resolvedObbs.isEmpty() ? "missing" : resolvedObbs.get(0).source();
            sb.append("bone=").append(boneName)
                    .append(" source=").append(source)
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

    private static @Nullable RVPEraStateAccess eraAccess(AbstractVehicle vehicle) {
        return vehicle instanceof RVPEraStateAccess access ? access : null;
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
            boolean era,
            float eraMinTriggerDamage,
            float eraExplosion
    ) {
        public static HitboxDamageResult disabled() {
            return new HitboxDamageResult(false, 1f, 1f, null, null, 0, Double.NaN,
                    null, false, Float.POSITIVE_INFINITY, 0f);
        }

        public static HitboxDamageResult defaulted(float defaultFactor, @Nullable ResourceLocation structureModel,
                                                   int missingBones, double hitDistance) {
            float def = Float.isFinite(defaultFactor) ? defaultFactor : 1f;
            return new HitboxDamageResult(true, Math.max(0f, def), def, null, structureModel,
                    missingBones, hitDistance, null, false, Float.POSITIVE_INFINITY, 0f);
        }

        public boolean shouldTriggerEra(float triggerDamage) {
            return era && Float.isFinite(triggerDamage) && triggerDamage > eraMinTriggerDamage;
        }
    }

    private record VehicleHitboxConfig(
            @Nullable ResourceLocation structureModel,
            float defaultFactor,
            Map<String, Float> factorByBoneName,
            Map<String, EraConfig> eraByBoneName,
            float coreDistanceScaleMultiplier
    ) {
        boolean isEnabled() {
            return defaultFactor != 1f
                    || (factorByBoneName != null && !factorByBoneName.isEmpty())
                    || (eraByBoneName != null && !eraByBoneName.isEmpty());
        }

        HitboxDamageResult resolve(BedrockModel model, AbstractVehicle vehicle, Vec3 segmentStart, Vec3 segmentEnd) {
            if ((factorByBoneName == null || factorByBoneName.isEmpty())
                    && (eraByBoneName == null || eraByBoneName.isEmpty())) {
                return HitboxDamageResult.defaulted(defaultFactor, structureModel, 0, Double.NaN);
            }
            Vector3f from = segmentStart.toVector3f();
            Vector3f to = segmentEnd.toVector3f();
            Map<String, BedrockBone> boneMap = model.getBoneMap();
            HashSet<BedrockBone> namedBones = new HashSet<>(boneMap.values());
            int missingBones = 0;

            List<HitCandidate> candidates = new ArrayList<>();
            Set<String> allConfigBones = new LinkedHashSet<>();
            allConfigBones.addAll(factorByBoneName.keySet());
            allConfigBones.addAll(eraByBoneName.keySet());

            for (String boneName : allConfigBones) {
                EraConfig eraConfig = eraByBoneName.get(boneName);
                float factor = eraConfig != null
                        ? eraConfig.damageFactor()
                        : factorByBoneName.getOrDefault(boneName, defaultFactor);
                List<ResolvedObb> resolvedObbs = resolveBoneObbs(vehicle, boneMap, namedBones, boneName);
                if (resolvedObbs.isEmpty()) {
                    if (boneMap.get(boneName) == null && vehicle.getPartUnit(boneName).isEmpty()) {
                        missingBones++;
                    }
                    continue;
                }
                boolean era = eraConfig != null;
                boolean eraActive = !era || INSTANCE.isEraActive(vehicle, boneName);
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
                            era,
                            eraActive,
                            eraConfig
                    ));
                }
            }

            if (candidates.isEmpty()) {
                return HitboxDamageResult.defaulted(defaultFactor, structureModel, missingBones, Double.NaN);
            }
            candidates.sort(Comparator.comparingDouble(HitCandidate::distance));
            for (HitCandidate candidate : candidates) {
                if (candidate.era() && !candidate.eraActive()) {
                    continue;
                }
                float factor = Float.isFinite(candidate.factor()) ? candidate.factor() : defaultFactor;
                if (candidate.era()) {
                    EraConfig eraConfig = candidate.eraConfig();
                    return new HitboxDamageResult(
                            true,
                            Math.max(0f, factor),
                            defaultFactor,
                            candidate.boneName(),
                            structureModel,
                            missingBones,
                            candidate.distance(),
                            candidate.hitPoint(),
                            true,
                            eraConfig == null ? Float.POSITIVE_INFINITY : eraConfig.minTriggerDamage(),
                            eraConfig == null ? 0f : eraConfig.explosion()
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
                        false,
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
            Map<String, EraConfig> eraMap = parseEraMap(obj.get("hitbox_era"));
            float coreM = GsonHelper.getAsFloat(obj, "core_distance_scale_multiplier", 1f);
            if ((map == null || map.isEmpty()) && (eraMap == null || eraMap.isEmpty()) && def == 1f && coreM == 1f) {
                return null;
            }
            return new VehicleHitboxConfig(
                    structure,
                    def,
                    map == null ? Map.of() : Map.copyOf(map),
                    eraMap == null ? Map.of() : Map.copyOf(eraMap),
                    coreM
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

        private static @Nullable Map<String, EraConfig> parseEraMap(@Nullable JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            Map<String, EraConfig> map = new HashMap<>();
            for (var entry : obj.entrySet()) {
                String key = normalizeBone(entry.getKey());
                if (key == null) {
                    continue;
                }
                EraConfig config = EraConfig.parse(entry.getValue());
                if (config != null) {
                    map.put(key, config);
                }
            }
            return map;
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
            boolean era,
            boolean eraActive,
            @Nullable EraConfig eraConfig
    ) {
    }

    private record ResolvedObb(OBB obb, String source) {
    }

    private record EraConfig(float damageFactor, float minTriggerDamage, float explosion) {
        static @Nullable EraConfig parse(@Nullable JsonElement element) {
            if (element == null) {
                return null;
            }
            if (element.isJsonPrimitive()) {
                Optional<Float> factor = VehicleHitboxConfig.tryFloat(element);
                return factor.map(value -> new EraConfig(Math.max(0f, value), Float.POSITIVE_INFINITY, 0f)).orElse(null);
            }
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject obj = element.getAsJsonObject();
            float damageFactor = GsonHelper.getAsFloat(obj, "damage_factor", 1f);
            float minTriggerDamage = GsonHelper.getAsFloat(obj, "min_trigger_damage", Float.POSITIVE_INFINITY);
            float explosion = GsonHelper.getAsFloat(obj, "explosion", 0f);
            if (!Float.isFinite(minTriggerDamage) || minTriggerDamage < 0f) {
                minTriggerDamage = Float.POSITIVE_INFINITY;
            }
            if (!Float.isFinite(explosion) || explosion < 0f) {
                explosion = 0f;
            }
            return new EraConfig(Math.max(0f, damageFactor), minTriggerDamage, explosion);
        }
    }
}
