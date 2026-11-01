package org.ywzj.rvp.uav;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 记录明确永久移除的联动实体 UUID，解决另一端未加载时实体 NBT 无法互相清链的问题。 */
public final class RVP_UavTerminalEntitySavedData extends SavedData {
    private static final String DATA_NAME = "rvp_uav_terminal_entities";
    private final Set<UUID> terminalUuids = new HashSet<>();

    public static RVP_UavTerminalEntitySavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                RVP_UavTerminalEntitySavedData::load,
                RVP_UavTerminalEntitySavedData::new,
                DATA_NAME);
    }

    public static RVP_UavTerminalEntitySavedData load(CompoundTag tag) {
        RVP_UavTerminalEntitySavedData data = new RVP_UavTerminalEntitySavedData();
        ListTag list = tag.getList("uuids", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("uuid")) data.terminalUuids.add(entry.getUUID("uuid"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (UUID uuid : terminalUuids) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("uuid", uuid);
            list.add(entry);
        }
        tag.put("uuids", list);
        return tag;
    }

    public void markTerminal(UUID uuid) {
        if (uuid != null && terminalUuids.add(uuid)) setDirty();
    }

    public boolean isTerminal(UUID uuid) {
        return uuid != null && terminalUuids.contains(uuid);
    }
}
