package org.ywzj.rvp.vehicle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [RVP] 引擎部件窗口累计伤害侧表（服务端，2026-09-26 新增；2026-09-27 深夜定版：无衰减）。
 *
 * <p>存储结构：{@code 载具UUID → (引擎骨名 → 累计直击伤害)}。与
 * {@link RVP_BoneModuleStateTable}（纯失效布尔集，无数值槽）并行：本表只存
 * "累计直击伤害"这一中间量，<b>不持久化</b>（停服清零只损失受损档进度；
 * 瘫痪档本身由 ENGINE 模块失效进状态表/存档，不受影响）。</p>
 *
 * <p>语义（用户 2026-09-27 最终定版，无衰减）：累计直击伤害 ≥ 受损阈值 → 功率减半，
 * <b>不随时间恢复</b>——重创后保持受损直到快修；继续累计跨过重损阈值 → ENGINE 模块
 * 失效（瘫痪档，功率清零，走维修恢复）。累计入口在 {@code tryDestroyBoneModules}，
 * 消费端 {@code RVP_EnginePowerHandler} 每载具每 tick（VehicleMoveEvent）计算档位。</p>
 */
public final class RVP_EngineDamageTable {

    /** 单块引擎骨的累计状态（无衰减定版：仅累计值）。 */
    private static final class Entry {
        float accumulated;

        Entry(float accumulated) {
            this.accumulated = accumulated;
        }
    }

    private static final Map<UUID, Map<String, Entry>> ACCUMULATED = new HashMap<>();
    /**
     * 已通知"重创发动机"的引擎骨（载具UUID → 骨名集合）：受损期间只向射手报一次
     * （用户 2026-09-27 定版）；快修修复后重新武装。
     */
    private static final Map<UUID, Set<String>> DAMAGED_NOTIFIED = new HashMap<>();

    private RVP_EngineDamageTable() {
    }

    /**
     * 累加一次直击伤害并返回累加后的累计值（供调用方判断是否跨过重损阈值）。
     * 无衰减定版：累计只增不减，仅快修/维修清零。
     */
    public static float accumulate(UUID vehicleId, String boneName, float damage) {
        if (vehicleId == null || boneName == null || boneName.isBlank() || damage <= 0f) {
            return 0f;
        }
        Map<String, Entry> boneMap = ACCUMULATED.computeIfAbsent(vehicleId, k -> new HashMap<>());
        Entry entry = boneMap.get(boneName);
        if (entry == null) {
            entry = new Entry(damage);
            boneMap.put(boneName, entry);
        } else {
            entry.accumulated += damage;
        }
        return entry.accumulated;
    }

    /** 当前累计值（无累计返回 0）。 */
    public static float getAccumulated(UUID vehicleId, String boneName) {
        Map<String, Entry> boneMap = ACCUMULATED.get(vehicleId);
        if (boneMap == null) {
            return 0f;
        }
        Entry entry = boneMap.get(boneName);
        return entry == null ? 0f : entry.accumulated;
    }

    /**
     * 登记本受损期"已通知重创发动机"；返回 true = 本次是新登记（调用方应发通知），
     * false = 受损期间已报过（跳过）。快修清零后解除武装。
     */
    public static boolean tryMarkDamagedNotified(UUID vehicleId, String boneName) {
        return DAMAGED_NOTIFIED
                .computeIfAbsent(vehicleId, k -> new HashSet<>())
                .add(boneName);
    }

    /**
     * 清空单块引擎骨累计（快修修复瘫痪档/维修触发清受损档时调用）。
     * 返回 true 表示确实有累计被清掉。
     */
    public static boolean clear(UUID vehicleId, String boneName) {
        Map<String, Entry> boneMap = ACCUMULATED.get(vehicleId);
        if (boneMap == null) {
            return false;
        }
        boolean removed = boneMap.remove(boneName) != null;
        if (boneMap.isEmpty()) {
            ACCUMULATED.remove(vehicleId);
        }
        clearNotified(vehicleId, boneName);
        return removed;
    }

    /** 清空整台载具的引擎骨累计（快修触发时调用：受损档随维修清零）。 */
    public static void clearVehicle(UUID vehicleId) {
        ACCUMULATED.remove(vehicleId);
        DAMAGED_NOTIFIED.remove(vehicleId);
    }

    /** 载具离开世界：清理内存侧表（中间量不持久化）。 */
    public static void onVehicleLeave(UUID vehicleId) {
        ACCUMULATED.remove(vehicleId);
        DAMAGED_NOTIFIED.remove(vehicleId);
    }

    /** 窗期重置：累计清零（快修）时解除"已通知"武装。 */
    private static void clearNotified(UUID vehicleId, String boneName) {
        Set<String> set = DAMAGED_NOTIFIED.get(vehicleId);
        if (set != null) {
            set.remove(boneName);
            if (set.isEmpty()) {
                DAMAGED_NOTIFIED.remove(vehicleId);
            }
        }
    }
}
