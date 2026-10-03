package org.ywzj.rvp.weapon.impact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_MissileAirTargetImpactFragmentSettingsTest {
    /** 圆锥参数加入快照后，必须保持圆锥角、阻尼和停止速度的字段映射不串位。 */
    @Test
    void snapshotKeepsConeAndMotionParametersInDeclaredOrder() {
        RVP_MissileAirTargetImpactFragmentSettings.reset();
        try {
            RVP_MissileAirTargetImpactFragmentSettings.setFragmentConeHalfAngleDegrees(30.0F);
            RVP_MissileAirTargetImpactFragmentSettings.setDamping(0.9F);
            RVP_MissileAirTargetImpactFragmentSettings.setStopSpeed(0.05F);

            RVP_MissileAirTargetImpactFragmentSettings.Snapshot snapshot =
                    RVP_MissileAirTargetImpactFragmentSettings.snapshot();

            assertEquals(30.0F, snapshot.fragmentConeHalfAngleDegrees(), 1.0E-6F);
            assertEquals(0.9F, snapshot.damping(), 1.0E-6F);
            assertEquals(0.05F, snapshot.stopSpeed(), 1.0E-6F);
        } finally {
            RVP_MissileAirTargetImpactFragmentSettings.reset();
        }
    }
}
