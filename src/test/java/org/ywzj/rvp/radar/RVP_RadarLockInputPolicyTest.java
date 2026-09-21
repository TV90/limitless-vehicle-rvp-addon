package org.ywzj.rvp.radar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R 键雷达锁定输入对 ARH 软跟踪、手动硬锁和外置锁的状态分类测试。 */
class RVP_RadarLockInputPolicyTest {

    @Test
    void activeRadarSeekerOnlyTrackCanBePromotedByManualLock() {
        // RF + 导引头开机 + 仅 WeaponUnit 有目标，正是 ARH 自动 TWS 软跟踪状态。
        assertTrue(RVP_RadarRoleHelper.isAutomaticRfSeekerTrackState(
                true, true, true, false, false, false, true));
    }

    @Test
    void hardRadarLockIsStillTreatedAsExplicitLock() {
        // RadarUnit 已有目标时，下一次按 R 必须继续走原有解锁语义。
        assertFalse(RVP_RadarRoleHelper.isAutomaticRfSeekerTrackState(
                true, true, true, true, false, false, true));
    }

    @Test
    void pendingManualLockIsNotMistakenForAutomaticTrack() {
        // 火控雷达尚未探测到目标时的 pending 仍属于一次手动锁定请求，再按 R 应取消。
        assertFalse(RVP_RadarRoleHelper.isAutomaticRfSeekerTrackState(
                true, true, true, false, true, false, true));
    }

    @Test
    void externalRadarLockIsNotMistakenForAutomaticTrack() {
        // 外置雷达目标即使同步进 WeaponUnit，也必须由外置锁分支负责清除。
        assertFalse(RVP_RadarRoleHelper.isAutomaticRfSeekerTrackState(
                true, true, true, false, false, true, true));
    }

    @Test
    void sarhCannotEnterAutomaticRfTrackState() {
        // SARH 被自动捕获能力门排除，保持必须按 R 建立照射锁的既有语义。
        assertFalse(RVP_RadarRoleHelper.isAutomaticRfSeekerTrackState(
                true, true, true, false, false, false, false));
    }

    @Test
    void seekerOffLocalLockKeepsLegacyUnlockBehavior() {
        // 导引头未开机时的本地锁不属于自动软跟踪，避免改变旧版 R 键切换语义。
        assertFalse(RVP_RadarRoleHelper.isAutomaticRfSeekerTrackState(
                true, false, true, false, false, false, true));
    }
}
