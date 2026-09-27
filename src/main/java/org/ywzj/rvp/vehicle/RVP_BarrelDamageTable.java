package org.ywzj.rvp.vehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * [RVP] 炮管部件累计伤害侧表（服务端，2026-09-28 新增）。
 *
 * <p>语义（用户 2026-09-28 定版：单档、无衰减永久，与引擎无衰减定版同构）：
 * {@code UUID → (炮管骨名 → 累计直击伤害)}，命中骨配置了 BARREL 模块时由
 * {@code RVP_VehicleHitboxFactorManager.accumulateBarrelDamage} 累入实际到骨伤害；
 * 累计跨过 {@code BoneBarrelConfig.threshold} 即 BARREL 模块失效（进失效表，
 * 整个炮管所在武器站禁止射击）。</p>
 *
 * <p>累计只增不减，中间量不持久化（停服清零，同引擎累计口径）——失效状态本身随
 * {@code RVP_BoneModuleStateTable} 持久化，重启后炮管保持损坏；唯一恢复途径 = 快修
 * （{@code restoreBoneModules} 恢复 BARREL 时 {@link #clear} 清该骨累计，
 * 防恢复后残存累计立即再次跨阈值）。</p>
 */
public final class RVP_BarrelDamageTable {

    private static final Map<UUID, Map<String, Float>> ACCUMULATED = new HashMap<>();

    private RVP_BarrelDamageTable() {
    }

    /** 累入一次直击伤害（实际到骨口径），返回累计后的总值。 */
    public static float accumulate(UUID vehicleId, String boneName, float damage) {
        float value = ACCUMULATED
                .computeIfAbsent(vehicleId, k -> new HashMap<>())
                .merge(boneName, damage, Float::sum);
        return value;
    }

    /** 当前累计值（无记录返回 0）。 */
    public static float getAccumulated(UUID vehicleId, String boneName) {
        Map<String, Float> boneMap = ACCUMULATED.get(vehicleId);
        Float value = boneMap == null ? null : boneMap.get(boneName);
        return value == null ? 0f : value;
    }

    /** 清空某骨累计（快修恢复 BARREL 模块时调用）。 */
    public static void clear(UUID vehicleId, String boneName) {
        Map<String, Float> boneMap = ACCUMULATED.get(vehicleId);
        if (boneMap != null) {
            boneMap.remove(boneName);
            if (boneMap.isEmpty()) {
                ACCUMULATED.remove(vehicleId);
            }
        }
    }

    /** 载具离开世界：清该载具全部累计（失效状态在失效表中持久化，不受影响）。 */
    public static void onVehicleLeave(UUID vehicleId) {
        ACCUMULATED.remove(vehicleId);
    }
}
