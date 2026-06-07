package org.ywzj.rvp.all;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.ywzj.rvp.weapon.core.RVP_DispenserWeapon;
import org.ywzj.rvp.weapon.core.RVP_LaserWeapon;
import org.ywzj.rvp.weapon.core.RVP_ProjectileWeapon;
import org.ywzj.rvp.weapon.core.RVP_TargetingPodWeapon;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.all.ModRegistries;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.custom.weapon.VehicleWeaponType;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Public RVP weapon type registry ({@code rvp:missile}, {@code rvp:rocket}, …).
 * Weapon JSON must already match the current schema; no runtime key migration.
 */
public final class RVP_WeaponTypes {

    public static final String PUBLIC_NAMESPACE = "rvp";

    public static final DeferredRegister<VehicleWeaponType<?, ?>> WEAPON_TYPES =
            DeferredRegister.create(ModRegistries.VEHICLE_WEAPON_TYPE, PUBLIC_NAMESPACE);

    public static final RegistryObject<VehicleWeaponType<RVP_ProjectileWeapon, RVP_WeaponData>> MISSILE =
            registerProjectile("missile", RVP_EnumWeaponKind.MISSILE, RVP_Entities.RVP_MISSILE::get);

    public static final RegistryObject<VehicleWeaponType<RVP_ProjectileWeapon, RVP_WeaponData>> ROCKET =
            registerProjectile("rocket", RVP_EnumWeaponKind.ROCKET, RVP_Entities.RVP_ROCKET::get);

    public static final RegistryObject<VehicleWeaponType<RVP_ProjectileWeapon, RVP_WeaponData>> MACHINEGUN =
            registerProjectile("machinegun", RVP_EnumWeaponKind.MACHINEGUN, RVP_Entities.RVP_BULLET::get);

    public static final RegistryObject<VehicleWeaponType<RVP_ProjectileWeapon, RVP_WeaponData>> BOMB =
            registerProjectile("bomb", RVP_EnumWeaponKind.BOMB, RVP_Entities.RVP_BOMB::get);

    public static final RegistryObject<VehicleWeaponType<RVP_LaserWeapon, RVP_WeaponData>> LASER =
            WEAPON_TYPES.register("laser",
                    () -> VehicleWeaponType.Builder.<RVP_LaserWeapon, RVP_WeaponData>of(id("laser"))
                            .setDataSerializer(json -> deserialize(json, RVP_EnumWeaponKind.LASER))
                            .setFactory(RVP_LaserWeapon::new)
                            .build());

    public static final RegistryObject<VehicleWeaponType<RVP_DispenserWeapon, RVP_WeaponData>> DISPENSER =
            WEAPON_TYPES.register("dispenser",
                    () -> VehicleWeaponType.Builder.<RVP_DispenserWeapon, RVP_WeaponData>of(id("dispenser"))
                            .setDataSerializer(json -> deserialize(json, RVP_EnumWeaponKind.DISPENSER))
                            .setFactory((v, u, i, d, s) -> new RVP_DispenserWeapon(v, u, i, d, s, RVP_Entities.RVP_DISPENSED::get))
                            .build());

    public static final RegistryObject<VehicleWeaponType<RVP_TargetingPodWeapon, RVP_WeaponData>> TARGETINGPOD =
            WEAPON_TYPES.register("targetingpod",
                    () -> VehicleWeaponType.Builder.<RVP_TargetingPodWeapon, RVP_WeaponData>of(id("targetingpod"))
                            .setDataSerializer(json -> deserialize(json, RVP_EnumWeaponKind.TARGETING_POD))
                            .setFactory(RVP_TargetingPodWeapon::new)
                            .build());

    private RVP_WeaponTypes() {}

    private static RegistryObject<VehicleWeaponType<RVP_ProjectileWeapon, RVP_WeaponData>> registerProjectile(
            String name, RVP_EnumWeaponKind kind, Supplier<EntityType<? extends Projectile>> entityType) {
        return WEAPON_TYPES.register(name,
                () -> VehicleWeaponType.Builder.<RVP_ProjectileWeapon, RVP_WeaponData>of(id(name))
                        .setDataSerializer(json -> deserialize(json, kind))
                        .setFactory((v, u, i, d, s) -> new RVP_ProjectileWeapon(v, u, i, d, s, entityType))
                        .build());
    }

    private static RVP_WeaponData deserialize(JsonElement json, RVP_EnumWeaponKind kind) {
        JsonElement prepared = prepareWeaponJson(json);
        RVP_WeaponData data = GsonUtil.GSON.fromJson(prepared, RVP_WeaponData.class);
        if (data != null && prepared.isJsonObject()) {
            data.setWeaponKind(kind);
            JsonObject root = prepared.getAsJsonObject();
            JsonObject proj = root.has("projectile_data") && root.get("projectile_data").isJsonObject()
                    ? root.getAsJsonObject("projectile_data")
                    : new JsonObject();
            data.getProjectileData().resolvePropulsionFallback(root, proj);
        }
        return data;
    }

