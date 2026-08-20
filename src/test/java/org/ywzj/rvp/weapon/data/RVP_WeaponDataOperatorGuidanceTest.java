package org.ywzj.rvp.weapon.data;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证需要持续同步世界瞄准点的制导类型集合。 */
class RVP_WeaponDataOperatorGuidanceTest {

    @Test
    void lbrAndSaclosBothSynchronizeOperatorWorldPoint() {
        assertTrue(RVP_WeaponData.isOperatorGuidanceType(RVP_EnumGuidanceType.LBR));
        assertTrue(RVP_WeaponData.isOperatorGuidanceType(RVP_EnumGuidanceType.SACLOS));
    }

    @Test
    void independentSeekersDoNotSynchronizeOperatorWorldPoint() {
        assertFalse(RVP_WeaponData.isOperatorGuidanceType(RVP_EnumGuidanceType.ARH));
        assertFalse(RVP_WeaponData.isOperatorGuidanceType(RVP_EnumGuidanceType.IR));
        assertFalse(RVP_WeaponData.isOperatorGuidanceType(null));
    }
}
