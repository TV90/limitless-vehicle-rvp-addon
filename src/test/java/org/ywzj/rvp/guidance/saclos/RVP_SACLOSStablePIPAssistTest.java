package org.ywzj.rvp.guidance.saclos;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 方案A的纯状态门控回归：只允许STABLE下的rvp_rf/RF/SACLOS导弹进入PIP。 */
class RVP_SACLOSStablePIPAssistTest {

    @Test
    void stableRFSACLOSMissileWithPredictiveInterceptIsEligible() {
        assertTrue(supports(
                true, true, "rvp_rf", WeaponUnitData.FireControlSensorType.RF,
                RVP_EnumGuidanceType.SACLOS, true, 10, 10, null));
    }

    @Test
    void rejectsNonStableNonRFAndNonSACLOSPaths() {
        assertFalse(supports(
                false, true, "rvp_rf", WeaponUnitData.FireControlSensorType.RF,
                RVP_EnumGuidanceType.SACLOS, true, 10, 10, null));
        assertFalse(supports(
                true, true, "rvp_ballistic_lead", WeaponUnitData.FireControlSensorType.RF,
                RVP_EnumGuidanceType.SACLOS, true, 10, 10, null));
        assertFalse(supports(
                true, true, "rvp_rf", WeaponUnitData.FireControlSensorType.EO,
                RVP_EnumGuidanceType.SACLOS, true, 10, 10, null));
        assertFalse(supports(
                true, true, "rvp_rf", WeaponUnitData.FireControlSensorType.RF,
                RVP_EnumGuidanceType.LBR, true, 10, 10, null));
    }

    @Test
    void rejectsDisabledOrNotYetStartedPipAndTopAttack() {
        assertFalse(supports(
                true, true, "rvp_rf", WeaponUnitData.FireControlSensorType.RF,
                RVP_EnumGuidanceType.SACLOS, false, 10, 10, null));
        assertFalse(supports(
                true, true, "rvp_rf", WeaponUnitData.FireControlSensorType.RF,
                RVP_EnumGuidanceType.SACLOS, true, 9, 10, null));
        assertFalse(supports(
                true, true, "rvp_rf", WeaponUnitData.FireControlSensorType.RF,
                RVP_EnumGuidanceType.SACLOS, true, 10, 10, 20.0F));
        assertFalse(supports(
                true, false, "rvp_rf", WeaponUnitData.FireControlSensorType.RF,
                RVP_EnumGuidanceType.SACLOS, true, 10, 10, null));
    }

    /** 调用被测纯策略，保持各用例的参数顺序集中可读。 */
    private static boolean supports(boolean stableRequestFresh,
                                    boolean missile,
                                    String fireControlMode,
                                    WeaponUnitData.FireControlSensorType sensorType,
                                    RVP_EnumGuidanceType guidanceType,
                                    boolean predictTargetPos,
                                    int flightTick,
                                    int predictStartTick,
                                    Float topAttackHeight) {
        return RVP_SACLOSStablePIPAssist.supportsStablePIP(
                stableRequestFresh,
                missile,
                fireControlMode,
                sensorType,
                guidanceType,
                predictTargetPos,
                flightTick,
                predictStartTick,
                topAttackHeight
        );
    }
}
