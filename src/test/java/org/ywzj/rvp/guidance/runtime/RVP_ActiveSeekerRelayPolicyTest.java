package org.ywzj.rvp.guidance.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ARH/AIR 中继段实时决策的单元测试（2026-10-06 实时中继语义，替代旧版永久锁存）。
 */
class RVP_ActiveSeekerRelayPolicyTest {

    @Test
    void supportedDesignationRelays() {
        // 有 designated 且支持链可用 → 重绑修正（持续照射即持续中继）
        RVP_ActiveSeekerRelayPolicy.RelayDecision d =
                RVP_ActiveSeekerRelayPolicy.decide(false, true, true);
        assertFalse(d.relayLost());
        assertTrue(d.rebind());
        assertFalse(d.freeze());
    }

    @Test
    void lostSupportFreezesAtLastKnownPoint() {
        // 有 designated 但支持链断（脱锁碟扫开/关雷达/超距）→ 冻结最后已知点滑行
        RVP_ActiveSeekerRelayPolicy.RelayDecision d =
                RVP_ActiveSeekerRelayPolicy.decide(false, true, false);
        assertTrue(d.relayLost());
        assertFalse(d.rebind());
        assertTrue(d.freeze());
    }

    @Test
    void supportRecoveryRelaysAgainImmediately() {
        // 失援后恢复跟踪 → 立即回到重绑（无锁存阻挡）——"重锁/雷达重开即恢复"语义
        RVP_ActiveSeekerRelayPolicy.RelayDecision recovered =
                RVP_ActiveSeekerRelayPolicy.decide(false, true, true);
        assertTrue(recovered.rebind());
        assertFalse(recovered.freeze());
    }

    @Test
    void noDesignationIsLoalNotRelayLoss() {
        // 无 designated（只开导引头未锁任何目标发射 / LOAL）：不算失援、不冻结、不重绑
        RVP_ActiveSeekerRelayPolicy.RelayDecision d =
                RVP_ActiveSeekerRelayPolicy.decide(false, false, false);
        assertFalse(d.relayLost());
        assertFalse(d.rebind());
        assertFalse(d.freeze());
    }

    @Test
    void caughtSeekerIsFullyAutonomous() {
        // 截获后完全自主：支持有无均不再介入（脱锁/关雷达不影响末段）
        RVP_ActiveSeekerRelayPolicy.RelayDecision supported =
                RVP_ActiveSeekerRelayPolicy.decide(true, true, true);
        RVP_ActiveSeekerRelayPolicy.RelayDecision unsupported =
                RVP_ActiveSeekerRelayPolicy.decide(true, true, false);
        assertFalse(supported.rebind() || supported.freeze());
        assertFalse(unsupported.rebind() || unsupported.freeze());
    }

    @Test
    void slideSelfDestructRespectsLimitAndDisable() {
        // 达到上限自爆；上限 ≤0 关闭
        assertTrue(RVP_ActiveSeekerRelayPolicy.shouldSelfDestruct(300, 300));
        assertTrue(RVP_ActiveSeekerRelayPolicy.shouldSelfDestruct(301, 300));
        assertFalse(RVP_ActiveSeekerRelayPolicy.shouldSelfDestruct(299, 300));
        assertFalse(RVP_ActiveSeekerRelayPolicy.shouldSelfDestruct(9999, 0));
    }
}
