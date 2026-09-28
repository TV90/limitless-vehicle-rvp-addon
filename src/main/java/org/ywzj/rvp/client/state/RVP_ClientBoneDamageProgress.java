package org.ywzj.rvp.client.state;

import org.ywzj.rvp.network.S2CBoneDamageProgress;

import java.util.HashMap;
import java.util.Map;

/**
 * [RVP] 骨骼模块累计伤害客户端侧表（键 = 实体 id，2026-09-28）。
 *
 * <p>由 {@code S2CBoneDamageProgress} 差分推送（累计变化才发，模块状态广播时全量补发）。
 * 辅助设备面板据此显示各模块"虚拟血量"（阈值 − 已累计）。无条目视为 0（满血）。</p>
 */
public final class RVP_ClientBoneDamageProgress {

    private static final Map<Integer, Map<String, Float>> PROGRESS = new HashMap<>();

    private RVP_ClientBoneDamageProgress() {
    }

    public static void apply(S2CBoneDamageProgress msg) {
        if (msg.damageByBone == null || msg.damageByBone.isEmpty()) {
            PROGRESS.remove(msg.entityId);
            return;
        }
        PROGRESS.put(msg.entityId, new HashMap<>(msg.damageByBone));
    }

    /** 某骨当前累计伤害（无记录返回 0 = 满血）。 */
    public static float getAccumulated(int entityId, String boneName) {
        Map<String, Float> boneMap = PROGRESS.get(entityId);
        Float value = boneMap == null ? null : boneMap.get(boneName);
        return value == null ? 0f : value;
    }

    public static void clear() {
        PROGRESS.clear();
    }
}
