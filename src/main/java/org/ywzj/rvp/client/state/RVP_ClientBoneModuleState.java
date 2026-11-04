package org.ywzj.rvp.client.state;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.event.RVP_TurretTraverseHandler;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.slf4j.Logger;
import org.ywzj.rvp.network.S2CBoneModuleState;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.debug.RVP_DebugFlags;

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
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID)
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
    /** 历史展板绘制作用域；不写入真实实体侧表。 */
    private static int replayEntityId = Integer.MIN_VALUE;
    private static Map<String, Set<BoneModuleType>> replayInactive = Map.of();
    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_ClientBoneModuleState() {
    }

    public static void apply(S2CBoneModuleState msg) {
        // 骨骼模块状态同步探针（开关：/rvpdebug flags client_state）
        if (RVP_DebugFlags.CLIENT_STATE.isEnabled()) {
            LOGGER.info("[RVP-ERA-STATE] apply entityId={} inactive={}",
                    msg.entityId, msg.inactiveModules.keySet());
        }
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
        // 收到维修后的空快照也要立即解除该炮塔的转速限制。
        applyTurretTraverse(msg.entityId, msg.inactiveModules);
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // 只遍历已同步过失效状态的载具，兼顾武器站重建或配置晚到。
            INACTIVE_MODULES.forEach(RVP_ClientBoneModuleState::applyTurretTraverse);
        } else {
            // 先于 NORMAL 的弹道准星、延迟炮口烟和其它姿态消费者，不能等它们缓存完旧坐标再补正。
            RVP_TurretTraverseHandler.constrainLevel(Minecraft.getInstance().level);
        }
    }

    private static void applyTurretTraverse(int entityId, Map<String, Set<BoneModuleType>> inactive) {
        var level = Minecraft.getInstance().level;
        if (level != null && level.getEntity(entityId) instanceof AbstractVehicle vehicle) {
            RVP_TurretTraverseHandler.apply(vehicle, inactive);
        }
    }

    @SubscribeEvent
    public static void onVehicleLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof AbstractVehicle vehicle) {
            INACTIVE_MODULES.remove(vehicle.getId());
            ERA_DESTROY_TIMES.remove(vehicle.getId());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) clear();
    }

    /** 骨块的某模块是否仍处于激活（未被消耗）。 */
    public static boolean isModuleActive(int entityId, String boneName, BoneModuleType type) {
        if (entityId == replayEntityId) return !replayInactive.getOrDefault(boneName, Set.of()).contains(type);
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
    public static void setReplayState(int entityId, Map<String, Set<BoneModuleType>> inactive) {
        replayEntityId = entityId;
        replayInactive = inactive;
    }

    public static void clearRenderDelay() {
        replayEntityId = Integer.MIN_VALUE;
        replayInactive = Map.of();
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
