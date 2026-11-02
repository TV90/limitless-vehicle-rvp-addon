package org.ywzj.rvp.uav;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 保存母车级候选选择、部署租约和冷却；全部维度统一写入 overworld SavedData。 */
public final class RVP_DeployableUavStateSavedData extends SavedData {
    /** SavedData 文件稳定名称。 */
    private static final String DATA_NAME = "rvp_deployable_uav_state";
    /** 按母车 UUID 保存的选择、冷却和部署租约。 */
    private final Map<UUID, Entry> entries = new HashMap<>();
    /** 未确认到达返回点的玩家；掉线和重启不丢弃，也不释放对应部署租约。 */
    private final Map<UUID, CompoundTag> pendingReturns = new HashMap<>();

    public void putPendingReturn(UUID player, CompoundTag request) {
        pendingReturns.put(player, request.copy());
        setDirty();
    }

    public void clearPendingReturn(UUID player) {
        if (pendingReturns.remove(player) != null) setDirty();
    }

    public Map<UUID, CompoundTag> pendingReturns() {
        Map<UUID, CompoundTag> copy = new HashMap<>();
        pendingReturns.forEach((id, tag) -> copy.put(id, tag.copy()));
        return copy;
    }

    /** 仅真实提交终结的子机可在重连后强制解除旧骑乘关系。 */
    public boolean isTerminating(UUID child) {
        return entries.values().stream().anyMatch(entry -> child.equals(entry.childUuid) && entry.cooldownStartedForChild);
    }

    public boolean hasPendingReturn(UUID child) {
        return pendingReturns.values().stream().anyMatch(tag -> tag.hasUUID("child")
                && child.equals(tag.getUUID("child")));
    }

    /** 已提交终结且回传完成的租约；实体已移除时也能按 UUID 收尾。 */
    public Map<UUID, UUID> readyTerminations() {
        Map<UUID, UUID> ready = new HashMap<>();
        entries.forEach((parent, entry) -> {
            if (entry.childUuid != null && entry.cooldownStartedForChild && !hasPendingReturn(entry.childUuid))
                ready.put(parent, entry.childUuid);
        });
        return ready;
    }

    /** 母车当前选项和权威部署/冷却状态。
     * @param selectedOptionId 当前稳定候选 ID
     * @param revision 客户端请求的乐观并发版本
     * @param remainingCooldownTick 剩余冷却 tick
     * @param deployed 是否存在活动子机租约
     * @param lockedReason 部署、回传、终结或冷却锁原因
     */
    public record StateSnapshot(String selectedOptionId, long revision, int remainingCooldownTick,
                                boolean deployed, String lockedReason) {}

    /** 单台母车的持久化状态。 */
    private static final class Entry {
        /** 当前选择的稳定候选 ID；空值回退车型默认项。 */
        String selectedOptionId = "";
        /** 选择或锁状态发生变更时递增的 revision。 */
        long revision;
        /** 冷却截止 overworld 游戏时刻。 */
        long cooldownEndGameTime;
        /** 当前活动子机 UUID；非空即持有部署租约。 */
        UUID childUuid;
        /** 部署时固定的候选 ID。 */
        String deploymentOptionId = "";
        /** 部署时固定的冷却 tick。 */
        int deploymentCooldownTick;
        /** 此部署周期是否已在终结起点记入冷却，防止重复延长。 */
        boolean cooldownStartedForChild;
        /** 当前租约锁定原因：deployed、returning 或 terminating。 */
        String lockReason = "";
    }

    private RVP_DeployableUavStateSavedData() {}

