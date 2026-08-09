package org.ywzj.rvp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponBase;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.HashMap;
import java.util.Map;

public final class RVP_VehicleWeaponHeatConfigCache {

    private static Map<ResourceLocation, Map<SlotKey, RVP_VehicleWeaponHeatConfig>> CONFIGS = Map.of();

    private RVP_VehicleWeaponHeatConfigCache() {}

    public static void replace(Map<ResourceLocation, Map<SlotKey, RVP_VehicleWeaponHeatConfig>> configs) {
        Map<ResourceLocation, Map<SlotKey, RVP_VehicleWeaponHeatConfig>> copy = new HashMap<>();
        for (var entry : configs.entrySet()) {
            copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        CONFIGS = Map.copyOf(copy);
    }

    public static Map<SlotKey, RVP_VehicleWeaponHeatConfig> parseVehicle(JsonObject vehicleObj) {
        Map<SlotKey, RVP_VehicleWeaponHeatConfig> out = new HashMap<>();
        if (!vehicleObj.has("parts") || !vehicleObj.get("parts").isJsonArray()) {
            return out;
        }
        for (JsonElement partElement : vehicleObj.getAsJsonArray("parts")) {
            if (partElement == null || !partElement.isJsonObject()) {
                continue;
            }
            JsonObject partObj = partElement.getAsJsonObject();
            String partId = GsonHelper.getAsString(partObj, "id", "").trim();
            if (partId.isEmpty() || !partObj.has("weapons") || !partObj.get("weapons").isJsonArray()) {
                continue;
            }
            int primaryIndex = 0;
            int secondaryIndex = 0;
            for (JsonElement weaponElement : partObj.getAsJsonArray("weapons")) {
                boolean secondary = weaponElement != null
                        && weaponElement.isJsonObject()
                        && GsonHelper.getAsBoolean(weaponElement.getAsJsonObject(), "secondary", false);
                boolean mergeIntoPrevious = weaponElement != null
                        && weaponElement.isJsonObject()
                        && GsonHelper.getAsBoolean(weaponElement.getAsJsonObject(), "merge_into_previous_slot", false);
                boolean independent = isIndependentWeaponEntry(weaponElement);
                if (weaponElement != null && weaponElement.isJsonObject()) {
                    JsonObject weaponObj = weaponElement.getAsJsonObject();
                    RVP_VehicleWeaponHeatConfig config = parseWeaponSlot(weaponObj);
                    if (config.enabled() && !independent && !mergeIntoPrevious) {
                        int slotIndex = secondary ? secondaryIndex : primaryIndex;
                        out.put(new SlotKey(partId, secondary ? Channel.SECONDARY : Channel.PRIMARY, slotIndex), config);
                    }
                }
                if (!independent && !mergeIntoPrevious) {
                    if (secondary) {
                        secondaryIndex++;
                    } else {
                        primaryIndex++;
                    }
                }
            }
        }
        return out;
    }

    @Nullable
    public static Resolved resolve(RVP_WeaponBase weapon) {
        WeaponUnit root = weapon.getWeaponUnit().getRootParentWeaponUnit();
        if (root == null) {
            root = weapon.getWeaponUnit();
        }
        if (root == null) {
            return null;
        }
        Map<SlotKey, RVP_VehicleWeaponHeatConfig> bySlot = CONFIGS.get(weapon.getVehicle().getVehicleId());
        if (bySlot == null || bySlot.isEmpty()) {
            return null;
        }
        SlotKey key = currentSlotKey(root, weapon);
        if (key == null) {
            return null;
        }
        RVP_VehicleWeaponHeatConfig config = bySlot.get(key);
        return config != null && config.enabled() ? new Resolved(key, config) : null;
    }

    @Nullable
    private static SlotKey currentSlotKey(WeaponUnit root, RVP_WeaponBase weapon) {
        String partId = root.getId();
        if (partId == null || partId.isEmpty()) {
            return null;
        }
        AbstractVehicleWeapon<?> primary = root.getCurrentWeapon().orElse(null);
        if (unwrap(primary) == weapon) {
            return new SlotKey(partId, Channel.PRIMARY, root.getCurrentWeaponIndex());
        }
        AbstractVehicleWeapon<?> secondary = root.getCurrentSecondaryWeapon().orElse(null);
        if (unwrap(secondary) == weapon) {
            return new SlotKey(partId, Channel.SECONDARY, root.getCurrentSecondaryWeaponIndex());
        }
        return null;
    }

    @Nullable
    private static AbstractVehicleWeapon<?> unwrap(@Nullable AbstractVehicleWeapon<?> weapon) {
        return RVP_WeaponResolveHelper.unwrap(weapon);
    }

    private static RVP_VehicleWeaponHeatConfig parseWeaponSlot(JsonObject obj) {
        int maxHeat = Math.max(0, GsonHelper.getAsInt(obj, "vehicle_max_heat_count", 0));
        if (maxHeat <= 0) {
            return RVP_VehicleWeaponHeatConfig.DISABLED;
        }
        return new RVP_VehicleWeaponHeatConfig(
                Math.max(0, GsonHelper.getAsInt(obj, "vehicle_heat_count", 0)),
                maxHeat,
                Math.max(0, GsonHelper.getAsInt(obj, "vehicle_overheat_extra_heat", 30))
        );
    }

    private static boolean isIndependentWeaponEntry(@Nullable JsonElement element) {
        ResourceLocation id = firstWeaponId(element);
        if (id == null) {
            return false;
        }
        return CommonAssetsManager.vehicleWeaponManager()
                .getIndex(id)
                .map(index -> index.data().independent)
                .orElse(false);
    }

    @Nullable
    private static ResourceLocation firstWeaponId(@Nullable JsonElement element) {
        if (element == null) {
            return null;
        }
        if (GsonHelper.isStringValue(element)) {
            return ResourceLocation.tryParse(element.getAsString());
        }
        if (element.isJsonArray() && !element.getAsJsonArray().isEmpty()) {
            JsonElement first = element.getAsJsonArray().get(0);
            return GsonHelper.isStringValue(first) ? ResourceLocation.tryParse(first.getAsString()) : null;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            String id = GsonHelper.getAsString(obj, "id", "");
            if (!id.isBlank()) {
                return ResourceLocation.tryParse(id);
            }
            if (obj.has("ids") && obj.get("ids").isJsonArray() && !obj.getAsJsonArray("ids").isEmpty()) {
                JsonElement first = obj.getAsJsonArray("ids").get(0);
                return GsonHelper.isStringValue(first) ? ResourceLocation.tryParse(first.getAsString()) : null;
            }
        }
        return null;
    }

    public enum Channel {
        PRIMARY,
        SECONDARY
    }

    public record SlotKey(String partUnitId, Channel channel, int slotIndex) {}

    public record Resolved(SlotKey key, RVP_VehicleWeaponHeatConfig config) {}
}
