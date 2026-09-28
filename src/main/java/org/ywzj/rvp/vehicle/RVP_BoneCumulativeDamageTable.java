package org.ywzj.rvp.vehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [RVP] 骨骼模块累计伤害侧表（服务端，2026-09-28 通用化）。
 *
 * <p>{@code UUID → (骨名 → 累计直击伤害)}，与模块类型无关的统一累计槽——引擎（ENGINE/
 * ENGINE_DAMAGED）、炮管（BARREL）与全部设备类模块（ERA/RADAR/APS/ECM/JAMMER/DIRCM/
 * COUNTERMEASURE，2026-09-28 起由单发直毁改为累计失效）共用。</p>
 *
 * <p>累计只增不减、中间量不持久化（停服清零；失效状态本身随失效表持久化）。清理点：
 * 快修生效结束（clearVehicle/clear）、焊枪部件修复（clear）、载具离开世界（onVehicleLeave）。</p>
 *
 * <p>额外承载引擎重创通知武装（{@code DAMAGED_NOTIFIED}）：跨受损阈时报一次"重创发动机"，
 * 累计清零后重新武装——原 RVP_EngineDamageTable 的通知语义迁入。</p>
 */
public final class RVP_BoneCumulativeDamageTable {

    /** 累计值：UUID → (骨名 → 累计直击伤害)。 */
    private static final Map<UUID, Map<String, Float>> ACCUMULATED = new HashMap<>();

    /** 引擎重创通知武装：UUID → 骨名（武装中 = 可报"重创发动机"；累计清零解除）。 */
    private static final Set<String> DAMAGED_NOTIFIED = ConcurrentHashMap.newKeySet();

    private RVP_BoneCumulativeDamageTable() {
    }

    /** 累入一次直击伤害（实际到骨口径），返回累计后的总值。 */
    public static float accumulate(UUID vehicleId, String boneName, float damage) {
        return ACCUMULATED
                .computeIfAbsent(vehicleId, k -> new HashMap<>())
                .merge(boneName, damage, Float::sum);
    }

    /** 该载具全部骨累计快照（副本，供差分推送对比；无记录返回空表）。 */
    public static Map<String, Float> snapshotOf(UUID vehicleId) {
        Map<String, Float> boneMap = ACCUMULATED.get(vehicleId);
        return boneMap == null ? Map.of() : new HashMap<>(boneMap);
    }

    /** 当前累计值（无记录返回 0）。 */
    public static float getAccumulated(UUID vehicleId, String boneName) {
        Map<String, Float> boneMap = ACCUMULATED.get(vehicleId);
        Float value = boneMap == null ? null : boneMap.get(boneName);
        return value == null ? 0f : value;
    }

    /** 清空某骨累计（快修/焊枪恢复该骨模块时调用）；同时解除该骨重创通知武装。 */
    public static void clear(UUID vehicleId, String boneName) {
        Map<String, Float> boneMap = ACCUMULATED.get(vehicleId);
        if (boneMap != null) {
            boneMap.remove(boneName);
            if (boneMap.isEmpty()) {
                ACCUMULATED.remove(vehicleId);
            }
        }
        clearNotified(vehicleId, boneName);
    }

    /** 清空某载具全部累计（快修生效结束联动；失效状态在失效表中持久化，不受影响）。 */
    public static void clearVehicle(UUID vehicleId) {
        ACCUMULATED.remove(vehicleId);
        DAMAGED_NOTIFIED.removeIf(key -> key.startsWith(vehicleId.toString()));
    }

    /** 载具离开世界：清该载具全部累计与通知武装。 */
    public static void onVehicleLeave(UUID vehicleId) {
        clearVehicle(vehicleId);
    }

    /** 引擎重创通知武装（首次跨受损阈返回 true = 报"重创发动机"；窗期内重复返回 false）。 */
    public static boolean tryMarkDamagedNotified(UUID vehicleId, String boneName) {
        return DAMAGED_NOTIFIED.add(vehicleId + "|" + boneName);
    }

    /** 解除某骨重创通知武装（累计清零/快修恢复时；下次跨阈重新报）。 */
    public static void clearNotified(UUID vehicleId, String boneName) {
        DAMAGED_NOTIFIED.remove(vehicleId + "|" + boneName);
    }
}
