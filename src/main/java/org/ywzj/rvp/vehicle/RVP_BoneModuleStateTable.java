package org.ywzj.rvp.vehicle;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

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
        boolean changed = map.keySet().removeIf(boneName -> !valid.contains(boneName));
        if (changed) {
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
