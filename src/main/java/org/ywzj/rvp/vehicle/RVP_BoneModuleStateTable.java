package org.ywzj.rvp.vehicle;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 骨骼模块（Bone Module）失效状态侧表（服务端）。
 *
 * <p>存储结构：{@code 载具UUID → (骨块名 → 已失效模块类型集合)}，一个骨块可同时挂多个
 * 模块（如反应装甲 + 光电干扰机叠加），各模块独立判定失效、独立消耗。</p>
 *
 * <p>替代被删 {@code AbstractVehicleEraStateMixin} 注入到 {@code AbstractVehicle} 实体上的
 * 字段：失效状态按载具 {@link UUID} 存于独立 Map，消费点（{@code RVP_VehicleHitboxFactorManager}）
 * 改为查表。</p>
 *
 * <p>持久化：状态写入主世界 {@link RVP_EraStateSavedData}（载具自身 NBT 无法注入，走独立
 * 存档）；载具加入世界时由 {@code RVP_EraStateEventHandler} 恢复，离开时写回并清理内存。</p>
 */
public final class RVP_BoneModuleStateTable {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<UUID, Map<String, Set<BoneModuleType>>> INACTIVE_MODULES = new HashMap<>();

    private RVP_BoneModuleStateTable() {
    }

    /** 骨块的某模块是否仍处于激活（未被消耗）。 */
    public static boolean isModuleActive(UUID vehicleId, String boneName, BoneModuleType type) {
        String normalized = normalizeBone(boneName);
        if (normalized == null || type == null) {
            return false;
        }
        Map<String, Set<BoneModuleType>> boneMap = INACTIVE_MODULES.get(vehicleId);
        if (boneMap == null) {
            return true;
        }
        Set<BoneModuleType> types = boneMap.get(normalized);
        return types == null || !types.contains(type);
    }

    /** 骨块的某模块是否已失效（被消耗）。 */
    public static boolean isModuleDestroyed(UUID vehicleId, String boneName, BoneModuleType type) {
        return !isModuleActive(vehicleId, boneName, type);
    }

    /**
     * 消耗骨块的某模块；返回 true 表示本次成功消耗（之前仍处于激活）。
     * 首次消耗会触发持久化。
     */
    public static boolean destroyModule(UUID vehicleId, String boneName, BoneModuleType type) {
        String normalized = normalizeBone(boneName);
        if (normalized == null || type == null) {
            return false;
        }
        Map<String, Set<BoneModuleType>> boneMap = INACTIVE_MODULES.computeIfAbsent(vehicleId, k -> new HashMap<>());
        boolean added = boneMap.computeIfAbsent(normalized, k -> new HashSet<>()).add(type);
        if (added) {
            persist(vehicleId);
        }
        return added;
    }

    /**
     * 恢复（维修）骨块的某模块；返回 true 表示之前确实失效、本次恢复成功。
     * 与 {@link #destroyModule} 对称：恢复后若该骨失效集合为空则整键移除；
     * 成功时自动触发持久化。快速维修（{@code RVP_MaintenanceRuntimeManager}）使用。
     */
    public static boolean restoreModule(UUID vehicleId, String boneName, BoneModuleType type) {
        String normalized = normalizeBone(boneName);
        if (normalized == null || type == null) {
            return false;
        }
        Map<String, Set<BoneModuleType>> boneMap = INACTIVE_MODULES.get(vehicleId);
        if (boneMap == null) {
            return false;
        }
        Set<BoneModuleType> types = boneMap.get(normalized);
        if (types == null || !types.remove(type)) {
            return false;
        }
        if (types.isEmpty()) {
            boneMap.remove(normalized);
            if (boneMap.isEmpty()) {
                INACTIVE_MODULES.remove(vehicleId);
            }
        }
        persist(vehicleId);
        return true;
    }

    /** 兼容旧调用：骨块是否仍处于激活（未消耗 ERA 模块）。 */
    public static boolean isEraActive(UUID vehicleId, String boneName) {
        return isModuleActive(vehicleId, boneName, BoneModuleType.ERA);
    }

    /** 兼容旧调用：消耗骨块的 ERA 模块。 */
    public static boolean consumeEra(UUID vehicleId, String boneName) {
        return destroyModule(vehicleId, boneName, BoneModuleType.ERA);
    }

