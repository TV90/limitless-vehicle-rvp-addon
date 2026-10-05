package org.ywzj.rvp.guidance.runtime;

/**
 * ARH/AIR 导弹中继段实时决策（纯函数，零 Minecraft 依赖，可单元测试）。
 * <p>语义（2026-10-06 用户定版）：中继支持<b>每 tick 实时评估、无锁存无记忆</b>——
 * 支持可用（任一本车雷达/外置中继正以 TWS 探测或硬锁跟踪 designated）即重绑 designated
 * 持续修正；失援即冻结最后已知点滑行（清 targetEntity，不跟目标运动）；恢复跟踪立即恢复
 * 中继（瞬时断一拍自愈）。截获后完全自主，重绑/冻结均不再介入。</p>
 */
public final class RVP_ActiveSeekerRelayPolicy {

    private RVP_ActiveSeekerRelayPolicy() {
    }

    /** 中继决策：relayLost（失援标志，供 freeAcquire/诊断）、rebind（重绑修正）、freeze（冻结滑行）。 */
    public record RelayDecision(boolean relayLost, boolean rebind, boolean freeze) {
        public static final RelayDecision AUTONOMOUS = new RelayDecision(false, false, false);
    }

    /**
     * @param caught            主动导引头已截获目标（截获后完全自主，中继分支退出）
     * @param hasDesignation    存在发射时指定的中继目标（designated 快照有效）
     * @param supportAvailable  支持链可用（任一本车雷达/外置中继正以 TWS 探测或硬锁跟踪 designated）
     */
    public static RelayDecision decide(boolean caught, boolean hasDesignation, boolean supportAvailable) {
        if (caught) {
            return RelayDecision.AUTONOMOUS;
        }
        boolean relayLost = hasDesignation && !supportAvailable;
        boolean rebind = hasDesignation && supportAvailable;
        return new RelayDecision(relayLost, rebind, relayLost);
    }

    /**
     * 失援滑行自毁判定：连续失援计数达到上限（{@code relay_lost_self_destruct_ticks}，
     * ≤0 表示关闭上限）时自爆，避免弹群集体死点滑行过久。
     */
    public static boolean shouldSelfDestruct(int relayLostTicks, int limitTicks) {
        return limitTicks > 0 && relayLostTicks >= limitTicks;
    }
}
