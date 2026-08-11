package org.ywzj.rvp.client.state;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.ywzj.rvp.network.S2CBoneModuleState;
import org.ywzj.rvp.vehicle.BoneModuleType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户端骨骼模块状态侧表：骨块失效模块集合按载具 entityId 存储。
 *
 * <p>数据经 {@link S2CBoneModuleState} 推送后在此按 entityId 存表，供动画脚本经
 * {@code context.rvp_isModuleActive(bone, type)} 查询并隐藏失效骨块。</p>
 */
public final class RVP_ClientBoneModuleState {

    private static final Map<Integer, Map<String, Set<BoneModuleType>>> INACTIVE_MODULES = new HashMap<>();
    /**
     * ERA 骨块被摧毁的时间戳（entityId → boneName → 摧毁时刻毫秒）。
     * 命中提示 UI 据此在"命中圈动画播完后"播放爆反燃烧动画；同一骨块只记首次摧毁时间，
     * 因此已播放过的爆反动画不会在后续命中时重复播放。
     */
    private static final Map<Integer, Map<String, Long>> ERA_DESTROY_TIMES = new HashMap<>();
    /**
     * 展板渲染期间生效的"状态回放延迟"（毫秒）：0 表示不延迟。
     * 展板命中提示渲染载具模型时临时设置，让 JS 动画脚本的爆反状态查询回到 0.5 秒前，
     * 使刚被摧毁的爆反骨块在展板里晚 0.5 秒消失；世界渲染不受影响（立即消失）。
     */
    private static long renderDelayMs = 0L;
    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_ClientBoneModuleState() {
    }

    public static void apply(S2CBoneModuleState msg) {
        LOGGER.info("[RVP-ERA-STATE] apply entityId={} inactive={}",
                msg.entityId, msg.inactiveModules.keySet());
        if (msg.inactiveModules.isEmpty()) {
            INACTIVE_MODULES.remove(msg.entityId);
            ERA_DESTROY_TIMES.remove(msg.entityId);
        } else {
            Map<String, Long> times = ERA_DESTROY_TIMES.computeIfAbsent(msg.entityId, k -> new HashMap<>());
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Set<BoneModuleType>> entry : msg.inactiveModules.entrySet()) {
                if (entry.getValue() != null && entry.getValue().contains(BoneModuleType.ERA)) {
                    times.computeIfAbsent(entry.getKey(), k -> now);
                }
            }
            INACTIVE_MODULES.put(msg.entityId, msg.inactiveModules);
        }
    }

    /** 骨块的某模块是否仍处于激活（未被消耗）。 */
    public static boolean isModuleActive(int entityId, String boneName, BoneModuleType type) {
        Map<String, Set<BoneModuleType>> boneMap = INACTIVE_MODULES.get(entityId);
        if (boneMap == null) {
            return true;
        }
        Set<BoneModuleType> types = boneMap.get(boneName);
        if (types == null || !types.contains(type)) {
            return true;
        }
        // 展板"状态回放延迟"：ERA 骨块刚被摧毁（摧毁时间落在延迟窗口内）时，
        // 展板渲染仍按"延迟前"的激活状态处理，让骨块在展板里晚 0.5 秒消失。
        if (renderDelayMs > 0L && type == BoneModuleType.ERA) {
            long destroyTime = getEraDestroyTime(entityId, boneName);
            if (destroyTime > 0L && destroyTime > System.currentTimeMillis() - renderDelayMs) {
                return true;
            }
        }
        return false;
    }

    /** 设置展板渲染期间的"状态回放延迟"（毫秒），渲染结束必须调用 {@link #clearRenderDelay()}。 */
    public static void setRenderDelay(long ms) {
        renderDelayMs = ms;
    }

    /** 清除展板渲染期间的状态回放延迟，恢复正常实时状态查询。 */
    public static void clearRenderDelay() {
        renderDelayMs = 0L;
    }

    /** 兼容旧调用：骨块的 ERA 模块是否仍激活。 */
    public static boolean isEraActive(int entityId, String boneName) {
        return isModuleActive(entityId, boneName, BoneModuleType.ERA);
    }

    /**
     * 该载具所有"ERA 模块已失效"的骨块名列表（被摧毁的爆反）。
     * 供命中提示 UI 在爆反骨块位置渲染"红色 → 深黑红 → 透明"的燃烧动画。
     */
    public static List<String> getInactiveEraBones(int entityId) {
        Map<String, Set<BoneModuleType>> boneMap = INACTIVE_MODULES.get(entityId);
        if (boneMap == null || boneMap.isEmpty()) {
            return List.of();
        }
        List<String> out = null;
        for (Map.Entry<String, Set<BoneModuleType>> entry : boneMap.entrySet()) {
            if (entry.getValue() != null && entry.getValue().contains(BoneModuleType.ERA)) {
                if (out == null) {
                    out = new ArrayList<>();
                }
                out.add(entry.getKey());
            }
        }
        return out == null ? List.of() : out;
    }

    /** 骨块 ERA 被摧毁的时间戳（毫秒）；该骨块从未被摧毁/无记录时返回 -1。 */
    public static long getEraDestroyTime(int entityId, String boneName) {
        Map<String, Long> times = ERA_DESTROY_TIMES.get(entityId);
        return times == null ? -1L : times.getOrDefault(boneName, -1L);
    }

    public static void clear() {
        INACTIVE_MODULES.clear();
        ERA_DESTROY_TIMES.clear();
    }
}
