package org.ywzj.rvp.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.vehicle.custom.serialize.GsonUtil;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.ResourceScanner;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import org.ywzj.vehicle.vehicle.weapon.VehicleMultiWeapons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_VehicleExtendedConfigManager extends SimplePreparableReloadListener<Map<ResourceLocation, JsonElement>> {

    public static final RVP_VehicleExtendedConfigManager INSTANCE = new RVP_VehicleExtendedConfigManager();

    private Map<ResourceLocation, VehicleExtendedConfig> configs = Map.of();

    private RVP_VehicleExtendedConfigManager() {}

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return ResourceScanner.scanDirectory(resourceManager, "vehicles", GsonUtil.GSON);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, VehicleExtendedConfig> loaded = new HashMap<>();
        map.forEach((vehicleId, json) -> {
            if (!json.isJsonObject()) {
                return;
            }
            VehicleExtendedConfig cfg = parseVehicle(json.getAsJsonObject());
            if (cfg.isEnabled()) {
                loaded.put(vehicleId, cfg);
            }
        });
        configs = Map.copyOf(loaded);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    public VehicleExtendedConfig get(AbstractVehicle vehicle) {
        return vehicle == null ? VehicleExtendedConfig.EMPTY : configs.getOrDefault(vehicle.getVehicleId(), VehicleExtendedConfig.EMPTY);
    }

    public boolean hasGroundContact(AbstractVehicle vehicle) {
        return !get(vehicle).groundContactPartIds().isEmpty();
    }

    public boolean isModdingOnlyMulti(WeaponUnit weaponUnit, int weaponIndex) {
        if (weaponUnit == null || weaponIndex < 0) {
            return false;
        }
        VehicleExtendedConfig cfg = get(weaponUnit.getVehicle());
        return cfg.isModdingOnlyMulti(weaponUnit.getId(), weaponIndex);
    }

    public boolean shouldBlockRuntimeMultiCycle(WeaponUnit weaponUnit, int weaponIndex) {
        if (weaponUnit == null || weaponIndex < 0) {
            return false;
        }
        VehicleExtendedConfig cfg = get(weaponUnit.getVehicle());
        return cfg.shouldBlockRuntimeMultiCycle(weaponUnit.getId(), weaponIndex);
    }

    public boolean hasGroupedWeaponSlots(AbstractVehicle vehicle, String partId) {
        if (vehicle == null || partId == null || partId.isBlank()) {
            return false;
        }
        return get(vehicle).hasGroupedWeaponSlots(partId);
    }

    public boolean isMergeIntoPreviousSlot(AbstractVehicle vehicle, String partId, int weaponIndex) {
        if (vehicle == null || partId == null || partId.isBlank() || weaponIndex < 0) {
            return false;
        }
        return get(vehicle).isMergeIntoPreviousSlot(partId, weaponIndex);
    }

    public VehicleMultiWeapons resolveModdingTargetMulti(WeaponUnit weaponUnit, int weaponIndex) {
        if (weaponUnit == null || weaponIndex < 0 || weaponIndex >= weaponUnit.weapons.size()) {
            return null;
        }
        AbstractVehicleWeapon<?> topLevel = weaponUnit.weapons.get(weaponIndex);
        if (!(topLevel instanceof VehicleMultiWeapons topMulti)) {
            return null;
        }
        VehicleExtendedConfig cfg = get(weaponUnit.getVehicle());
        if (!cfg.isGroupedSlotCarrier(weaponUnit.getId(), weaponIndex)) {
            return topMulti;
        }
        if (topMulti.getSubWeapons().isEmpty()) {
            return null;
        }
        AbstractVehicleWeapon<?> first = topMulti.getSubWeapons().get(0);
        return first instanceof VehicleMultiWeapons inner ? inner : null;
    }

    public List<ModdingMultiEntry> getModdingOnlyEntries(AbstractVehicle vehicle) {
        VehicleExtendedConfig cfg = get(vehicle);
        List<ModdingMultiEntry> out = new ArrayList<>();
        cfg.moddingOnlyMultiByPartId().forEach((partId, weaponIndexes) -> {
            for (Integer weaponIndex : weaponIndexes) {
                out.add(new ModdingMultiEntry(partId, weaponIndex));
            }
        });
        return out;
    }

    private static VehicleExtendedConfig parseVehicle(JsonObject obj) {
        Set<String> groundContactPartIds = parseGroundContactPartIds(obj);
        Map<String, Set<Integer>> moddingOnlyMulti = parseModdingOnlyMulti(obj);
        Map<String, Set<Integer>> mergeIntoPreviousSlots = parseMergeIntoPreviousSlots(obj);
        if (groundContactPartIds.isEmpty() && moddingOnlyMulti.isEmpty() && mergeIntoPreviousSlots.isEmpty()) {
            return VehicleExtendedConfig.EMPTY;
        }
        return new VehicleExtendedConfig(
                Set.copyOf(groundContactPartIds),
                immutableIndexMap(moddingOnlyMulti),
                immutableIndexMap(mergeIntoPreviousSlots)
        );
    }

    private static Set<String> parseGroundContactPartIds(JsonObject obj) {
        if (!obj.has("physics_info") || !obj.get("physics_info").isJsonObject()) {
            return Set.of();
        }
        JsonObject physicsObj = obj.getAsJsonObject("physics_info");
        if (!physicsObj.has("ground_contact_part_ids") || !physicsObj.get("ground_contact_part_ids").isJsonArray()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        JsonArray array = physicsObj.getAsJsonArray("ground_contact_part_ids");
        for (JsonElement element : array) {
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            String id = element.getAsString().trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static Map<String, Set<Integer>> parseModdingOnlyMulti(JsonObject obj) {
        if (!obj.has("parts") || !obj.get("parts").isJsonArray()) {
            return Map.of();
        }
        Map<String, Set<Integer>> out = new LinkedHashMap<>();
        JsonArray parts = obj.getAsJsonArray("parts");
        for (JsonElement partElement : parts) {
            if (partElement == null || !partElement.isJsonObject()) {
                continue;
            }
            JsonObject partObj = partElement.getAsJsonObject();
            String type = GsonHelper.getAsString(partObj, "type", "");
            if (!type.contains("weapon")) {
                continue;
            }
            String partId = GsonHelper.getAsString(partObj, "id", "").trim();
            if (partId.isEmpty()) {
                continue;
            }
            if (!partObj.has("weapons") || !partObj.get("weapons").isJsonArray()) {
                continue;
            }
            JsonArray weapons = partObj.getAsJsonArray("weapons");
            for (int i = 0; i < weapons.size(); i++) {
                JsonElement weaponElement = weapons.get(i);
                if (weaponElement == null || !weaponElement.isJsonObject()) {
                    continue;
                }
                JsonObject weaponObj = weaponElement.getAsJsonObject();
                if (!GsonHelper.getAsBoolean(weaponObj, "modding_only_multi", false)) {
                    continue;
                }
                if (!weaponObj.has("ids") || !weaponObj.get("ids").isJsonArray() || weaponObj.getAsJsonArray("ids").size() < 2) {
                    continue;
                }
                out.computeIfAbsent(partId, key -> new LinkedHashSet<>()).add(i);
            }
        }
        return out;
    }

    private static Map<String, Set<Integer>> parseMergeIntoPreviousSlots(JsonObject obj) {
        if (!obj.has("parts") || !obj.get("parts").isJsonArray()) {
            return Map.of();
        }
        Map<String, Set<Integer>> out = new LinkedHashMap<>();
        JsonArray parts = obj.getAsJsonArray("parts");
        for (JsonElement partElement : parts) {
            if (partElement == null || !partElement.isJsonObject()) {
                continue;
            }
            JsonObject partObj = partElement.getAsJsonObject();
            String type = GsonHelper.getAsString(partObj, "type", "");
            if (!type.contains("weapon")) {
                continue;
            }
            String partId = GsonHelper.getAsString(partObj, "id", "").trim();
            if (partId.isEmpty() || !partObj.has("weapons") || !partObj.get("weapons").isJsonArray()) {
                continue;
            }
            JsonArray weapons = partObj.getAsJsonArray("weapons");
            for (int i = 0; i < weapons.size(); i++) {
                JsonElement weaponElement = weapons.get(i);
                if (weaponElement == null || !weaponElement.isJsonObject()) {
                    continue;
                }
                JsonObject weaponObj = weaponElement.getAsJsonObject();
                if (!GsonHelper.getAsBoolean(weaponObj, "merge_into_previous_slot", false)) {
                    continue;
                }
                if (i <= 0) {
                    continue;
                }
                out.computeIfAbsent(partId, key -> new LinkedHashSet<>()).add(i);
            }
        }
        return out;
    }

    private static Map<String, Set<Integer>> immutableIndexMap(Map<String, Set<Integer>> input) {
        Map<String, Set<Integer>> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
        return Map.copyOf(copy);
    }

    public record ModdingMultiEntry(String partId, int weaponIndex) {}

    public record VehicleExtendedConfig(
            Set<String> groundContactPartIds,
            Map<String, Set<Integer>> moddingOnlyMultiByPartId,
            Map<String, Set<Integer>> mergeIntoPreviousSlotsByPartId
    ) {
        public static final VehicleExtendedConfig EMPTY = new VehicleExtendedConfig(Set.of(), Map.of(), Map.of());

        public boolean isEnabled() {
            return !groundContactPartIds.isEmpty()
                    || !moddingOnlyMultiByPartId.isEmpty()
                    || !mergeIntoPreviousSlotsByPartId.isEmpty();
        }

        public boolean isModdingOnlyMulti(String partId, int weaponIndex) {
            Set<Integer> indexes = moddingOnlyMultiByPartId.get(partId);
            return indexes != null && indexes.contains(weaponIndex);
        }

        public boolean isMergeIntoPreviousSlot(String partId, int weaponIndex) {
            Set<Integer> indexes = mergeIntoPreviousSlotsByPartId.get(partId);
            return indexes != null && indexes.contains(weaponIndex);
        }

        public boolean isGroupedSlotCarrier(String partId, int weaponIndex) {
            return isModdingOnlyMulti(partId, weaponIndex) && isMergeIntoPreviousSlot(partId, weaponIndex + 1);
        }

        public boolean shouldBlockRuntimeMultiCycle(String partId, int weaponIndex) {
            return isModdingOnlyMulti(partId, weaponIndex) && !isGroupedSlotCarrier(partId, weaponIndex);
        }

        public boolean hasGroupedWeaponSlots(String partId) {
            Set<Integer> indexes = mergeIntoPreviousSlotsByPartId.get(partId);
            return indexes != null && !indexes.isEmpty();
        }
    }
}
