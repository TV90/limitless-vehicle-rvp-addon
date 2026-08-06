package org.ywzj.rvp.vehicle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * APS（主动防护系统）弹药/冷却/动画游标的独立存档（挂在主世界 SavedData 上）。
 *
 * <p>载具自身 NBT（{@code addAdditionalSaveData}）无法注入（无 mixin），因此把
 * {@code UUID → APS 状态} 存到独立存档：载具加入世界时恢复进
 * {@link RVP_ApsRuntimeManager}，离开时写回。</p>
 */
public class RVP_ApsStateSavedData extends SavedData {

    private static final String DATA_NAME = "rvp_aps_state";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_AMMO = "ammo";
    private static final String KEY_RELOAD = "reload";
    private static final String KEY_COOLDOWN = "cooldown";
    private static final String KEY_CURSOR = "cursor";

    private final Map<UUID, int[]> states = new HashMap<>();

    public static RVP_ApsStateSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                RVP_ApsStateSavedData::load, RVP_ApsStateSavedData::new, DATA_NAME);
    }

    public static RVP_ApsStateSavedData load(CompoundTag tag) {
        RVP_ApsStateSavedData data = new RVP_ApsStateSavedData();
        ListTag entries = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.contains(KEY_UUID)) {
                continue;
            }
            UUID uuid = entry.getUUID(KEY_UUID);
            int ammo = Math.max(0, entry.getInt(KEY_AMMO));
            int reload = Math.max(0, entry.getInt(KEY_RELOAD));
            int cooldown = Math.max(0, entry.getInt(KEY_COOLDOWN));
            int cursor = Math.max(0, entry.getInt(KEY_CURSOR));
            data.states.put(uuid, new int[]{ammo, reload, cooldown, cursor});
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (var entry : states.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID(KEY_UUID, entry.getKey());
            int[] v = entry.getValue();
            e.putInt(KEY_AMMO, v[0]);
            e.putInt(KEY_RELOAD, v[1]);
            e.putInt(KEY_COOLDOWN, v[2]);
            e.putInt(KEY_CURSOR, v[3]);
            entries.add(e);
        }
        tag.put(KEY_ENTRIES, entries);
        return tag;
    }

    public void writeEntry(UUID vehicleId, @Nullable int[] state) {
        if (state == null) {
            states.remove(vehicleId);
        } else {
            states.put(vehicleId, state);
        }
        setDirty();
    }

    @Nullable
    public int[] readEntry(UUID vehicleId) {
        return states.get(vehicleId);
    }
}
