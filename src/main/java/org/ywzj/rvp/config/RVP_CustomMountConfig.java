package org.ywzj.rvp.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side custom hardpoint render config parsed from vehicle JSON.
 */
public record RVP_CustomMountConfig(
        String partUnitId,
        String attachBone,
        String attachPartUnitId,
        ResourceLocation weaponId,
        ResourceLocation model,
        ResourceLocation texture,
        List<String> rackBones,
        List<String> missileBones,
        boolean replaceWeaponDisplay,
        int ammoSlot,
        int configOrder,
        Vec3fConfig offset,
        Vec3fConfig rotationDeg,
        Vec3fConfig scale
) {

    public record Vec3fConfig(float x, float y, float z) {
        public static final Vec3fConfig ZERO = new Vec3fConfig(0.0f, 0.0f, 0.0f);
        public static final Vec3fConfig ONE = new Vec3fConfig(1.0f, 1.0f, 1.0f);
    }

    @Nullable
    public static List<RVP_CustomMountConfig> parseList(JsonObject vehicleObj) {
        if (!vehicleObj.has("rvp_custom_mounts") || !vehicleObj.get("rvp_custom_mounts").isJsonArray()) {
            return null;
        }
        JsonArray array = vehicleObj.getAsJsonArray("rvp_custom_mounts");
        List<RVP_CustomMountConfig> configs = new ArrayList<>();
        int order = 0;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            RVP_CustomMountConfig config = parse(element.getAsJsonObject(), order++);
            if (config != null) {
                configs.add(config);
            }
        }
        return configs.isEmpty() ? null : List.copyOf(configs);
    }

    @Nullable
    private static RVP_CustomMountConfig parse(JsonObject obj, int order) {
        String partUnitId = GsonHelper.getAsString(obj, "part_unit_id", "").trim();
        String attachBone = GsonHelper.getAsString(obj, "attach_bone", "").trim();
        String attachPartUnitId = GsonHelper.getAsString(obj, "attach_part_unit_id", "").trim();
        ResourceLocation weaponId = parseResourceLocation(obj, "weapon_id");
        ResourceLocation model = parseResourceLocation(obj, "model");
        ResourceLocation texture = parseResourceLocation(obj, "texture");
        if (partUnitId.isEmpty() || (attachBone.isEmpty() && attachPartUnitId.isEmpty())
                || weaponId == null || model == null || texture == null) {
            return null;
        }
        return new RVP_CustomMountConfig(
                partUnitId,
                attachBone,
                attachPartUnitId,
                weaponId,
                model,
                texture,
                parseStringList(obj, "rack_bones"),
                parseStringList(obj, "missile_bones"),
                GsonHelper.getAsBoolean(obj, "replace_weapon_display", true),
                Math.max(0, GsonHelper.getAsInt(obj, "ammo_slot", 0)),
                order,
                parseVec3(obj, "offset", Vec3fConfig.ZERO),
                parseVec3(obj, "rotation_deg", Vec3fConfig.ZERO),
                parseVec3(obj, "scale", Vec3fConfig.ONE)
        );
    }

    private static List<String> parseStringList(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : obj.getAsJsonArray(key)) {
            if (element != null && element.isJsonPrimitive()) {
                String value = element.getAsString().trim();
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return List.copyOf(result);
    }

    @Nullable
    private static ResourceLocation parseResourceLocation(JsonObject obj, String key) {
        String raw = GsonHelper.getAsString(obj, key, "").trim();
        return raw.isEmpty() ? null : ResourceLocation.tryParse(raw);
    }

    private static Vec3fConfig parseVec3(JsonObject obj, String key, Vec3fConfig fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return fallback;
        }
        JsonArray array = obj.getAsJsonArray(key);
        if (array.size() < 3) {
            return fallback;
        }
        try {
            return new Vec3fConfig(
                    array.get(0).getAsFloat(),
                    array.get(1).getAsFloat(),
                    array.get(2).getAsFloat()
            );
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
