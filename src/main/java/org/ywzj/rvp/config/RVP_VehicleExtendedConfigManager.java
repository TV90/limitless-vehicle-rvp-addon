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
import org.jetbrains.annotations.Nullable;
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
    /** 原始载具 JSON（含 rvp_custom_mounts / modding_only_multi 等 RVP 扩展字段），供服务端向客户端同步。 */
    private Map<ResourceLocation, JsonElement> rawVehicleJson = Map.of();

    private RVP_VehicleExtendedConfigManager() {}

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
        rawVehicleJson = Map.copyOf(map);
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

    /**
     * 供客户端在收到服务端同步的配置包（{@code S2CVehicleRvpConfig}）后填充配置。
     * 专用服务器下客户端不触发 {@link AddReloadListenerEvent}，改装工具/挂架等功能依赖该配置，
     * 故必须复用同一套解析逻辑。
     */
    public void applyFromJsonMap(Map<ResourceLocation, JsonElement> jsonMap) {
        apply(jsonMap, null, null);
    }

    /** 服务端用于向客户端同步的原始载具 JSON（仅服务端有完整数据）。 */
    public Map<ResourceLocation, JsonElement> getRawVehicleJson() {
        return rawVehicleJson;
    }

    /** 已配置（非 EMPTY）的载具 id 集合，服务端同步时据此过滤载荷。 */
    public Set<ResourceLocation> getConfiguredVehicleIds() {
        return configs.keySet();
    }

    public VehicleExtendedConfig get(AbstractVehicle vehicle) {
        return vehicle == null ? VehicleExtendedConfig.EMPTY : configs.getOrDefault(vehicle.getVehicleId(), VehicleExtendedConfig.EMPTY);
    }

    public boolean hasGroundContact(AbstractVehicle vehicle) {
        return !get(vehicle).groundContactPartIds().isEmpty();
    }

    /** 改装换弹允许的最大载具速度（km/h）：低于该值才可更换弹种。 */
    public static final double MAX_MODDING_SPEED_KPH = 5.0;

    /**
     * 载具是否有玩家或 gunner 乘员。
     * 目的：作为改装类交互的"有人"判定单一语义源，供 {@link #canModVehicle} 与
     * 潜行右键改装守卫 {@code RVP_ModdingInteractGuard} 共用，避免两处判定漂移。
     */
    public boolean hasPilotOrGunnerPassenger(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return false;
        }
        for (net.minecraft.world.entity.Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof net.minecraft.world.entity.player.Player
                    || passenger instanceof org.ywzj.rvp.entity.gunner.GunnerEntity) {
                return true;
            }
        }
        return false;
    }

    /**
     * 改装换弹条件校验：仅当载具速度低于 {@link #MAX_MODDING_SPEED_KPH} 且
     * 载具上无玩家或 gunner 乘员时才允许更换武器（服务端权威校验 + 客户端按钮显隐共用）。
     */
    public boolean canModVehicle(AbstractVehicle vehicle) {
        if (vehicle == null) {
            return false;
        }
        // block/tick -> km/h：1 block = 1 m，20 tick/s，×3.6 → ×72
        double speedKph = vehicle.getDeltaMovement().length() * 72.0;
        if (speedKph >= MAX_MODDING_SPEED_KPH) {
            return false;
        }
        return !hasPilotOrGunnerPassenger(vehicle);
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

    /**
     * 判断 F 键多弹种循环是否应被拦截。
     * <p>
     * 普通 {@code modding_only_multi} 槽（如 LAV25 / ZBL08A）：F 键一律拦截，组内子武器只能由改装工具切换。
     * grouped slot carrier（如 T90M 的 AP 组 + HE 组 {@code merge_into_previous_slot}）：装配器已把 AP/HE 合并为
     * 外层 {@code VehicleMultiWeapons}，此处直接放行，由本体 {@code cycleMultiWeapon} → 外层 multi 的
     * {@code cycleSubWeapon} 完成 AP ↔ HE 大组切换；组内子武器仍由改装工具选择。
     * </p>
     * <p>
     * 除了当前武器索引本身，还需检查代理展开后的多武器：炮塔武器栏里放导弹代理时，
     * F 键在炮塔上展开的是 missile 部件的 multi，若只按炮塔索引判定会漏拦，
     * 因此按该 multi 真实所属部件的配置复核。
     * </p>
     */
    public boolean shouldBlockCurrentMultiCycle(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return false;
        }
        if (isGroupedSlotCarrier(weaponUnit.getVehicle(), weaponUnit.getId(), weaponUnit.getCurrentWeaponIndex())) {
            return false;
        }
        if (shouldBlockRuntimeMultiCycle(weaponUnit, weaponUnit.getCurrentWeaponIndex())) {
            return true;
        }
        AbstractVehicleWeapon<?> current = weaponUnit.getCurrentWeapon().orElse(null);
        if (current instanceof VehicleMultiWeapons multi) {
            WeaponUnit owner = multi.getWeaponUnit();
            if (owner != null && owner != weaponUnit) {
                if (isGroupedSlotCarrier(owner.getVehicle(), owner.getId(), owner.getCurrentWeaponIndex())) {
                    return false;
                }
                return shouldBlockRuntimeMultiCycle(owner, owner.getCurrentWeaponIndex());
            }
        }
        return false;
    }

    public boolean hasGroupedWeaponSlots(AbstractVehicle vehicle, String partId) {
        if (vehicle == null || partId == null || partId.isBlank()) {
            return false;
        }
        return get(vehicle).hasGroupedWeaponSlots(partId);
    }

    public boolean isGroupedSlotCarrier(AbstractVehicle vehicle, String partId, int weaponIndex) {
        if (vehicle == null || partId == null || partId.isBlank() || weaponIndex < 0) {
            return false;
        }
        return get(vehicle).isGroupedSlotCarrier(partId, weaponIndex);
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
        VehicleMultiWeapons nestedCarrier = findNestedModdingCarrier(topMulti);
        return nestedCarrier != null ? nestedCarrier : topMulti;
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

    /**
     * 改装界面分栏条目：把 grouped slot carrier（如 T90M/VT4 的 AP 组 + HE 组并槽）展开为
     * <b>每个分组合一栏</b>，而不是整个合并槽只显示一个栏。
     *
     * <p>展开规则：</p>
     * <ul>
     *   <li>普通 {@code modding_only_multi} 槽（如 LAV25/ZBL08A）：一条目，{@code groupIndex = -1}，
     *       行为与 {@link #getModdingOnlyEntries} 一致；</li>
     *   <li>grouped slot carrier（载波槽 {@code modding_only_multi} + 后续 {@code merge_into_previous_slot}
     *       槽）：载波槽展开为第 0 组，其后每个并入槽依次为第 1、2… 组——组序号即外层
     *       {@code VehicleMultiWeapons} 的子武器下标。</li>
     * </ul>
     *
     * @param groupId 配置里的 {@code save_id}（如 {@code main_gun_ap}），供界面作分栏标题；可能为 null
     */
    public List<ModdingGroupEntry> getModdingGroupEntries(AbstractVehicle vehicle) {
        VehicleExtendedConfig cfg = get(vehicle);
        List<ModdingGroupEntry> out = new ArrayList<>();
        cfg.moddingOnlyMultiByPartId().forEach((partId, weaponIndexes) -> {
            for (Integer weaponIndex : weaponIndexes) {
                if (cfg.isGroupedSlotCarrier(partId, weaponIndex)) {
                    out.add(new ModdingGroupEntry(partId, weaponIndex, 0, cfg.saveId(partId, weaponIndex)));
                    int cursor = weaponIndex + 1;
                    int group = 1;
                    while (cfg.isMergeIntoPreviousSlot(partId, cursor)) {
                        out.add(new ModdingGroupEntry(partId, weaponIndex, group, cfg.saveId(partId, cursor)));
                        cursor++;
                        group++;
                    }
                } else {
                    out.add(new ModdingGroupEntry(partId, weaponIndex, -1, cfg.saveId(partId, weaponIndex)));
                }
            }
        });
        return out;
    }

    private static VehicleExtendedConfig parseVehicle(JsonObject obj) {
        Set<String> groundContactPartIds = parseGroundContactPartIds(obj);
        ResourceLocation structureModel = parseStructureModel(obj);
        Set<String> physicsOnlyBones = parsePhysicsOnlyBones(obj);
        Map<String, Set<Integer>> moddingOnlyMulti = parseModdingOnlyMulti(obj);
        Map<String, Set<Integer>> mergeIntoPreviousSlots = parseMergeIntoPreviousSlots(obj);
        Map<String, Map<Integer, String>> saveIds = parseWeaponSaveIds(obj);
        if (groundContactPartIds.isEmpty()
                && physicsOnlyBones.isEmpty()
                && moddingOnlyMulti.isEmpty()
                && mergeIntoPreviousSlots.isEmpty()) {
            return VehicleExtendedConfig.EMPTY;
        }
        return new VehicleExtendedConfig(
                Set.copyOf(groundContactPartIds),
                structureModel,
                Set.copyOf(physicsOnlyBones),
                immutableIndexMap(moddingOnlyMulti),
                immutableIndexMap(mergeIntoPreviousSlots),
                immutableSaveIdMap(saveIds)
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

    private static ResourceLocation parseStructureModel(JsonObject obj) {
        String structureModel = GsonHelper.getAsString(obj, "structure_model", "").trim();
        if (structureModel.isEmpty()) {
            return null;
        }
        return ResourceLocation.tryParse(structureModel);
    }

    private static Set<String> parsePhysicsOnlyBones(JsonObject obj) {
        if (!obj.has("physics_info") || !obj.get("physics_info").isJsonObject()) {
            return Set.of();
        }
        JsonObject physicsObj = obj.getAsJsonObject("physics_info");
        Set<String> bones = new LinkedHashSet<>();
        if (physicsObj.has("physics_only_bone") && physicsObj.get("physics_only_bone").isJsonPrimitive()) {
            String bone = physicsObj.get("physics_only_bone").getAsString().trim();
            if (!bone.isEmpty()) {
                bones.add(bone);
            }
        }
        if (physicsObj.has("physics_only_bones") && physicsObj.get("physics_only_bones").isJsonArray()) {
            JsonArray array = physicsObj.getAsJsonArray("physics_only_bones");
            for (JsonElement element : array) {
                if (element == null || !element.isJsonPrimitive()) {
                    continue;
                }
                String bone = element.getAsString().trim();
                if (!bone.isEmpty()) {
                    bones.add(bone);
                }
            }
        }
        return bones;
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

    private static Map<String, Map<Integer, String>> immutableSaveIdMap(Map<String, Map<Integer, String>> input) {
        Map<String, Map<Integer, String>> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
        return Map.copyOf(copy);
    }

    /** 解析 parts→weapons 各条目的 {@code save_id}（存档标签，用作改装界面的分组标题）。 */
    private static Map<String, Map<Integer, String>> parseWeaponSaveIds(JsonObject obj) {
        if (!obj.has("parts") || !obj.get("parts").isJsonArray()) {
            return Map.of();
        }
        Map<String, Map<Integer, String>> out = new LinkedHashMap<>();
        JsonArray parts = obj.getAsJsonArray("parts");
        for (JsonElement partElement : parts) {
            if (partElement == null || !partElement.isJsonObject()) {
                continue;
            }
            JsonObject partObj = partElement.getAsJsonObject();
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
                String saveId = GsonHelper.getAsString(weaponElement.getAsJsonObject(), "save_id", "").trim();
                if (!saveId.isEmpty()) {
                    out.computeIfAbsent(partId, key -> new LinkedHashMap<>()).put(i, saveId);
                }
            }
        }
        return out;
    }

    private static VehicleMultiWeapons findNestedModdingCarrier(VehicleMultiWeapons topMulti) {
        for (AbstractVehicleWeapon<?> subWeapon : topMulti.getSubWeapons()) {
            if (subWeapon instanceof VehicleMultiWeapons nested) {
                return nested;
            }
        }
        return null;
    }

    public record ModdingMultiEntry(String partId, int weaponIndex) {}

    /** 改装界面分组条目：{@code groupIndex = -1} 为整槽单组；≥0 为分组载波外层 multi 的子武器下标。 */
    public record ModdingGroupEntry(String partId, int weaponIndex, int groupIndex, String groupId) {}

    public record VehicleExtendedConfig(
            Set<String> groundContactPartIds,
            ResourceLocation structureModel,
            Set<String> physicsOnlyBones,
            Map<String, Set<Integer>> moddingOnlyMultiByPartId,
            Map<String, Set<Integer>> mergeIntoPreviousSlotsByPartId,
            Map<String, Map<Integer, String>> saveIdsByPartId
    ) {
        public static final VehicleExtendedConfig EMPTY = new VehicleExtendedConfig(
                Set.of(), null, Set.of(), Map.of(), Map.of(), Map.of());

        /** 指定槽位配置的 {@code save_id}；未配置返回 null。 */
        @Nullable
        public String saveId(String partId, int weaponIndex) {
            Map<Integer, String> byIndex = saveIdsByPartId.get(partId);
            return byIndex == null ? null : byIndex.get(weaponIndex);
        }

        public boolean isEnabled() {
            return !groundContactPartIds.isEmpty()
                    || !physicsOnlyBones.isEmpty()
                    || !moddingOnlyMultiByPartId.isEmpty()
                    || !mergeIntoPreviousSlotsByPartId.isEmpty();
        }

        public boolean hasPhysicsOnlyBones() {
            return structureModel != null && !physicsOnlyBones.isEmpty();
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

        /**
         * modding_only_multi 槽一律拦截 F 键 MULTI 循环（组内子武器只能由改装工具切换）。
         * grouped slot carrier（如 T90M 的 AP 组）拦截后由调用方重定向为武器槽切换（AP ↔ HE）。
         */
        public boolean shouldBlockRuntimeMultiCycle(String partId, int weaponIndex) {
            return isModdingOnlyMulti(partId, weaponIndex);
        }

        public boolean hasGroupedWeaponSlots(String partId) {
            Set<Integer> indexes = mergeIntoPreviousSlotsByPartId.get(partId);
            return indexes != null && !indexes.isEmpty();
        }
    }
}
