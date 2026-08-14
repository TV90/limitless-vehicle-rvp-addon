package org.ywzj.rvp.client.state;

/**
 * 客户端锁定告警状态（血条上方提示文案的数据源）。
 *
 * <p>由服务端 {@code S2CMissileTrackAlert} 在导弹跟踪目标时周期写入（ARH 记入
 * {@code arhTrackAt}，IR/AIR 记入 {@code irTrackAt}）。时间用单调递增的系统毫秒
 * （{@link System#currentTimeMillis()}），窗口过期判定不受世界切换 / gameTime 回绕影响：
 * 上个世界留下的旧标记在 `now - markTime` 下一定是大正数 → 正确判定已过期。</p>
 *
 * <p>雷达锁定（RADAR_LOCK）由本体 {@code WarningReceiver.targets} 直接判定，不在此维护。</p>
 */
public final class RVP_ClientLockWarningState {

    /** 告警窗口（毫秒）：服务端约每 10 tick（0.5s）刷新一次，窗口 1.5s 保证连续显示。 */
    private static final long WINDOW_MS = 1500;

    private static long arhTrackAt = 0L;
    private static long irTrackAt = 0L;

    private RVP_ClientLockWarningState() {}

    public static void markArhTrack() {
        arhTrackAt = System.currentTimeMillis();
    }

    public static void markIrTrack() {
        irTrackAt = System.currentTimeMillis();
    }

    public static boolean isArhTrack() {
        return System.currentTimeMillis() - arhTrackAt < WINDOW_MS;
    }

    public static boolean isIrTrack() {
        return System.currentTimeMillis() - irTrackAt < WINDOW_MS;
    }
}
