package org.ywzj.rvp.entity.projectile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ProjectileMotionTest {

    /** 冷发射覆盖点火延迟时，当前 Tick 的 PRESET 制导速度必须完成接管。 */
    @Test
    void overlappingColdLaunchAndIgnitionKeepsPresetGuidance() {
        assertTrue(RVP_ProjectileMotion.shouldPreserveGuidanceDuringColdLaunch(
                15, 20, 20, true));
    }

    /** 冷发射已早于点火结束时，继续沿用原有冷发射运动，保持伊斯坎德尔行为不变。 */
    @Test
    void shorterColdLaunchKeepsOriginalPreIgnitionMotion() {
        assertFalse(RVP_ProjectileMotion.shouldPreserveGuidanceDuringColdLaunch(
                5, 10, 20, true));
    }

    /** 没有成功写入 PRESET 制导速度时，不得误触发冷发射接管。 */
    @Test
    void missingGuidanceDoesNotTriggerHandoff() {
        assertFalse(RVP_ProjectileMotion.shouldPreserveGuidanceDuringColdLaunch(
                15, 20, 20, false));
    }

    /** 冷发射结束后的 Tick 不再走冷发射制导接管分支。 */
    @Test
    void completedColdLaunchDoesNotTriggerHandoff() {
        assertFalse(RVP_ProjectileMotion.shouldPreserveGuidanceDuringColdLaunch(
                20, 20, 20, true));
    }
}
