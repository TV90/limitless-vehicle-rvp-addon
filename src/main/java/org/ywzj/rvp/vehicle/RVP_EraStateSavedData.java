package org.ywzj.rvp.vehicle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 骨骼模块失效状态的独立存档（挂在主世界 SavedData 上）。
 *
 * <p>载具自身 NBT（{@code addAdditionalSaveData}）无法注入（无 mixin），因此把
 * {@code UUID → (骨块名 → 失效模块集合)} 存到独立存档：载具加入世界时恢复进
 * {@link RVP_BoneModuleStateTable}，离开时写回。</p>
 *
 * <p>兼容旧版 ERA 存档：旧格式 {@code bones} 为纯字符串列表（骨块名，默认仅 ERA 模块），
 * 读取时自动映射为 {@code {骨块名: [ERA]}}；写出一律使用新格式。</p>
 */
public class RVP_EraStateSavedData extends SavedData {

    private static final String DATA_NAME = "rvp_era_state";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_BONES = "bones";
    /** 新格式：单个骨块条目里的骨块名。 */
    private static final String KEY_BONE_NAME = "name";
    /** 新格式：单个骨块条目里的失效模块类型列表（字符串枚举名）。 */
    private static final String KEY_TYPES = "types";

    private final Map<UUID, Map<String, Set<BoneModuleType>>> inactiveModules = new HashMap<>();

    public static RVP_EraStateSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                RVP_EraStateSavedData::load, RVP_EraStateSavedData::new, DATA_NAME);
    }

    public static RVP_EraStateSavedData load(CompoundTag tag) {
        RVP_EraStateSavedData data = new RVP_EraStateSavedData();
        ListTag entries = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.contains(KEY_UUID)) {
                continue;
            }
            UUID uuid = entry.getUUID(KEY_UUID);
            ListTag bones = entry.getList(KEY_BONES, Tag.TAG_COMPOUND);
            Map<String, Set<BoneModuleType>> boneMap = new HashMap<>();
            if (!bones.isEmpty()) {
                // 新格式：{name, types}
                for (int j = 0; j < bones.size(); j++) {
                    CompoundTag boneEntry = bones.getCompound(j);
                    String boneName = boneEntry.getString(KEY_BONE_NAME);
                    if (boneName == null || boneName.isBlank()) {
                        continue;
                    }
                    Set<BoneModuleType> types = new HashSet<>();
                    ListTag typeList = boneEntry.getList(KEY_TYPES, Tag.TAG_STRING);
                    for (int k = 0; k < typeList.size(); k++) {
                        BoneModuleType type = BoneModuleType.byName(typeList.getString(k));
                        if (type != null) {
                            types.add(type);
                        }
                    }
                    if (types.isEmpty()) {
                        // 类型列表缺失/未知时默认视为 ERA（保持旧语义）
                        types.add(BoneModuleType.ERA);
                    }
                    boneMap.put(boneName.trim(), types);
                }
            } else {
                // 旧格式兼容：bones 为纯字符串列表（骨块名），默认仅 ERA 模块
                ListTag legacyBones = entry.getList(KEY_BONES, Tag.TAG_STRING);
                for (int j = 0; j < legacyBones.size(); j++) {
                    String boneName = legacyBones.getString(j);
                    if (boneName == null || boneName.isBlank()) {
                        continue;
                    }
                    Set<BoneModuleType> types = new HashSet<>();
                    types.add(BoneModuleType.ERA);
                    boneMap.put(boneName.trim(), types);
                }
            }
            if (!boneMap.isEmpty()) {
                data.inactiveModules.put(uuid, boneMap);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (var entry : inactiveModules.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID(KEY_UUID, entry.getKey());
            ListTag bones = new ListTag();
            for (var boneEntry : entry.getValue().entrySet()) {
                CompoundTag boneTag = new CompoundTag();
                boneTag.putString(KEY_BONE_NAME, boneEntry.getKey());
                ListTag types = new ListTag();
                for (BoneModuleType type : boneEntry.getValue()) {
                    types.add(StringTag.valueOf(type.name()));
                }
                boneTag.put(KEY_TYPES, types);
                bones.add(boneTag);
            }
            e.put(KEY_BONES, bones);
            entries.add(e);
        }
        tag.put(KEY_ENTRIES, entries);
        return tag;
    }

    public void writeEntry(UUID vehicleId, @Nullable Map<String, Set<BoneModuleType>> bones) {
        if (bones == null || bones.isEmpty()) {
            inactiveModules.remove(vehicleId);
        } else {
            inactiveModules.put(vehicleId, bones);
        }
        setDirty();
    }

    @Nullable
    public Map<String, Set<BoneModuleType>> readEntry(UUID vehicleId) {
        return inactiveModules.get(vehicleId);
    }
}
