package org.ywzj.rvp.client.state;

import java.lang.ref.WeakReference;

/** TV 失联与结束时的显示时钟，不持有导弹、区块缓存或操控权限。 */
public final class RVP_HitlSignalLossPresentation {
    /** 失图显示一秒；使用单调时钟，服务器 TPS 波动不会延长黑屏。 */
    private static final long DURATION_NANOS = 1_000_000_000L;
    /** 失联时渐入、恢复时渐出的时长；引爆不走过渡。 */
    private static final long FADE_NANOS = 350_000_000L;
    /** 弱引用只校验世界身份，登出后不因提示保留整个旧世界。 */
    private WeakReference<Object> world = new WeakReference<>(null);
    /** 最近一次链路变化的单调时钟与当时的透明度。 */
    private long changedAt;
    private float fromOpacity;
    /** 本段过渡的目标。 */
    private boolean blocked;
    /** 彻底结束后才开始一秒的退场计时。 */
    private long endedAt;
    /** 普通断链和引爆具有不同的退场方式。 */
    private enum End { NONE, SIGNAL_LOSS, DETONATION }
    private End end = End.NONE;

    /** 结束动画只能属于仍在载具上观察的当前 TV 弹；下车、退出和旧弹消息均不触发。 */
    public static boolean shouldBegin(int activeId, int endedId, boolean viewingTv, boolean onVehicle) {
        return activeId >= 0 && activeId == endedId && viewingTv && onVehicle;
    }

    /** 重复快照不重启动画；信号恢复时从当前透明度反向渐出。 */
    public void setBlocked(Object currentWorld, boolean blocked, long now) {
        if (!valid(currentWorld, now)) {
            if (!blocked || currentWorld == null) return;
            world = new WeakReference<>(currentWorld);
            changedAt = now;
        }
        if (this.blocked == blocked || end != End.NONE) return;
        fromOpacity = linkOpacity(now);
        changedAt = now;
        this.blocked = blocked;
    }

    /** 彻底断链接续当前透明度，结束提示的最后一段淡出。 */
    public void endLink(Object currentWorld, long now) {
        setBlocked(currentWorld, true, now);
        if (currentWorld == null) return;
        end = End.SIGNAL_LOSS;
        endedAt = now;
    }

    /** 引爆从首帧开始全不透明，保持原有一秒显示时长。 */
    public void detonate(Object currentWorld, long now) {
        if (currentWorld == null) return;
        world = new WeakReference<>(currentWorld);
        changedAt = now;
        end = End.DETONATION;
        endedAt = now;
    }

    /** 世界变化、时钟回退或一秒到期立即隐藏；重复查询不延长寿命。 */
    public boolean isVisible(Object currentWorld, long now) {
        return valid(currentWorld, now) && end != End.NONE;
    }

    /** 根据真实时间计算透明度，窗口缩放和帧数不会改变渐入速度。 */
    public float opacity(Object currentWorld, long now) {
        if (!valid(currentWorld, now)) return 0f;
        if (end == End.DETONATION) return 1f;
        float alpha = linkOpacity(now);
        if (end == End.SIGNAL_LOSS) {
            return Math.min(alpha, (DURATION_NANOS - (now - endedAt)) / (float) FADE_NANOS);
        }
        if (!blocked && alpha == 0f) clear();
        return alpha;
    }

    /** 从反转前的值连续变化，快速反复堵塞也不会闪变。 */
    private float linkOpacity(long now) {
        float progress = (now - changedAt) / (float) FADE_NANOS;
        return blocked ? Math.min(1f, fromOpacity + progress) : Math.max(0f, fromOpacity - progress);
    }

    private boolean valid(Object currentWorld, long now) {
        if (currentWorld == null || currentWorld != world.get() || now - changedAt < 0
                || (end != End.NONE && (now - endedAt < 0 || now - endedAt >= DURATION_NANOS))) {
            clear();
            return false;
        }
        return true;
    }

    /** 主动退出、切弹和登出均不遗留上一个视频的 NO IMAGE。 */
    public void clear() {
        world.clear();
        blocked = false;
        fromOpacity = 0f;
        end = End.NONE;
    }
}
