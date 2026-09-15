package org.ywzj.rvp.entity.gunner.ai;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * [RVP] 同 faction gunner 组网交战网络侧表（"组网智能拦截"）。
 *
 * <p>记录每个维度内、各 faction 最近被本 faction 任一 gunner 交战的目标，供
 * {@code GunnerTargeting.findCiwsTarget} / {@code findBestTarget} 的可拦截导弹层做两级限制
 * （2026-09-15 与用户定版）：</p>
 * <ul>
 *   <li><b>限位窗口（硬禁，{@value #HARD_LOCK_TICKS} tick）</b>：交战后前 60 tick，除交战者
 *       本身外的同 faction gunner <b>不可选择</b>该目标——即使它是唯一候选（多枚拦截弹不往
 *       同一枚来袭导弹上倾泻）；</li>
 *   <li><b>排斥窗口（软降权，随交战距离滑动 100~200 tick，由调用方按距离计算传入）</b>：
 *       限位过期后仍降权——其它 gunner 仅在不存在未交战候选时才回退选择它；窗口过后恢复可选。</li>
 * </ul>
 * <p>记账点两处：实际发射（硬禁 + 排斥同时记账，交战者 = 射手本人）与开始跟踪新目标
 * （仅刷新排斥窗，覆盖"已选中但延迟开火"的空隙，不重置他人硬禁）。结构仿
 * {@code RVP_ChaffJamState}：静态 Map + 每服务端 tick 懒清理；服务端专用，无可测量 TPS 开销。</p>
 */
public final class RVP_GunnerEngagementNet {

    /** 限位窗口（硬禁）时长：交战后除交战者本人外其它同 faction gunner 不可选择的时长。 */
    public static final long HARD_LOCK_TICKS = 60L;

    /**
     * 交战网络索引键。
     * @param dimension 目标所在维度，避免跨维度 UUID 记录相互影响。
     * @param faction Gunner 阵营，仅同阵营共享交战状态。
     * @param targetUuid 被跟踪或交战目标 UUID。
     */
    private record Key(ResourceLocation dimension, RVP_EnumGunnerFaction faction, UUID targetUuid) {}

    /**
     * 单条交战记录。
     * @param engagedUntil 软排斥窗口截止的维度 gameTime。
     * @param hardLockedUntil 硬禁窗口截止的维度 gameTime。
     * @param engager 发射记账的 Gunner UUID；跟踪记账没有交战者时为 null。
     */
    private record Engagement(long engagedUntil, long hardLockedUntil, UUID engager) {}

    /** 交战索引表：按维度、阵营与目标 UUID 保存尚未过期的交战状态。 */
    private static final Map<Key, Engagement> ENGAGEMENTS = new HashMap<>();

    private RVP_GunnerEngagementNet() {
    }

    /**
     * 按射手到目标的距离计算组网排斥窗口：基础值为近距下限，384 格处线性增长到两倍。
     * @param vehicle Gunner 当前控制的载具。
     * @param target 当前跟踪或发射目标。
     * @param minWindowTick Profile 配置的近距下限，单位 tick；非正数表示关闭。
     * @return 线性距离窗口，单位 tick。
     */
    public static long resolveWindowTick(AbstractVehicle vehicle, Entity target, int minWindowTick) {
        if (vehicle == null || target == null || minWindowTick <= 0) {
            return 0L;
        }
        long maxWindowTick = minWindowTick * 2L;
        double distanceRatio = Mth.clamp(vehicle.distanceTo(target) / 384.0D, 0.0D, 1.0D);
        return Math.round(minWindowTick + (maxWindowTick - minWindowTick) * distanceRatio);
    }

    /**
     * 发射记账：硬禁（{@value #HARD_LOCK_TICKS} tick，对交战者 {@code shooter} 本人不生效）+
     * 排斥窗（{@code windowTick}，随交战距离滑动）同时记入。
     */
    public static void markEngaged(Level level, RVP_EnumGunnerFaction faction, Entity target,
                                   Entity shooter, long windowTick) {
        if (level == null || faction == null || target == null || !target.isAlive()
                || windowTick <= 0 || shooter == null) {
            return;
        }
        long now = level.getGameTime();
        ENGAGEMENTS.put(new Key(level.dimension().location(), faction, target.getUUID()),
                new Engagement(now + windowTick, now + HARD_LOCK_TICKS, shooter.getUUID()));
    }

    /**
     * 跟踪记账：仅刷新排斥窗（覆盖"已选中但延迟开火"的空隙），<b>不重置</b>已有的
     * 限位硬禁与交战者——周期性重扫描同一目标不会无限续期硬禁。
     */
    public static void markTracked(Level level, RVP_EnumGunnerFaction faction, Entity target, long windowTick) {
        if (level == null || faction == null || target == null || !target.isAlive() || windowTick <= 0) {
            return;
        }
        Key key = new Key(level.dimension().location(), faction, target.getUUID());
        Engagement existing = ENGAGEMENTS.get(key);
        long softUntil = level.getGameTime() + windowTick;
        if (existing == null) {
            ENGAGEMENTS.put(key, new Engagement(softUntil, 0L, null));
        } else {
            ENGAGEMENTS.put(key, new Engagement(softUntil, existing.hardLockedUntil(), existing.engager()));
        }
    }

    /**
     * 目标当前是否对 {@code gunner} 处于限位硬禁期（{@value #HARD_LOCK_TICKS} tick 内被
     * <b>其它</b>同 faction gunner 交战过）。硬禁期目标从该 gunner 的候选中完全排除——
     * 即使它是唯一候选；交战者本人不受影响。
     */
    public static boolean isHardLockedFor(Level level, RVP_EnumGunnerFaction faction,
                                          Entity target, Entity gunner) {
        if (level == null || faction == null || target == null || gunner == null) {
            return false;
        }
        Engagement engagement = ENGAGEMENTS.get(new Key(level.dimension().location(), faction, target.getUUID()));
        if (engagement == null || level.getGameTime() >= engagement.hardLockedUntil()) {
            return false;
        }
        UUID engager = engagement.engager();
        return engager != null && !engager.equals(gunner.getUUID());
    }

    /** 目标是否处于本 faction 网络的排斥窗内（任一同 faction gunner 交战过且未到期）。 */
    public static boolean isRecentlyEngaged(Level level, RVP_EnumGunnerFaction faction, Entity target) {
        if (level == null || faction == null || target == null) {
            return false;
        }
        Key key = new Key(level.dimension().location(), faction, target.getUUID());
        Engagement engagement = ENGAGEMENTS.get(key);
        if (engagement == null) {
            return false;
        }
        if (level.getGameTime() >= engagement.engagedUntil()) {
            ENGAGEMENTS.remove(key);
            return false;
        }
        return true;
    }

    /** 服务端每维度 tick 清理该维度的过期条目，避免使用其它维度的 gameTime 误删有效记录。 */
    public static void onServerTick(ResourceLocation dimension, long gameTime) {
        if (dimension == null) {
            return;
        }
        ENGAGEMENTS.entrySet().removeIf(entry -> entry.getKey().dimension().equals(dimension)
                && gameTime >= entry.getValue().engagedUntil());
    }
}
