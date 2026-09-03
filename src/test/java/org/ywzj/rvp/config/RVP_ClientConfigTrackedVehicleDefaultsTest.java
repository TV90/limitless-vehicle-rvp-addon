package org.ywzj.rvp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ClientConfigTrackedVehicleDefaultsTest {
    @Test
    void usesConservativeTrackedVehicleProtectionDefaultsBeforeConfigRegistration() {
        assertTrue(RVP_ClientConfig.shouldProtectDistantHorizonsTrackedVehicles());
        assertEquals(0.5F, RVP_ClientConfig.getDistantHorizonsTrackedOcclusionBiasBlocks());
        assertEquals(64, RVP_ClientConfig.getDistantHorizonsMaxProtectedTrackedVehicles());
    }
}
