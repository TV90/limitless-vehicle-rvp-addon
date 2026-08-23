package org.ywzj.rvp.vehicle;

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
 * DIRCM 照射通道状态的独立存档（挂在主世界 SavedData 上）。
 *
 * <p>与 {@link RVP_ApsStateSavedData} 同机制：把 {@code UUID → DIRCM 各通道状态} 存到独立存档，
 * 载具加入世界时恢复进 {@code RVP_DircmRuntimeManager}，离开时写回。
 * 通道结构：{@code [通道数, (busyTargetId, beamRemainTick, chargeRemainTick)…]}。</p>
 */
public class RVP_DircmStateSavedData extends SavedData {

    private static final String DATA_NAME = "rvp_dircm_state";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_CHANNELS = "channels";

    private final Map<UUID, int[]> states = new HashMap<>();

    public static RVP_DircmStateSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                RVP_DircmStateSavedData::load, RVP_DircmStateSavedData::new, DATA_NAME);
    }

    public static RVP_DircmStateSavedData load(CompoundTag tag) {
        RVP_DircmStateSavedData data = new RVP_DircmStateSavedData();
        ListTag entries = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.contains(KEY_UUID)) {
                continue;
            }
            UUID uuid = entry.getUUID(KEY_UUID);
            int[] channels = entry.getIntArray(KEY_CHANNELS);
            if (channels == null || channels.length == 0) {
                continue;
            }
            // 忙碌目标不跨世界存活：恢复时把 busyTargetId 清为 -1，仅保留充能
            for (int j = 0; j < channels.length; j += 3) {
                channels[j] = -1;
            }
            data.states.put(uuid, channels);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (var entry : states.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID(KEY_UUID, entry.getKey());
            e.putIntArray(KEY_CHANNELS, entry.getValue());
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

    public int[] readEntry(UUID vehicleId) {
        return states.get(vehicleId);
    }
}