    /**
     * Non-migrating normalization only (enum casing, optional field defaults).
     */
    private static JsonElement prepareWeaponJson(JsonElement json) {
        if (!json.isJsonObject()) {
            return json;
        }
        JsonObject root = json.getAsJsonObject().deepCopy();
        normalizeGuidanceData(root);
        normalizeProjectileData(root);
        normalizeFireMode(root);
        return root;
    }

    /**
     * Accepts legacy flat stage fields / top-level {@code steering_data}; canonical schema uses {@code stages}.
     */
    private static void normalizeGuidanceData(JsonObject root) {
        if (!root.has("guidance_data")) {
            return;
        }
        JsonElement guidance = root.get("guidance_data");
        if (guidance == null || guidance.isJsonNull()) {
            return;
        }
        if (guidance.isJsonArray()) {
            JsonObject wrapper = new JsonObject();
            wrapper.add("stages", guidance);
            root.add("guidance_data", wrapper);
            guidance = root.get("guidance_data");
        }
        if (!guidance.isJsonObject()) {
            root.remove("guidance_data");
            return;
        }
        JsonObject guidanceObject = guidance.getAsJsonObject();
        if (guidanceObject.has("phases") && !guidanceObject.has("stages")) {
            guidanceObject.add("stages", guidanceObject.remove("phases"));
        }
        if (guidanceObject.has("stage_policy") && !guidanceObject.has("phase_resolve_policy")) {
            guidanceObject.add("phase_resolve_policy", guidanceObject.remove("stage_policy"));
        }
        normalizeGuidanceEnumFields(guidanceObject);
        JsonElement topSteering = guidanceObject.has("steering_data")
                ? guidanceObject.remove("steering_data")
                : null;
        JsonElement topSeeker = guidanceObject.has("seeker_data")
                ? guidanceObject.remove("seeker_data")
                : null;
        if (guidanceObject.has("stages") && guidanceObject.get("stages").isJsonArray()) {
            JsonObject seekerTargetStage = null;
            for (JsonElement stageElement : guidanceObject.getAsJsonArray("stages")) {
                if (!stageElement.isJsonObject()) {
                    continue;
                }
                JsonObject stage = stageElement.getAsJsonObject();
                normalizeStageObject(stage);
                if (topSteering != null && !stage.has("steering_data")) {
                    stage.add("steering_data", topSteering.deepCopy());
                }
                if (topSeeker != null && stageUsesSeeker(stage) && seekerTargetStage == null) {
                    seekerTargetStage = stage;
                }
            }
            if (topSeeker != null) {
                if (seekerTargetStage == null) {
                    for (JsonElement stageElement : guidanceObject.getAsJsonArray("stages")) {
                        if (stageElement.isJsonObject()) {
                            seekerTargetStage = stageElement.getAsJsonObject();
                            break;
                        }
                    }
                }
                if (seekerTargetStage != null) {
                    mergeSeekerDefaults(seekerTargetStage, topSeeker.getAsJsonObject());
                }
            }
        }
    }

