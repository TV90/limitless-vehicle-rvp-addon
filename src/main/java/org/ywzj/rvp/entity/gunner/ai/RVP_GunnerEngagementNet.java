package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * [RVP] 同 faction gunner 组网交战网络侧表（"组网智能拦截"）。
 *
 * <p>记录每个维度内、各 faction 最近被本 faction 任一 gunner 射击过的目标，供
 * {@code GunnerTargeting.findBestTarget} 的可拦截导弹层做<b>降权</b>：某目标在窗口内已被
 * 同 faction 任何 gunner 交战过，则该 gunner 优先选择其它未交战目标——拦截作业时多台
 * 防空车不再全体重复锁同一枚最近的导弹，弹幕自动分配到不同来袭目标上。</p>
 *
 * <p>语义（2026-09-15 与用户定版）：<b>降权而非禁选</b>——当所有候选都已被交战时，
 * 忽略降权照常选择（仍有弹的 gunner 继续打击）；窗口过后目标恢复可选。窗口时长由
 * gunner 档案 {@code engagement_net_cooldown_tick} 配置（本表只存"最后交战 tick"，
 * 由查询方按自身档案窗口判断，不同档案窗口可不同）。</p>
 *
 * <p>结构仿 {@code RVP_ChaffJamState}：静态 Map + 每服务端 tick 懒清理；服务端专用
 * （gunner AI 与射击记账均在服务端），无客户端读取路径。</p>
 */
public final class RVP_GunnerEngagementNet {

    /** 条目滞留上限（tick）：超过该时长的记录无论窗口配置如何都清除，防内存膨胀。 */
    private static final long STALE_TICKS = 1200L;

    /** 组合键：同一维度的同 faction 才互相避让；目标用 UUID（实体 id 跨重启不稳）。 */
    private record Key(ResourceLocation dimension, RVP_EnumGunnerFaction faction, UUID targetUuid) {}

    private static final Map<Key, Long> LAST_ENGAGED = new HashMap<>();

    private RVP_GunnerEngagementNet() {
    }

    /** 记录一次交战：faction 网络内该目标进入其它 gunner 的降权窗口（从当前 gameTime 起算）。 */
    public static void markEngaged(Level level, RVP_EnumGunnerFaction faction, Entity target) {
        if (level == null || faction == null || target == null || !target.isAlive()) {
            return;
        }
        LAST_ENGAGED.put(new Key(level.dimension().location(), faction, target.getUUID()),
                level.getGameTime());
    }

    /**
     * 目标是否处于本 faction 网络的降权窗口内（{@code windowTick} 内被任何同 faction
     * gunner 射击过）。{@code windowTick <= 0} 视为组网关闭。
     */
    public static boolean isRecentlyEngaged(Level level, RVP_EnumGunnerFaction faction,
                                            Entity target, long windowTick) {
        if (level == null || faction == null || target == null || windowTick <= 0) {
            return false;
        }
        Long last = LAST_ENGAGED.get(new Key(level.dimension().location(), faction, target.getUUID()));
        return last != null && level.getGameTime() - last <= windowTick;
    }

    /** 服务端每 tick 清理滞留条目。 */
    public static void onServerTick(long gameTime) {
        LAST_ENGAGED.entrySet().removeIf(entry -> gameTime - entry.getValue() > STALE_TICKS);
    }
}
