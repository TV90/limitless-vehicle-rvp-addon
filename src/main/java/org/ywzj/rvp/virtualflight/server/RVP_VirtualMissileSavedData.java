package org.ywzj.rvp.virtualflight.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Minecraft 按维度持久化的虚拟导弹容器。
 *
 * <p>容器只负责 NBT 生命周期和 UUID Map 原子增删；积分与恢复全部由
 * {@link RVP_VirtualMissileManager} 驱动。停服期间不推进飞行时间，加载本类也不会申请区块。</p>
 */
public final class RVP_VirtualMissileSavedData extends SavedData {
    /** SavedData 根标签 schema 版本。 */
    static final int SAVED_DATA_SCHEMA_VERSION = 1;
    /** 每个维度 data 目录中的稳定文件名。 */
    private static final String FILE_NAME = "rvp_virtual_missiles";
    /** 保持插入顺序的 UUID 权威记录表，便于稳定保存和诊断。 */
    private final Map<UUID, RVP_VirtualMissileState> states = new LinkedHashMap<>();

    /**
     * 从指定维度 DataStorage 取得或创建容器。
     *
     * @param level 所属服务端维度
     */
    public static RVP_VirtualMissileSavedData get(ServerLevel level) {
        // 原版 DataStorage 在首次访问时调用 load，之后复用同一内存实例。
        return level.getDataStorage().computeIfAbsent(
                RVP_VirtualMissileSavedData::load, RVP_VirtualMissileSavedData::new, FILE_NAME);
    }

    /**
     * 解码 SavedData 根标签；不支持的根版本返回空容器，不尝试旧 schema 迁移。
     */
    public static RVP_VirtualMissileSavedData load(CompoundTag root) {
        RVP_VirtualMissileSavedData data = new RVP_VirtualMissileSavedData();
        if (root.getInt("schemaVersion") != SAVED_DATA_SCHEMA_VERSION) return data;
        ListTag list = root.getList("missiles", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            // 每条记录交给自身 codec 校验，损坏记录不会阻断同文件其他导弹加载。
            RVP_VirtualMissileState.load(list.getCompound(i)).ifPresent(state ->
                    data.states.putIfAbsent(state.flightUuid, state));
        }
        return data;
    }

    /** 将当前有效记录编码为根 CompoundTag，由原版存档调度决定落盘时机。 */
    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("schemaVersion", SAVED_DATA_SCHEMA_VERSION);
        ListTag list = new ListTag();
        // 调用单条状态 codec，确保状态版本随记录一起保存。
        for (RVP_VirtualMissileState state : states.values()) list.add(state.save());
        root.put("missiles", list);
        return root;
    }

    /** @return 当前维度权威记录的只读迭代视图；管理器会先复制再删除。 */
    public Collection<RVP_VirtualMissileState> states() { return states.values(); }
    /** @return 当前维度是否已占用指定飞行 UUID。 */
    public boolean contains(UUID uuid) { return states.containsKey(uuid); }
    /**
     * 以 UUID 原子注册记录并标脏。
     *
     * @return UUID 未被占用且注册成功时为 true
     */
    public boolean add(RVP_VirtualMissileState state) {
        if (states.putIfAbsent(state.flightUuid, state) != null) return false;
        setDirty();
        return true;
    }
    /** 删除指定 UUID 记录；只有实际删除时才标脏。 */
    public void remove(UUID uuid) {
        if (states.remove(uuid) != null) setDirty();
    }
    /** @return 当前维度的虚拟导弹数量。 */
    public int size() { return states.size(); }
}
