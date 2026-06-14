package org.ywzj.rvp.weapon.damage;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.ResourceScanner;
import org.ywzj.vehicle.vehicle.structure.OBB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class RVP_VehicleHitboxFactorManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public static final RVP_VehicleHitboxFactorManager INSTANCE = new RVP_VehicleHitboxFactorManager();

    private Map<ResourceLocation, VehicleHitboxConfig> configs = Map.of();
    private final Map<UUID, Long> lastDebugAtMsByPlayer = new HashMap<>();

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return ResourceScanner.scanDirectory(resourceManager, "vehicles", GsonUtil.GSON);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, VehicleHitboxConfig> loaded = new HashMap<>();
        map.forEach((vehicleId, json) -> {
            if (!json.isJsonObject()) {
                return;
            }
            JsonObject obj = json.getAsJsonObject();
            VehicleHitboxConfig cfg = VehicleHitboxConfig.parse(obj);
            if (cfg != null) {
                loaded.put(vehicleId, cfg);
            }
        });
        configs = Map.copyOf(loaded);
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

    public void maybeSendHitboxDebug(
            Player player,
            AbstractVehicle vehicle,
            float damageBefore,
            float damageAfter,
            HitboxDamageResult result,
            float coreFalloffScale,
            float coreFalloffMultiplier
    ) {
        if (player == null || vehicle == null || result == null) {
            return;
        }
        if (!result.enabled()) {
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
            String hitBoneName,
            ResourceLocation structureModel,
            int missingConfigBones,
            double hitDistance
    ) {
        public static HitboxDamageResult disabled() {
            return new HitboxDamageResult(false, 1f, 1f, null, null, 0, Double.NaN);
        }

        public static HitboxDamageResult defaulted(float defaultFactor, ResourceLocation structureModel, int missingBones, double hitDistance) {
            float def = Float.isFinite(defaultFactor) ? defaultFactor : 1f;
            return new HitboxDamageResult(true, Math.max(0f, def), def, null, structureModel, missingBones, hitDistance);
        }
    }

    private record VehicleHitboxConfig(
            @Nullable ResourceLocation structureModel,
            float defaultFactor,
            Map<String, Float> factorByBoneName,
            float coreDistanceScaleMultiplier
    ) {
        boolean isEnabled() {
            return defaultFactor != 1f || (factorByBoneName != null && !factorByBoneName.isEmpty());
        }

        HitboxDamageResult resolve(BedrockModel model, AbstractVehicle vehicle, Vec3 segmentStart, Vec3 segmentEnd) {
            if (factorByBoneName == null || factorByBoneName.isEmpty()) {
                return HitboxDamageResult.defaulted(defaultFactor, structureModel, 0, Double.NaN);
            }
            Vector3f from = segmentStart.toVector3f();
            Vector3f to = segmentEnd.toVector3f();
            Map<String, BedrockBone> boneMap = model.getBoneMap();

            HashSet<BedrockBone> namedBones = new HashSet<>(boneMap.values());
            int missingBones = 0;

            double bestDistance = Double.MAX_VALUE;
            float bestFactor = Float.NaN;
            String bestBone = null;
            for (var entry : factorByBoneName.entrySet()) {
                String boneName = entry.getKey();
                Float factorObj = entry.getValue();
                if (factorObj == null) {
                    continue;
                }
                float factor = factorObj;
                BedrockBone bone = boneMap.get(boneName);
                if (bone == null) {
                    missingBones++;
                    continue;
                }
                for (OBB.CubeOBB cubeObb : OBB.getOBBsFromBone(bone, vehicle, namedBones)) {
                    Vector3f hit = cubeObb.obb().clip(from, to).orElse(null);
                    if (hit == null) {
                        continue;
                    }
                    double distance = new Vec3(hit).distanceTo(segmentStart);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestFactor = factor;
                        bestBone = boneName;
                    }
                }
            }
            if (Float.isNaN(bestFactor)) {
                return HitboxDamageResult.defaulted(defaultFactor, structureModel, missingBones, Double.NaN);
            }
            if (!Float.isFinite(bestFactor)) {
                return HitboxDamageResult.defaulted(defaultFactor, structureModel, missingBones, bestDistance);
            }
            return new HitboxDamageResult(true, Math.max(0f, bestFactor), defaultFactor, bestBone, structureModel, missingBones, bestDistance);
        }

        static @Nullable VehicleHitboxConfig parse(JsonObject obj) {
            ResourceLocation structure = parseId(GsonHelper.getAsString(obj, "structure_model", null));
            float def = GsonHelper.getAsFloat(obj, "hitbox_damage_factor_default", 1f);
            Map<String, Float> map = parseFactorMap(obj.get("hitbox_damage_factor"));
            float coreM = GsonHelper.getAsFloat(obj, "core_distance_scale_multiplier", 1f);
            if ((map == null || map.isEmpty()) && def == 1f && coreM == 1f) {
                return null;
            }
            return new VehicleHitboxConfig(structure, def, map == null ? Map.of() : Map.copyOf(map), coreM);
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
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                Optional<Float> val = tryFloat(entry.getValue());
                if (val.isEmpty()) {
                    continue;
                }
                float f = val.get();
                map.put(key.trim(), f);
            }
            return map;
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
            } catch (Exception ignored) {}
            return Optional.empty();
        }
    }
}
