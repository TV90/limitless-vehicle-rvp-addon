package org.ywzj.rvp.ecm;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 主动ECM状态独立存档（挂在主世界 SavedData 上）。
 *
 * <p>与 {@link org.ywzj.rvp.vehicle.RVP_DircmStateSavedData} 同机制：把
 * {@code UUID → [active, cooldown, armPriority]} 存到独立存档，
 * 载具加入世界时恢复进 {@link RVP_EcmActiveManager}，离开时写回。</p>
 */
public class RVP_EcmActiveStateSavedData extends SavedData {

    private static final String DATA_NAME = "rvp_ecm_active_state";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_STATE = "state";

    private final Map<UUID, int[]> states = new HashMap<>();

    public static RVP_EcmActiveStateSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                RVP_EcmActiveStateSavedData::load, RVP_EcmActiveStateSavedData::new, DATA_NAME);
    }

    public static RVP_EcmActiveStateSavedData load(CompoundTag tag) {
        RVP_EcmActiveStateSavedData data = new RVP_EcmActiveStateSavedData();
        ListTag entries = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.contains(KEY_UUID)) {
                continue;
            }
            UUID uuid = entry.getUUID(KEY_UUID);
            int[] state = entry.getIntArray(KEY_STATE);
            if (state == null || state.length != 3) {
                continue;
            }
            data.states.put(uuid, state);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (var entry : states.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID(KEY_UUID, entry.getKey());
            e.putIntArray(KEY_STATE, entry.getValue());
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
