package org.ywzj.rvp.client.state;

import java.lang.ref.WeakReference;

/** 已结束视频的短暂失图提示；不持有导弹、区块缓存、操控权限或服务器会话。 */
public final class RVP_HitlSignalLossPresentation {
    /** 失图显示一秒；使用单调时钟，服务器 TPS 波动不会延长黑屏。 */
    private static final long DURATION_NANOS = 1_000_000_000L;
    /** 弱引用只校验世界身份，登出后不因提示保留整个旧世界。 */
    private WeakReference<Object> world = new WeakReference<>(null);
    /** 本次提示开始的单调时钟纳秒值。 */
    private long startedAt;

    /** 仅由已结束的当前视频触发，主动切弹或退出调用 clear。 */
    public void begin(Object currentWorld, long now) {
        world = new WeakReference<>(currentWorld);
        startedAt = now;
    }

    /** 世界变化、时钟回退或一秒到期立即隐藏；重复查询不延长寿命。 */
    public boolean isVisible(Object currentWorld, long now) {
        long elapsed = now - startedAt;
        if (currentWorld == null || currentWorld != world.get() || elapsed < 0 || elapsed >= DURATION_NANOS) {
            clear();
            return false;
        }
        return true;
    }

    /** 主动退出、切弹和登出均不遗留上一个视频的 NO IMAGE。 */
    public void clear() {
        world.clear();
        startedAt = 0;
    }
}