    private static boolean stageUsesSeeker(JsonObject stage) {
        if (!stage.has("sources") || !stage.get("sources").isJsonArray()) {
            return false;
        }
        for (JsonElement sourceElement : stage.getAsJsonArray("sources")) {
            if (!sourceElement.isJsonObject()) {
                continue;
            }
            JsonObject source = sourceElement.getAsJsonObject();
            if (!source.has("type") || !source.get("type").isJsonPrimitive()) {
                continue;
            }
            String type = source.get("type").getAsString().trim().toUpperCase(Locale.ROOT);
            if ("IR".equals(type) || "ARH".equals(type) || "SARH".equals(type) || "ARM".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static void mergeSeekerDefaults(JsonObject stage, JsonObject topSeeker) {
        JsonObject seeker = stage.has("seeker") && stage.get("seeker").isJsonObject()
                ? stage.getAsJsonObject("seeker").deepCopy()
                : new JsonObject();
        for (String key : topSeeker.keySet()) {
            if (!seeker.has(key)) {
                seeker.add(key, topSeeker.get(key).deepCopy());
            }
        }
        stage.add("seeker", seeker);
    }

    /** Uppercase guidance enum strings so Gson can bind {@code blend} → {@code BLEND}. */
    private static void normalizeGuidanceEnumFields(JsonObject guidanceObject) {
        if (guidanceObject.has("phase_resolve_policy")
                && guidanceObject.get("phase_resolve_policy").isJsonPrimitive()) {
            String policy = guidanceObject.get("phase_resolve_policy").getAsString();
            guidanceObject.addProperty("phase_resolve_policy", policy.trim().toUpperCase(Locale.ROOT));
        }
        if (!guidanceObject.has("stages") || !guidanceObject.get("stages").isJsonArray()) {
            return;
        }
        for (JsonElement stageElement : guidanceObject.getAsJsonArray("stages")) {
            if (!stageElement.isJsonObject()) {
                continue;
            }
            JsonObject stage = stageElement.getAsJsonObject();
            if (!stage.has("sources") || !stage.get("sources").isJsonArray()) {
                continue;
            }
            for (JsonElement sourceElement : stage.getAsJsonArray("sources")) {
                if (!sourceElement.isJsonObject()) {
                    continue;
                }
                JsonObject source = sourceElement.getAsJsonObject();
                if (source.has("type") && source.get("type").isJsonPrimitive()) {
                    source.addProperty("type", source.get("type").getAsString().trim().toUpperCase(Locale.ROOT));
                }
                if (source.has("composite_mode") && source.get("composite_mode").isJsonPrimitive()) {
                    source.addProperty("composite_mode",
                            source.get("composite_mode").getAsString().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
    }

    private static void normalizeStageObject(JsonObject stage) {
        if (stage.has("seeker_data") && !stage.has("seeker")) {
            stage.add("seeker", stage.remove("seeker_data"));
        }
        JsonObject activation = stage.has("activation") && stage.get("activation").isJsonObject()
                ? stage.getAsJsonObject("activation").deepCopy()
                : new JsonObject();
        moveField(stage, activation, "start_tick");
        moveField(stage, activation, "end_tick");
        moveField(stage, activation, "enter_once");
        moveField(stage, activation, "require_target");
        moveField(stage, activation, "require_entity_target");
        moveField(stage, activation, "require_illumination");
        moveField(stage, activation, "min_distance", "min_target_distance");
        moveField(stage, activation, "max_distance", "max_target_distance");
        moveField(stage, activation, "min_entity_distance");
        moveField(stage, activation, "max_entity_distance");
        moveField(stage, activation, "min_altitude", "min_altitude_agl");
        moveField(stage, activation, "max_altitude", "max_altitude_agl");
        if (activation.size() > 0) {
            stage.add("activation", activation);
        }
    }

    private static void moveField(JsonObject from, JsonObject to, String key) {
        moveField(from, to, key, key);
    }

    private static void moveField(JsonObject from, JsonObject to, String fromKey, String toKey) {
        if (from.has(fromKey) && !to.has(toKey)) {
            to.add(toKey, from.remove(fromKey));
        }
    }

    private static void normalizeProjectileData(JsonObject root) {
        JsonObject proj = ensureProjectileData(root);
        if (!proj.has("velocity") && root.has("velocity") && root.get("velocity").isJsonPrimitive()) {
            proj.add("velocity", root.get("velocity"));
        }
        autoEnableRocketEngine(root, proj);
    }

    private static JsonObject ensureProjectileData(JsonObject root) {
        if (!root.has("projectile_data") || !root.get("projectile_data").isJsonObject()) {
            JsonObject created = new JsonObject();
            root.add("projectile_data", created);
            return created;
        }
        return root.getAsJsonObject("projectile_data");
    }

    /** When propulsion fields are present but {@code has_rocket_engine} is omitted, default it to true. */
    private static void autoEnableRocketEngine(JsonObject root, JsonObject proj) {
        boolean hasPropulsion = root.has("mass") || root.has("thrust") || root.has("motor_burn_time")
                || proj.has("mass") || proj.has("thrust") || proj.has("motor_burn_time");
        if (hasPropulsion && !proj.has("has_rocket_engine")) {
            proj.addProperty("has_rocket_engine", true);
        }
    }

    /** Normalize {@code fire_data.fire_mode} to {@link org.ywzj.rvp.weapon.data.RVP_EnumFireMode} enum name. */
    private static void normalizeFireMode(JsonObject root) {
        if (!root.has("fire_data") || !root.get("fire_data").isJsonObject()) {
            return;
        }
        JsonObject fire = root.getAsJsonObject("fire_data");
        if (!fire.has("fire_mode") || !fire.get("fire_mode").isJsonPrimitive()) {
            return;
        }
        String normalized = org.ywzj.rvp.weapon.data.RVP_EnumFireMode
                .fromString(fire.get("fire_mode").getAsString())
                .name();
        fire.addProperty("fire_mode", normalized);
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(PUBLIC_NAMESPACE, name);
    }

    public static void register(IEventBus bus) {
        WEAPON_TYPES.register(bus);
    }
}
