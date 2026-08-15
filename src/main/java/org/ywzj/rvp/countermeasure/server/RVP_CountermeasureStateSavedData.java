package org.ywzj.rvp.countermeasure.server;

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
 * 干扰物剩余/装填进度独立存档（挂在主世界 SavedData 上）。
 *
 * <p>载具自身 NBT 无法注入（无 mixin），因此把
 * {@code UUID → [flare剩余, flare装填进度, chaff剩余, chaff装填进度, smoke剩余, smoke装填进度]}
 * 存到独立存档：载具加入世界时恢复，离开时写回。对齐 {@code RVP_ApsStateSavedData}。</p>
 */
public class RVP_CountermeasureStateSavedData extends SavedData {

    private static final String DATA_NAME = "rvp_countermeasure_state";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_FLARE_REMAIN = "flare_remain";
    private static final String KEY_FLARE_RELOAD = "flare_reload";
    private static final String KEY_CHAFF_REMAIN = "chaff_remain";
    private static final String KEY_CHAFF_RELOAD = "chaff_reload";
    private static final String KEY_SMOKE_REMAIN = "smoke_remain";
    private static final String KEY_SMOKE_RELOAD = "smoke_reload";

    private final Map<UUID, int[]> states = new HashMap<>();

    public static RVP_CountermeasureStateSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                RVP_CountermeasureStateSavedData::load,
                RVP_CountermeasureStateSavedData::new,
                DATA_NAME);
    }

    public static RVP_CountermeasureStateSavedData load(CompoundTag tag) {
        RVP_CountermeasureStateSavedData data = new RVP_CountermeasureStateSavedData();
        ListTag entries = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.contains(KEY_UUID)) {
                continue;
            }
            UUID uuid = entry.getUUID(KEY_UUID);
            data.states.put(uuid, new int[]{
                    Math.max(0, entry.getInt(KEY_FLARE_REMAIN)),
                    Math.max(0, entry.getInt(KEY_FLARE_RELOAD)),
                    Math.max(0, entry.getInt(KEY_CHAFF_REMAIN)),
                    Math.max(0, entry.getInt(KEY_CHAFF_RELOAD)),
                    Math.max(0, entry.getInt(KEY_SMOKE_REMAIN)),
                    Math.max(0, entry.getInt(KEY_SMOKE_RELOAD))
            });
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
            int smokeRemain = v.length > 4 ? v[4] : 0;
            int smokeReload = v.length > 5 ? v[5] : 0;
            e.putInt(KEY_FLARE_REMAIN, v[0]);
            e.putInt(KEY_FLARE_RELOAD, v[1]);
            e.putInt(KEY_CHAFF_REMAIN, v[2]);
            e.putInt(KEY_CHAFF_RELOAD, v[3]);
            e.putInt(KEY_SMOKE_REMAIN, smokeRemain);
            e.putInt(KEY_SMOKE_RELOAD, smokeReload);
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
