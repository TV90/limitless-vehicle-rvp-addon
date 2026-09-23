package org.ywzj.rvp.client.firecontrol;

import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_BallisticLeadFireControlPolicyTest {

    @Test
    void existingRvpRfStillRequiresRfSensor() {
        assertEquals(
                RVP_BallisticLeadFireControlPolicy.Profile.RVP_RF,
                RVP_BallisticLeadFireControlPolicy.resolve(
                        "rvp_rf",
                        WeaponUnitData.FireControlSensorType.RF,
                        true
                )
        );
        assertEquals(
                RVP_BallisticLeadFireControlPolicy.Profile.NONE,
                RVP_BallisticLeadFireControlPolicy.resolve(
                        "rvp_rf",
                        WeaponUnitData.FireControlSensorType.EO,
                        true
                )
        );
    }

    @Test
    void existingRvpRfKeepsNonMachinegunSoftTrackingProfile() {
        assertEquals(
                RVP_BallisticLeadFireControlPolicy.Profile.RVP_RF,
                RVP_BallisticLeadFireControlPolicy.resolve(
                        "RVP_RF",
                        WeaponUnitData.FireControlSensorType.RF,
                        false
                )
        );
        assertFalse(RVP_BallisticLeadFireControlPolicy.supportsStabilizer(
                "rvp_rf",
                WeaponUnitData.FireControlSensorType.RF,
                false
        ));
    }

    @Test
    void ballisticLeadAcceptsEveryLockCapableSensorForMachinegun() {
        for (WeaponUnitData.FireControlSensorType sensorType : new WeaponUnitData.FireControlSensorType[] {
                WeaponUnitData.FireControlSensorType.IR,
                WeaponUnitData.FireControlSensorType.EO,
                WeaponUnitData.FireControlSensorType.RF
        }) {
            assertEquals(
                    RVP_BallisticLeadFireControlPolicy.Profile.BALLISTIC_LEAD,
                    RVP_BallisticLeadFireControlPolicy.resolve(
                            "rvp_ballistic_lead",
                            sensorType,
                            true
                    )
            );
            assertTrue(RVP_BallisticLeadFireControlPolicy.supportsStabilizer(
                    "rvp_ballistic_lead",
                    sensorType,
                    true
            ));
        }
    }

    @Test
    void ballisticLeadRejectsNonMachinegunAndNonLockingSensors() {
        assertEquals(
                RVP_BallisticLeadFireControlPolicy.Profile.NONE,
                RVP_BallisticLeadFireControlPolicy.resolve(
                        "rvp_ballistic_lead",
                        WeaponUnitData.FireControlSensorType.EO,
                        false
                )
        );
        assertEquals(
                RVP_BallisticLeadFireControlPolicy.Profile.NONE,
                RVP_BallisticLeadFireControlPolicy.resolve(
                        "rvp_ballistic_lead",
                        WeaponUnitData.FireControlSensorType.CCIP,
                        true
                )
        );
        assertEquals(
                RVP_BallisticLeadFireControlPolicy.Profile.NONE,
                RVP_BallisticLeadFireControlPolicy.resolve(
                        "unknown",
                        WeaponUnitData.FireControlSensorType.RF,
                        true
                )
        );
    }
}
