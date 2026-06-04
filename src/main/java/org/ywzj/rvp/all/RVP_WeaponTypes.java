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

import java.util.function.Supplier;

/**
 * Public RVP weapon type registry. New JSON should use only these seven
 * {@code rvp:*} entries instead of legacy parity type names.
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
        JsonElement normalized = normalizeGuidance(json);
        RVP_WeaponData data = GsonUtil.GSON.fromJson(normalized, RVP_WeaponData.class);
        if (data != null && normalized.isJsonObject()) {
            data.setWeaponKind(kind);
            JsonObject root = normalized.getAsJsonObject();
            JsonObject proj = root.has("projectile_data") && root.get("projectile_data").isJsonObject()
                    ? root.getAsJsonObject("projectile_data")
                    : new JsonObject();
            data.getProjectileData().resolvePropulsionFallback(root, proj);
        }
        return data;
    }

    private static JsonElement normalizeGuidance(JsonElement json) {
        if (!json.isJsonObject()) {
            return json;
        }
        JsonObject obj = json.getAsJsonObject().deepCopy();
        JsonElement guidance = obj.get("guidance_data");
        if (guidance == null) {
            guidance = obj.get("guidance");
        }
        if (guidance != null && guidance.isJsonArray()) {
            JsonObject wrapper = new JsonObject();
            wrapper.add("stages", guidance);
            moveSteeringIntoGuidance(obj, wrapper);
            obj.remove("guidance");
            obj.add("guidance_data", wrapper);
        } else if (guidance != null && guidance.isJsonObject()) {
            moveSteeringIntoGuidance(obj, guidance.getAsJsonObject());
        }
        JsonElement damage = obj.get("damage");
        if (damage != null && damage.isJsonObject()) {
            JsonObject damageObject = damage.getAsJsonObject();
            obj.remove("damage_model");
            obj.add("damage_model_data", damageObject.deepCopy());
            if (damageObject.has("direct") && damageObject.get("direct").isJsonPrimitive()) {
                obj.add("damage", damageObject.get("direct"));
            } else {
                obj.remove("damage");
            }
            if (!obj.has("explosion") && !hasDetonateExplosion(obj)
                    && (damageObject.has("explosion") || damageObject.has("radius"))) {
                JsonObject explosion = new JsonObject();
                explosion.addProperty("explode", damageObject.has("explosion"));
                if (damageObject.has("explosion")) {
                    explosion.add("damage", damageObject.get("explosion"));
                }
                if (damageObject.has("radius")) {
                    explosion.add("radius", damageObject.get("radius"));
                }
                explosion.addProperty("destroy_block", false);
                addExplosionToDetonate(obj, explosion);
            }
        }
        moveExplosionIntoDetonate(obj);
        moveCollisionFromDamageModel(obj);
        normalizeFireMode(obj);
        normalizeFirePelletCount(obj);
        stripLegacyFireDataKeys(obj);
        normalizeFireDataFieldNames(obj);
        normalizeFireSpread(obj);
        normalizeFuseData(obj);
        normalizeProjectileData(obj);
        return obj;
    }

    /** 旧 {@code projectile_data.acceleration} 合并到 {@code velocity}；{@code speed_factor*} 转为推进字段。 */
    private static void normalizeProjectileData(JsonObject root) {
        JsonObject proj = ensureProjectileData(root);
        if (proj.has("acceleration") && proj.get("acceleration").isJsonPrimitive()) {
            JsonElement legacy = proj.remove("acceleration");
            if (!proj.has("velocity")) {
                proj.add("velocity", legacy);
            }
        }
        migrateLegacySpeedFactor(root, proj);
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

    /** 顶层或弹体写了推进参数时，若未配置 {@code has_rocket_engine} 则默认开启。 */
    private static void autoEnableRocketEngine(JsonObject root, JsonObject proj) {
        boolean hasPropulsion = root.has("mass") || root.has("thrust") || root.has("motor_burn_time")
                || proj.has("mass") || proj.has("thrust") || proj.has("motor_burn_time");
        if (hasPropulsion && !proj.has("has_rocket_engine")) {
            proj.addProperty("has_rocket_engine", true);
        }
    }

    private static final float LEGACY_PROPULSION_MASS = 0.01f;

    /** 将已废弃的 speed_factor 区间加速换算为本体导弹 thrust/mass 参数。 */
    private static void migrateLegacySpeedFactor(JsonObject root, JsonObject proj) {
        if (!proj.has("speed_factor") || !proj.get("speed_factor").isJsonPrimitive()) {
            return;
        }
        float speedFactor = proj.get("speed_factor").getAsFloat();
        int endTick = proj.has("speed_factor_end_tick") && proj.get("speed_factor_end_tick").isJsonPrimitive()
                ? proj.get("speed_factor_end_tick").getAsInt()
                : 0;
        proj.remove("speed_factor");
        proj.remove("speed_factor_start_tick");
        proj.remove("speed_factor_end_tick");
        if (speedFactor <= 0f) {
            return;
        }
        if (endTick <= 0) {
            endTick = 100;
        }
        if (!proj.has("has_rocket_engine")) {
            proj.addProperty("has_rocket_engine", true);
        }
        ensurePropulsionField(proj, "mass", LEGACY_PROPULSION_MASS);
        ensurePropulsionField(proj, "thrust", LEGACY_PROPULSION_MASS * speedFactor);
        ensurePropulsionField(proj, "motor_burn_time", endTick);
        if (!proj.has("drag_coefficient")) {
            proj.addProperty("drag_coefficient", 0.012f);
        }
    }

    private static void ensurePropulsionField(JsonObject target, String key, float value) {
        if (!target.has(key) || !target.get(key).isJsonPrimitive() || target.get(key).getAsFloat() <= 0f) {
            target.addProperty(key, value);
        }
    }

    /**
     * 合并 {@code time_tick} → {@code delay_tick}；旧 {@code airburst} 距离/时间空爆 → {@code programmable_airburst}。
     */
    private static void normalizeFuseData(JsonObject root) {
        if (!root.has("fuse_data") || !root.get("fuse_data").isJsonObject()) {
            return;
        }
        JsonObject fuse = root.getAsJsonObject("fuse_data");
        if (fuse.has("time_tick") && fuse.get("time_tick").isJsonPrimitive()) {
            int time = fuse.get("time_tick").getAsInt();
            fuse.remove("time_tick");
            if (time > 0 && (!fuse.has("delay_tick") || fuse.get("delay_tick").getAsInt() <= 0)) {
                fuse.addProperty("delay_tick", time);
            }
        }
        if (fuse.has("airburst") && fuse.get("airburst").getAsBoolean()) {
            if (!fuse.has("programmable_airburst")) {
                fuse.addProperty("programmable_airburst", true);
            }
            fuse.remove("airburst");
        }
        fuse.remove("airburst_distance");
        fuse.remove("airburst_tick");
    }

    /** 顶层 {@code inaccuracy} 迁入 {@code fire_data.spread}（仅当未写 spread 时），与 damage/direct 迁移一致。 */
    private static void normalizeFireSpread(JsonObject root) {
        if (!root.has("inaccuracy") || !root.get("inaccuracy").isJsonPrimitive()) {
            return;
        }
        JsonObject fire = root.has("fire_data") && root.get("fire_data").isJsonObject()
                ? root.getAsJsonObject("fire_data")
                : new JsonObject();
        if (!root.has("fire_data")) {
            root.add("fire_data", fire);
        }
        if (!fire.has("spread")) {
            fire.add("spread", root.get("inaccuracy"));
        }
    }

    private static void stripLegacyFireDataKeys(JsonObject root) {
        if (!root.has("fire_data") || !root.get("fire_data").isJsonObject()) {
            return;
        }
        root.getAsJsonObject("fire_data").remove("shotgun");
    }

    private static void normalizeFireDataFieldNames(JsonObject root) {
        if (!root.has("fire_data") || !root.get("fire_data").isJsonObject()) {
            return;
        }
        JsonObject fire = root.getAsJsonObject("fire_data");
        renameFirePrimitive(fire, "bomblet_diff", "canister_diff");
        renameFirePrimitive(fire, "BombletDiff", "canister_diff");
        renameFirePrimitive(fire, "bomblet_s_time", "canister_burst_delay_time");
        renameFirePrimitive(fire, "BombletSTime", "canister_burst_delay_time");
        renameFirePrimitive(fire, "bombletstime", "canister_burst_delay_time");

        boolean burstMode = fire.has("fire_mode") && fire.get("fire_mode").isJsonPrimitive()
                && "BURST".equalsIgnoreCase(fire.get("fire_mode").getAsString());
        if (fire.has("burst_count") && !burstMode) {
            JsonElement legacy = fire.remove("burst_count");
            if (!fire.has("canister_burst_count")) {
                fire.add("canister_burst_count", legacy);
            }
        }
    }

    private static void renameFirePrimitive(JsonObject fire, String from, String to) {
        if (fire.has(from) && !fire.has(to)) {
            fire.add(to, fire.remove(from));
        } else if (fire.has(from)) {
            fire.remove(from);
        }
    }

    private static void normalizeFirePelletCount(JsonObject root) {
        if (!root.has("fire_data") || !root.get("fire_data").isJsonObject()) {
            return;
        }
        JsonObject fire = root.getAsJsonObject("fire_data");
        if (fire.has("projectile_count") && fire.get("projectile_count").isJsonPrimitive()) {
            try {
                int legacy = fire.get("projectile_count").getAsInt();
                fire.remove("projectile_count");
                if (legacy > 1 && (!fire.has("canister_count") || fire.get("canister_count").getAsInt() <= 0)) {
                    fire.addProperty("canister_count", legacy);
                }
            } catch (NumberFormatException ignored) {
                fire.remove("projectile_count");
            }
        }
        if (!fire.has("canister")) {
            return;
        }
        JsonElement legacyCanister = fire.remove("canister");
        if (fire.has("canister_count")) {
            return;
        }
        if (legacyCanister != null && legacyCanister.isJsonPrimitive()) {
            if (legacyCanister.getAsJsonPrimitive().isNumber()) {
                fire.addProperty("canister_count", legacyCanister.getAsInt());
            } else if (legacyCanister.getAsJsonPrimitive().isBoolean() && legacyCanister.getAsBoolean()) {
                fire.addProperty("canister_count", 8);
            }
        }
    }

    private static void normalizeFireMode(JsonObject root) {
        if (!root.has("fire_data") || !root.get("fire_data").isJsonObject()) {
            return;
        }
        JsonObject fire = root.getAsJsonObject("fire_data");
        JsonElement raw = null;
        if (fire.has("mode")) {
            raw = fire.remove("mode");
        } else if (fire.has("fire_mode") && fire.get("fire_mode").isJsonPrimitive()) {
            raw = fire.get("fire_mode");
        }
        if (raw == null || !raw.isJsonPrimitive()) {
            return;
        }
        String normalized = org.ywzj.rvp.weapon.data.RVP_EnumFireMode
                .fromString(raw.getAsString())
                .name();
        fire.addProperty("fire_mode", normalized);
    }

    private static final String[] COLLISION_KEYS = {
            "piercing", "wall_penetration", "bounce", "bounce_strength", "bounce_fuse_tick"
    };

    private static void moveCollisionFromDamageModel(JsonObject root) {
        if (!root.has("damage_model_data") || !root.get("damage_model_data").isJsonObject()) {
            return;
        }
        JsonObject damageModel = root.getAsJsonObject("damage_model_data");
        JsonObject collision = root.has("collision_data") && root.get("collision_data").isJsonObject()
                ? root.getAsJsonObject("collision_data").deepCopy()
                : new JsonObject();
        boolean changed = false;
        for (String key : COLLISION_KEYS) {
            if (!damageModel.has(key)) {
                continue;
            }
            if (!collision.has(key)) {
                collision.add(key, damageModel.remove(key));
            } else {
                damageModel.remove(key);
            }
            changed = true;
        }
        if (changed || collision.size() > 0) {
            root.add("collision_data", collision);
        }
    }

    private static boolean hasDetonateExplosion(JsonObject root) {
        if (!root.has("detonate_data") || !root.get("detonate_data").isJsonObject()) {
            return false;
        }
        JsonObject detonate = root.getAsJsonObject("detonate_data");
        return detonate.has("explosion_data") || detonate.has("explosion");
    }

    private static void addExplosionToDetonate(JsonObject root, JsonElement explosion) {
        JsonObject detonate = root.has("detonate_data") && root.get("detonate_data").isJsonObject()
                ? root.getAsJsonObject("detonate_data").deepCopy()
                : new JsonObject();
        if (!detonate.has("explosion_data")) {
            detonate.add("explosion_data", explosion);
        }
        root.add("detonate_data", detonate);
    }

    private static void moveExplosionIntoDetonate(JsonObject root) {
        JsonElement explosion = null;
        if (root.has("explosion_data")) {
            explosion = root.remove("explosion_data");
        } else if (root.has("explosion")) {
            explosion = root.remove("explosion");
        }
        if (explosion == null) {
            return;
        }
        addExplosionToDetonate(root, explosion);
    }

    private static void moveSteeringIntoGuidance(JsonObject root, JsonObject guidanceObj) {
        String[] steeringKeys = {
                "rigidity_time", "turning_factor", "max_degree_of_missile",
                "predict_target_pos", "tick_end_homing"
        };
        JsonObject steering = null;
        for (String key : steeringKeys) {
            if (!root.has(key)) {
                continue;
            }
            if (steering == null) {
                steering = new JsonObject();
            }
            steering.add(key, root.remove(key));
        }
        if (steering != null) {
            guidanceObj.add("steering_data", steering);
        }
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(PUBLIC_NAMESPACE, name);
    }

    public static void register(IEventBus bus) {
        WEAPON_TYPES.register(bus);
    }
}