    /** 规范化到 overworld storage，避免各维度各存一份而读写不一致。 */
    public static RVP_DeployableUavStateSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                RVP_DeployableUavStateSavedData::load, RVP_DeployableUavStateSavedData::new, DATA_NAME);
    }

    /** 查询母车快照；选项状态按 UUID 存储，时间统一读取 overworld 游戏时钟。 */
    public static StateSnapshot snapshot(ServerLevel level, UUID parentUuid, String defaultOptionId) {
        return get(level).getSnapshot(parentUuid, defaultOptionId, level.getServer().overworld().getGameTime());
    }

    /** 主线程按 revision 原子写入选项，冷却/租约未结束时拒绝。 */
    public static boolean select(ServerLevel level, UUID parentUuid, String optionId, long expectedRevision) {
        return get(level).selectOption(parentUuid, optionId, expectedRevision,
                level.getServer().overworld().getGameTime());
    }

    /** 读取母车剩余冷却 tick，使用稳定世界时间。 */
    public static int remainingCooldown(ServerLevel level, UUID parentUuid) {
        return snapshot(level, parentUuid, "").remainingCooldownTick();
    }

    /** 兼容旧部署调用点；单个终结事务只记时一次，不重复延长冷却。 */
    public static void startCooldown(ServerLevel level, UUID parentUuid, int cooldownTick) {
        if (level == null || parentUuid == null || cooldownTick <= 0) return;
        get(level).startCooldown(parentUuid, level.getServer().overworld().getGameTime(), cooldownTick);
    }

    /** 成功部署后固定子机 UUID、候选 ID 和该候选冷却策略。 */
    public static boolean recordDeployment(ServerLevel level, UUID parentUuid, UUID childUuid,
                                            String optionId, int cooldownTick) {
        if (remainingCooldown(level, parentUuid) > 0) return false;
        return get(level).recordDeployment(parentUuid, childUuid, optionId, cooldownTick);
    }

    /** 读取子机启动时固定的候选 ID，母车后续选择不会污染在途实体。 */
    @Nullable public static String getDeploymentOptionId(ServerLevel level, UUID parentUuid, UUID childUuid) {
        return get(level).getDeploymentOptionId(parentUuid, childUuid);
    }

    /** 第一次开始 returning/terminating 时立即记冷却并保持部署租约。 */
    public static boolean beginTermination(ServerLevel level, UUID parentUuid, UUID childUuid, String reason) {
        return get(level).beginTermination(parentUuid, childUuid, reason,
                level.getServer().overworld().getGameTime());
    }

    /** 回传完成或残骸终结后幂等释放部署租约，不会重置冷却起点。 */
    public static boolean finishTermination(ServerLevel level, UUID parentUuid, UUID childUuid) {
        return get(level).finishTermination(parentUuid, childUuid);
    }

    /** 读取已存选择；ServerLevel 参数保证访问的是当前服务器存档。 */
    public static String getSelectedOptionId(ServerLevel level, UUID parentUuid,
                                             ResourceLocation parentVehicleId, String defaultOptionId) {
        return get(level).getSelectedOptionId(parentUuid, defaultOptionId);
    }

    /** NBT 反序列化母车 UUID 状态。 */
    public static RVP_DeployableUavStateSavedData load(CompoundTag tag) {
        RVP_DeployableUavStateSavedData data = new RVP_DeployableUavStateSavedData();
        ListTag list = tag.getList("parents", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag row = list.getCompound(i);
            if (!row.hasUUID("parent")) continue;
            Entry entry = new Entry();
            entry.selectedOptionId = row.getString("selected");
            entry.revision = Math.max(0L, row.getLong("revision"));
            entry.cooldownEndGameTime = Math.max(0L, row.getLong("cooldownEnd"));
            if (row.hasUUID("child")) entry.childUuid = row.getUUID("child");
            entry.deploymentOptionId = row.getString("deploymentOption");
            entry.deploymentCooldownTick = Math.max(0, row.getInt("deploymentCooldown"));
            entry.cooldownStartedForChild = row.getBoolean("cooldownStartedForChild");
            entry.lockReason = row.getString("lockReason");
            data.entries.put(row.getUUID("parent"), entry);
        }
        ListTag returns = tag.getList("pendingReturns", Tag.TAG_COMPOUND);
        for (int i = 0; i < returns.size(); i++) {
            CompoundTag row = returns.getCompound(i);
            if (row.hasUUID("player") && row.hasUUID("child") && row.hasUUID("parent"))
                data.pendingReturns.put(row.getUUID("player"), row.copy());
        }
        return data;
    }

    /** 持久化母车选项、revision、冷却截止时间和当前子机租约。 */
    @Override public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (var item : entries.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("parent", item.getKey());
            Entry entry = item.getValue();
            row.putString("selected", entry.selectedOptionId);
            row.putLong("revision", entry.revision);
            row.putLong("cooldownEnd", entry.cooldownEndGameTime);
            if (entry.childUuid != null) row.putUUID("child", entry.childUuid);
            row.putString("deploymentOption", entry.deploymentOptionId);
            row.putInt("deploymentCooldown", entry.deploymentCooldownTick);
            row.putBoolean("cooldownStartedForChild", entry.cooldownStartedForChild);
            row.putString("lockReason", entry.lockReason);
            list.add(row);
        }
        tag.put("parents", list);
        ListTag returns = new ListTag();
        pendingReturns.forEach((player, request) -> {
            CompoundTag row = request.copy();
            row.putUUID("player", player);
            returns.add(row);
        });
        tag.put("pendingReturns", returns);
        return tag;
    }

    /** 获取当前状态；冷却中即使锁原因为空也返回 cooldown。 */
    public StateSnapshot getSnapshot(UUID parentUuid, String defaultOptionId, long now) {
        Entry entry = entries.get(parentUuid);
        if (entry == null) return new StateSnapshot(defaultOptionId, 0L, 0, false, "");
        int remaining = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, entry.cooldownEndGameTime - now));
        String reason = !entry.lockReason.isEmpty() ? entry.lockReason : remaining > 0 ? "cooldown" : "";
        return new StateSnapshot(entry.selectedOptionId.isBlank() ? defaultOptionId : entry.selectedOptionId,
                entry.revision, remaining, entry.childUuid != null, reason);
    }

    /** 验证 revision、冷却和租约后保存选项；调用方还须验证玩家和工具。 */
    public boolean selectOption(UUID parentUuid, String optionId, long expectedRevision, long now) {
        Entry entry = entries.computeIfAbsent(parentUuid, ignored -> new Entry());
        if (optionId == null || optionId.isBlank() || entry.revision != expectedRevision || entry.childUuid != null
                || entry.cooldownEndGameTime > now || !entry.lockReason.isEmpty()) return false;
        if (!optionId.equals(entry.selectedOptionId)) {
            entry.selectedOptionId = optionId;
            entry.revision++;
            setDirty();
        }
        return true;
    }

    /** 旧 linked-child 或当前租约任一存在时，父车仍按已部署处理。 */
    public static boolean hasActiveDeployment(ServerLevel level, UUID parentUuid) {
        Entry entry = get(level).entries.get(parentUuid);
        return entry != null && entry.childUuid != null;
    }

    /** 读取已保存候选 ID；空值回退调用方传入的车型默认值。 */
    public String getSelectedOptionId(UUID parentUuid, String defaultOptionId) {
        Entry entry = entries.get(parentUuid);
        return entry == null || entry.selectedOptionId.isBlank() ? defaultOptionId : entry.selectedOptionId;
    }

    /** 成功生成子机后建立租约；单个母车最多持有一台子机租约。 */
    public boolean recordDeployment(UUID parentUuid, UUID childUuid, String optionId, int cooldownTick) {
        Entry entry = entries.computeIfAbsent(parentUuid, ignored -> new Entry());
        if (entry.childUuid != null || childUuid == null) return false;
        entry.childUuid = childUuid;
        entry.deploymentOptionId = optionId == null ? "" : optionId;
        entry.deploymentCooldownTick = Math.max(0, cooldownTick);
        entry.cooldownStartedForChild = false;
        entry.lockReason = "deployed";
        entry.revision++;
        setDirty();
        return true;
    }

    /** 读取部署时固定候选 ID；仅当前 child 租约匹配时返回。 */
    @Nullable public String getDeploymentOptionId(UUID parentUuid, UUID childUuid) {
        Entry entry = entries.get(parentUuid);
        return entry != null && childUuid.equals(entry.childUuid) ? entry.deploymentOptionId : null;
    }

    /** 第一次终结事件启动冷却，重复事件只更新锁原因且不延长冷却。 */
    public boolean beginTermination(UUID parentUuid, UUID childUuid, String reason, long now) {
        Entry entry = entries.get(parentUuid);
        if (entry == null || !childUuid.equals(entry.childUuid)) return false;
        String nextReason = reason == null || reason.isBlank() ? "terminating" : reason;
        boolean changed = false;
        if (!entry.cooldownStartedForChild) {
            entry.cooldownEndGameTime = Math.max(entry.cooldownEndGameTime, now + entry.deploymentCooldownTick);
            entry.cooldownStartedForChild = true;
            changed = true;
        }
        if (!nextReason.equals(entry.lockReason)) {
            entry.lockReason = nextReason;
            changed = true;
        }
        if (changed) {
            entry.revision++;
            setDirty();
        }
        return true;
    }

    /** 回传/终态处理完成后清租约；重复终结因 child UUID 不匹配自动拒绝。 */
    public boolean finishTermination(UUID parentUuid, UUID childUuid) {
        Entry entry = entries.get(parentUuid);
        if (entry == null || !childUuid.equals(entry.childUuid) || hasPendingReturn(childUuid)) return false;
        entry.childUuid = null;
        entry.deploymentOptionId = "";
        entry.deploymentCooldownTick = 0;
        entry.lockReason = "";
        entry.revision++;
        setDirty();
        return true;
    }

    /** 兼容未带子机 UUID 的旧调用；同一部署周期只允许首次调用开启冷却。 */
    private void startCooldown(UUID parentUuid, long now, int cooldownTick) {
        if (cooldownTick <= 0) return;
        Entry entry = entries.computeIfAbsent(parentUuid, ignored -> new Entry());
        if (entry.cooldownStartedForChild) return;
        entry.cooldownEndGameTime = Math.max(entry.cooldownEndGameTime, now + cooldownTick);
        entry.cooldownStartedForChild = true;
        entry.revision++;
        setDirty();
    }

    /** 服务器关闭时只清空运行态；SavedData 选择、租约和冷却原样保留。 */
    public static void clearRuntime() {}
}