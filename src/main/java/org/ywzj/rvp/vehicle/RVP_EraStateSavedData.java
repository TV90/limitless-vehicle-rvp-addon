package org.ywzj.rvp.vehicle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ERA 失效骨块状态的独立存档（挂在主世界 SavedData 上）。
 *
 * <p>载具自身 NBT（{@code addAdditionalSaveData}）无法注入（无 mixin），因此把
 * {@code UUID → 失效骨块列表} 存到独立存档：载具加入世界时恢复进
 * {@link RVP_EraStateTable}，离开时写回。</p>
 */
public class RVP_EraStateSavedData extends SavedData {

    private static final String DATA_NAME = "rvp_era_state";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_BONES = "bones";

    private final Map<UUID, List<String>> inactiveBones = new HashMap<>();

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
            ListTag bones = entry.getList(KEY_BONES, Tag.TAG_STRING);
            List<String> list = new ArrayList<>();
            for (int j = 0; j < bones.size(); j++) {
                list.add(bones.getString(j));
            }
            if (!list.isEmpty()) {
                data.inactiveBones.put(uuid, list);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (var entry : inactiveBones.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID(KEY_UUID, entry.getKey());
            ListTag bones = new ListTag();
            for (String bone : entry.getValue()) {
                bones.add(StringTag.valueOf(bone));
            }
            e.put(KEY_BONES, bones);
            entries.add(e);
        }
        tag.put(KEY_ENTRIES, entries);
        return tag;
    }

    public void writeEntry(UUID vehicleId, @Nullable Set<String> bones) {
        if (bones == null || bones.isEmpty()) {
            inactiveBones.remove(vehicleId);
        } else {
            inactiveBones.put(vehicleId, new ArrayList<>(bones));
        }
        setDirty();
    }

    @Nullable
    public List<String> readEntry(UUID vehicleId) {
        return inactiveBones.get(vehicleId);
    }
}
