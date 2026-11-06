package org.ywzj.rvp.client.visual.shake;

import org.ywzj.rvp.config.RVP_ClientConfig;

/** 单个冲击波的震动抵达判定；传播半径与最大衰减范围由调用方分别提供。 */
public final class RVP_ShockwaveScreenShake {
    /** 沿用温压手感：持续 12 刻（0.6 秒），左右/上下/倾斜振幅为 0.90/0.70/0.50 度。 */
    static final RVP_ScreenShakeParameters PARAMETERS = new RVP_ScreenShakeParameters(
            12, 0.90F, 0.70F, 0.50F, 2.17D, 2.63D, 1.91D);
    /** 新增冲击波反馈独立限幅至每轴 2 度，实时遵循 RVP 爆炸震动设置。 */
    private static final RVP_ScreenShakeScheduler SHAKES = new RVP_ScreenShakeScheduler(
            2.0F, RVP_ClientConfig::getExplosionShakeIntensity);
    /** 本波面的确定性种子，只控制三轴摆动的初始相位。 */
    private final long seed;
    /** 执行本波面反馈的调度器；生产实例统一复用 SHAKES。 */
    private final RVP_ScreenShakeScheduler scheduler;
    /** 是否已经抵达玩家；即使边界强度为零，离开后重新进入也不能再次触发。 */
    private boolean arrived;

    public RVP_ShockwaveScreenShake(long seed) {
        // 复用本调用方的统一调度器，让连续冲击波共享叠加上限和实时强度设置。
        this(seed, SHAKES);
    }

    /** 允许数值测试传入独立调度器，不依赖运行中的游戏或客户端配置。 */
    RVP_ShockwaveScreenShake(long seed, RVP_ScreenShakeScheduler scheduler) {
        this.seed = seed;
        this.scheduler = scheduler;
    }

    /**
     * 在现有相机事件中检查抵达，不查询地形或相机位置，也不预先固定玩家距离。
     *
     * @param now 当前连续世界时间，单位游戏刻，包含帧内插值
     * @param distance 此帧玩家位置到真实爆心的三维直线距离，单位格
     * @param radius 此帧震动已经传播到的半径，单位格，不在此处缩放传播速度
     * @param maximumRadius 震动的最大衰减半径，单位格
     * @return 是否成功创建本次震动；边界处零强度也会记录为已抵达
     */
    public boolean update(double now, double distance, double radius, double maximumRadius) {
        if (arrived || !Double.isFinite(now) || !Double.isFinite(distance) || distance < 0
                || !Double.isFinite(radius) || radius < 0
                || !Double.isFinite(maximumRadius) || maximumRadius <= 0
                || distance > radius || distance > maximumRadius) {
            return false;
        }
        arrived = true;
        // 调用通用调度器按独立的最大范围线性衰减；任务仍独立播放完 12 刻。
        return scheduler.schedule(this, now, now, seed, distance, maximumRadius, PARAMETERS);
    }
}
