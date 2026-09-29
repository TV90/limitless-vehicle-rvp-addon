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

    /** 同 tick 同骨防双计入账记录（vehicleId|bone → 上次入账的 gameTime；§41.6）。 */
    private static final Map<String, Long> LAST_CREDIT_TICK = new HashMap<>();

    private RVP_BoneCumulativeDamageTable() {
    }

    /** 累入一次直击伤害（实际到骨口径），返回累计后的总值。 */
    public static float accumulate(UUID vehicleId, String boneName, float damage) {
        return ACCUMULATED
                .computeIfAbsent(vehicleId, k -> new HashMap<>())
                .merge(boneName, damage, Float::sum);
    }

    /**
     * 累入一次伤害（带同 tick 同骨防双计，2026-09-29 §41.6 用户实测 SBW 火箭筒一发拆
     * 800 引擎）：外源 mod 武器常见"直击段+爆炸段"两段独立 hurt（均非本体
     * {@code ywzj_vehicle.explosion} msgId），两段都走直击骨解析并各自给同一骨累计入账
     * （直击段 ~300 + 爆炸段 ~576 ≈ 876 ≥ 800 → 一发拆 800 引擎，而车体实收只有两段
     * 合计 576）。守卫：同一游戏 tick 内同一载具同一骨只接受**首次**入账，后续入账忽略
     * （返回当前累计不变）。副作用：高射速武器同 tick 多发命中同骨只计第一发——量级
     * 损失可接受（该表只服务炮管/引擎/设备三类累计部件）。
     *
     * @param gameTime 当前游戏 tick（vehicle.level().getGameTime()，同 tick 同值）
     */
    public static float accumulate(UUID vehicleId, String boneName, float damage, long gameTime) {
        String dedupeKey = vehicleId + "|" + boneName;
        Long lastTick = LAST_CREDIT_TICK.get(dedupeKey);
        if (lastTick != null && lastTick == gameTime) {
            return getAccumulated(vehicleId, boneName);
        }
        LAST_CREDIT_TICK.put(dedupeKey, gameTime);
        return accumulate(vehicleId, boneName, damage);
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
