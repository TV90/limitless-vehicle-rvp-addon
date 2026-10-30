package org.ywzj.rvp.server.light;

import java.util.Objects;
import java.util.function.Predicate;

/** 临时光恢复的纯状态机，可用假方块世界验证，不需要加载原版方块注册表。 */
public final class RVP_BlockLightRecovery {
    /** 恢复结果；只有成功恢复或检测到外部改动才能释放凭据。 */
    public enum Result {
        /** 区块未加载，保留凭据等待。 */
        WAIT_FOR_CHUNK(false),
        /** 未到期，仍在正常照明。 */
        ACTIVE(false),
        /** 写入恢复失败，下一 tick 重试。 */
        RETRY(false),
        /** 已恢复原来的空气状态。 */
        RESTORED(true),
        /** 玩家或其它系统已改变该位置，主动放弃所有权。 */
        EXTERNAL_CHANGE(true);

        /** 是否可以移除对应的恢复凭据。 */
        private final boolean release;
        Result(boolean release) { this.release = release; }
        /** 成功处理后才能移除凭据。 */
        public boolean release() { return release; }
    }

    private RVP_BlockLightRecovery() { }

    /** 恢复只针对仍由本系统持有的已加载方块；失败不丢弃凭据，也不覆盖新方块。 */
    public static <S> Result recover(boolean loaded, boolean expired, S placed, S original, S current,
            Predicate<S> restore) {
        if (!loaded) { return Result.WAIT_FOR_CHUNK; }
        if (!Objects.equals(placed, current)) { return Result.EXTERNAL_CHANGE; }
        if (!expired) { return Result.ACTIVE; }
        return restore.test(original) ? Result.RESTORED : Result.RETRY;
    }

    /** 邻近连射可续期，但单个连续光最多保留 40 tick，不能无限推迟清理。 */
    public static long renewedExpiry(long created, long expires, long now, int lifetime) {
        return Math.min(created + 40, Math.min(now + lifetime, expires + 2));
    }
}