    /** 整表替换某载具的失效模块集合（载具加入世界恢复时使用）。 */
    public static void setInactiveModules(UUID vehicleId, @Nullable Map<String, Set<BoneModuleType>> boneModules) {
        Map<String, Set<BoneModuleType>> map = new HashMap<>();
        if (boneModules != null) {
            for (var entry : boneModules.entrySet()) {
                String normalized = normalizeBone(entry.getKey());
                if (normalized == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                map.put(normalized, new HashSet<>(entry.getValue()));
            }
        }
        if (map.isEmpty()) {
            INACTIVE_MODULES.remove(vehicleId);
        } else {
            INACTIVE_MODULES.put(vehicleId, map);
        }
        persist(vehicleId);
    }

    public static Map<String, Set<BoneModuleType>> getInactiveModules(UUID vehicleId) {
        Map<String, Set<BoneModuleType>> map = INACTIVE_MODULES.get(vehicleId);
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<BoneModuleType>> copy = new HashMap<>();
        for (var entry : map.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return copy;
    }

    /**
     * 只保留合法骨块名（配置变更后清理失效名）；返回集合是否发生了变化，
     * 变化时调用方需要重新同步给客户端。
     */
    public static boolean retainValidBones(UUID vehicleId, @Nullable Collection<String> validBoneNames) {
        Map<String, Set<BoneModuleType>> map = INACTIVE_MODULES.get(vehicleId);
        if (map == null || map.isEmpty()) {
            return false;
        }
        Set<String> valid = new HashSet<>();
        if (validBoneNames != null) {
            for (String boneName : validBoneNames) {
                String normalized = normalizeBone(boneName);
                if (normalized != null) {
                    valid.add(normalized);
                }
            }
        }
        // [RVP] 剪枝防护日志（2026-09-28）：剪掉失效骨名必须留痕——车包 bone_modules 骨名
        // 漂移（改名/删除条目）会让存档里的失效记录被静默清除并写回，表现为"部件自动修好"
        java.util.List<String> pruned = new java.util.ArrayList<>();
        for (String boneName : map.keySet()) {
            if (!valid.contains(boneName)) {
                pruned.add(boneName);
            }
        }
        boolean changed = map.keySet().removeIf(boneName -> !valid.contains(boneName));
        if (changed) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("[RVP-BoneModuleState] 载具 {} 剪除失效骨（不在当前配置合法骨名集合内）: {}",
                        vehicleId, pruned);
            }
            if (map.isEmpty()) {
                INACTIVE_MODULES.remove(vehicleId);
            }
            persist(vehicleId);
        }
        return changed;
    }

    /** 载具离开世界：从内存侧表移除（持久化由事件处理器负责）。 */
    public static void onVehicleLeave(UUID vehicleId) {
        INACTIVE_MODULES.remove(vehicleId);
    }

    /**
     * [RVP] 停服兜底落盘（2026-09-28）：把内存侧表<b>全部</b>条目写入主世界 SavedData
     * （幂等——leave 已写过的条目原样覆盖）。覆盖 {@code EntityLeaveLevelEvent} 未按预期
     * 触发的路径（强杀进程前的关卡关闭、实体批量卸载时序等），消除"退出重进失效状态
     * 丢失"的最后缝隙。返回写入条目数。
     */
    public static int flushAll(ServerLevel overworld) {
        if (overworld == null || INACTIVE_MODULES.isEmpty()) {
            return 0;
        }
        RVP_EraStateSavedData savedData = RVP_EraStateSavedData.get(overworld);
        int count = 0;
        for (Map.Entry<UUID, Map<String, Set<BoneModuleType>>> entry : INACTIVE_MODULES.entrySet()) {
            savedData.writeEntry(entry.getKey(), entry.getValue());
            count++;
        }
        return count;
    }

    /** [RVP] 诊断（/rvpdebug modulestate dump）：内存侧表全部条目的可读文本。 */
    public static String dump() {
        if (INACTIVE_MODULES.isEmpty()) {
            return "内存侧表为空";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Map<String, Set<BoneModuleType>>> entry : INACTIVE_MODULES.entrySet()) {
            sb.append("内存 ").append(shortId(entry.getKey())).append(" = ").append(format(entry.getValue())).append('\n');
        }
        return sb.toString();
    }

    private static String format(Map<String, Set<BoneModuleType>> boneMap) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<BoneModuleType>> entry : boneMap.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }

    private static String shortId(UUID uuid) {
        String s = uuid.toString();
        return s.substring(0, Math.min(8, s.length()));
    }

    private static void persist(UUID vehicleId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        RVP_EraStateSavedData.get(overworld).writeEntry(vehicleId, INACTIVE_MODULES.get(vehicleId));
    }

    private static @Nullable String normalizeBone(@Nullable String boneName) {
        if (boneName == null) {
            return null;
        }
        String normalized = boneName.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
