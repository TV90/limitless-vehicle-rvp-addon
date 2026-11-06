package org.ywzj.rvp.client.visual.shake;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.ArrayList;
import java.util.List;

import static org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning.Group.sphere;
import static org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning.Parameter.lifetime;

/** 普通与自动冲击波的独立震动传播；保留原速度，只延长传播距离与存活时间。 */
public final class RVP_ShockwaveShakePropagation {
    /** 最大震动半径为可见波面最大半径的两倍，不作用于每帧传播速度。 */
    private static final double RANGE_MULTIPLIER = 2.0D;
    /** 原视觉事件的标准时间长度，单位游戏刻。 */
    private static final double STANDARD_DURATION = 220.0D;
    /** 只保存轻量传播状态，独立于最多八个可见空气层；最多保留三十二个事件。 */
    private static final int MAX_ACTIVE = 32;
    /** 当前世界中尚未传播完的事件，视觉实例结束或被淘汰不删除此集合。 */
    private static final List<RVP_ShockwaveShakePropagation> ACTIVE = new ArrayList<>();
    /** 真实爆心坐标，用于每帧重新计算玩家的三维直线距离。 */
    private final Vec3 center;
    /** 服务端爆炸开始时间，单位游戏刻；迟到事件不会从零重新传播。 */
    private final long startGameTime;
    /** 事件基准时长，单位游戏刻，与原视觉时钟使用相同换算。 */
    private final int baseDuration;
    /** 创建时的固定调参快照，后续调参不能改变正在传播的事件。 */
    private final RVP_ShockwaveRuntimeTuning.Snapshot tuning;
    /** 单事件的一次性抵达判定与通用震动提交器。 */
    private final RVP_ShockwaveScreenShake screenShake;
    /** 可见波面精确寿命终点，单位标准游戏刻，不取向上取整后的回收时间。 */
    private final float visualEndAge;
    /** 可见波面寿命结束时的实际半径，单位格，已包含原有尺寸上限。 */
    private final double maximumVisualRadius;
    /** 独立扩大后的震动衰减边界，单位格。 */
    private final double maximumRadius;
    /** 可见波面结束后继续传播的原基础速度，单位格/标准游戏刻。 */
    private final double continuationSpeed;
    /** 包含外圈传播的精确总时长，单位实际游戏刻。 */
    private final double durationTicks;

    /** 同包测试可传入独立震动调度器；生产事件统一由 spawn 登记。 */
    RVP_ShockwaveShakePropagation(Vec3 center, long startGameTime, int baseDuration,
            RVP_ShockwaveRuntimeTuning.Snapshot tuning, RVP_ShockwaveScreenShake screenShake) {
        this.center = center;
        this.startGameTime = startGameTime;
        this.baseDuration = baseDuration;
        this.tuning = tuning;
        this.screenShake = screenShake;
        // 调用原快照取得精确寿命终点和可达半径，旧范围内的到达曲线保持原样。
        visualEndAge = tuning.get(sphere, lifetime) / tuning.age(sphere, 1);
        maximumVisualRadius = tuning.sphereRadius(visualEndAge);
        maximumRadius = maximumVisualRadius * RANGE_MULTIPLIER;
        // 调用原基础速度，新增外圈只增加传播时间，不加倍速度，也不放大起始半径。
        continuationSpeed = tuning.sphereBaseExpansionSpeed();
        durationTicks = (visualEndAge + (maximumRadius - maximumVisualRadius) / continuationSpeed)
                * baseDuration / STANDARD_DURATION;
    }

    /** 为已验证的普通/自动视觉事件登记传播，允许网络事件晚于可见寿命但仍处于外圈传播期。 */
    public static void spawn(RVP_VisualEffectEvent event, int baseDuration,
            RVP_ShockwaveRuntimeTuning.Snapshot tuning, double now) {
        // 创建独立抵达判定并登记轻量状态；不持有视觉实例或绘图资源。
        add(new RVP_ShockwaveShakePropagation(event.position(), event.startGameTime(), baseDuration,
                tuning, new RVP_ShockwaveScreenShake(event.seed())), now);
    }

    /** 统一限制轻量事件数量；同包测试复用真实登记路径验证外圈存活与清理。 */
    static void add(RVP_ShockwaveShakePropagation propagation, double now) {
        // 拒绝无法传播或已完整过期的事件，不能挤掉仍有效的震动。
        if (!Double.isFinite(propagation.durationTicks) || propagation.durationTicks <= 0
                || propagation.isFinished(now)) { return; }
        if (ACTIVE.size() >= MAX_ACTIVE) { ACTIVE.remove(0); }
        ACTIVE.add(propagation);
    }

    /** 每个客户端逻辑刻清理过期事件，使用世界时间，暂停时不推进。 */
    public static void tick(double now) {
        // 按震动自身的传播期限清理，不依赖可见波面的寿命。
        ACTIVE.removeIf(propagation -> propagation.isFinished(now));
    }

    /** 在原相机事件中按玩家当前位置检查全部传播，不查询地形或视线遮挡。 */
    public static void updateAll(double now, Vec3 playerPosition) {
        for (RVP_ShockwaveShakePropagation propagation : ACTIVE) {
            // 调用单事件抵达判定，玩家移动仍可提前或推迟收到震动。
            propagation.update(now, playerPosition.distanceTo(propagation.center));
        }
    }

    /** 退出世界或换维度时清空尚未抵达的传播；已提交脉冲由通用执行器一起清理。 */
    public static void clear() { ACTIVE.clear(); }

    /** 按独立传播时钟提交一次震动，默认每次仍播放十二刻。 */
    boolean update(double now, double distance) {
        // 调用独立到期判定和半径计算；原最大范围外的玩家也必须等到传播实际抵达。
        if (!Double.isFinite(now) || now < startGameTime || isFinished(now)) { return false; }
        return screenShake.update(now, distance, radiusAt(now), maximumRadius);
    }

    /** 返回此刻实际传播半径（格）；旧寿命内完全复用原曲线，结束后按原基础速度继续。 */
    double radiusAt(double now) {
        double standardAge = Math.max(0, now - startGameTime) * STANDARD_DURATION / baseDuration;
        // 调用原扩张曲线，保留原范围内的抵达时刻和起始半径。
        if (standardAge <= visualEndAge) { return tuning.sphereRadius((float) standardAge); }
        return Math.min(maximumRadius, maximumVisualRadius + (standardAge - visualEndAge) * continuationSpeed);
    }

    /** 判断震动本身是否已传播到新边界，不读取视觉实例状态。 */
    private boolean isFinished(double now) { return now - startGameTime >= durationTicks; }
}
