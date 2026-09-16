package org.ywzj.rvp.client.state;

import net.minecraft.Util;

/**
 * 客户端激光致盲状态（2026-09-17）：被 rvp:laser 命中累计达标后由
 * {@code S2CLaserBlind} 驱动，白色闪光滤镜渲染见
 * {@code org.ywzj.rvp.client.laser.RVP_LaserBlindOverlay}。
 * 仅客户端加载（经 DistExecutor 全限定名引用），重复触发刷新时长。
 */
public final class RVP_ClientLaserBlindState {

    private static long blindUntilMs;
    private static long blindTotalMs;

    private RVP_ClientLaserBlindState() {
    }

    /** 进入/刷新致盲：durationTick 为服务端下发的致盲时长。 */
    public static void blind(int durationTick) {
        blindTotalMs = Math.max(durationTick, 1) * 50L;
        blindUntilMs = Util.getMillis() + blindTotalMs;
    }

    /** 剩余致盲时长（毫秒），已结束返回 0。 */
    public static long remainingMs() {
        return Math.max(0L, blindUntilMs - Util.getMillis());
    }

    /** 本次致盲的总时长（毫秒），供渐隐比例计算。 */
    public static long totalMs() {
        return blindTotalMs;
    }

    public static void clear() {
        blindUntilMs = 0L;
        blindTotalMs = 0L;
    }
}
