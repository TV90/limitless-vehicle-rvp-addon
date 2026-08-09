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
 * ERA（爆炸反应装甲）失效骨块状态侧表（服务端）。
 *
 * <p>替代被删 {@code AbstractVehicleEraStateMixin} 注入到 {@code AbstractVehicle} 实体上的
 * {@code rvp$inactiveEraBones} 字段：失效骨块集合按载具 {@link UUID} 存于独立 Map，
 * 消费点（{@code RVP_VehicleHitboxFactorManager}）改为查表。</p>
 *
 * <p>持久化：状态写入主世界 {@link RVP_EraStateSavedData}（载具自身 NBT 无法注入，走独立
 * 存档）；载具加入世界时由 {@code RVP_EraStateEventHandler} 恢复，离开时写回并清理内存。</p>
 */
public final class RVP_EraStateTable {

    private static final Map<UUID, Set<String>> INACTIVE_BONES = new HashMap<>();

    private RVP_EraStateTable() {
    }

    /** 骨块是否仍处于激活（未被消耗）。 */
    public static boolean isEraActive(UUID vehicleId, String boneName) {
        String normalized = normalizeBone(boneName);
        if (normalized == null) {
            return false;
        }
        Set<String> set = INACTIVE_BONES.get(vehicleId);
        return set == null || !set.contains(normalized);
    }

    /** 消耗一块 ERA；返回 true 表示本次成功消耗（之前仍处于激活）。 */
    public static boolean consumeEra(UUID vehicleId, String boneName) {
        String normalized = normalizeBone(boneName);
        if (normalized == null) {
            return false;
        }
        boolean added = INACTIVE_BONES.computeIfAbsent(vehicleId, k -> new HashSet<>()).add(normalized);
        if (added) {
            persist(vehicleId);
        }
        return added;
    }

    /** 整表替换某载具的失效骨块集合（载具加入世界恢复时使用）。 */
    public static void setInactiveEraBones(UUID vehicleId, @Nullable Collection<String> boneNames) {
        Set<String> set = new HashSet<>();
        if (boneNames != null) {
            for (String boneName : boneNames) {
                String normalized = normalizeBone(boneName);
                if (normalized != null) {
                    set.add(normalized);
                }
            }
        }
        if (set.isEmpty()) {
            INACTIVE_BONES.remove(vehicleId);
        } else {
            INACTIVE_BONES.put(vehicleId, set);
        }
        persist(vehicleId);
    }

    public static Set<String> getInactiveEraBones(UUID vehicleId) {
        Set<String> set = INACTIVE_BONES.get(vehicleId);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    /**
     * 只保留合法骨块名（配置变更后清理失效名）；返回集合是否发生了变化，
     * 变化时调用方需要重新同步给客户端。
     */
    public static boolean retainEraBones(UUID vehicleId, @Nullable Collection<String> validBoneNames) {
        Set<String> set = INACTIVE_BONES.get(vehicleId);
        if (set == null || set.isEmpty()) {
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
        boolean changed = set.removeIf(boneName -> !valid.contains(boneName));
        if (changed) {
            if (set.isEmpty()) {
                INACTIVE_BONES.remove(vehicleId);
            }
            persist(vehicleId);
        }
        return changed;
    }

    /** 载具离开世界：从内存侧表移除（持久化由事件处理器负责）。 */
    public static void onVehicleLeave(UUID vehicleId) {
        INACTIVE_BONES.remove(vehicleId);
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
        RVP_EraStateSavedData.get(overworld).writeEntry(vehicleId, INACTIVE_BONES.get(vehicleId));
    }

    private static @Nullable String normalizeBone(@Nullable String boneName) {
        if (boneName == null) {
            return null;
        }
        String normalized = boneName.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
