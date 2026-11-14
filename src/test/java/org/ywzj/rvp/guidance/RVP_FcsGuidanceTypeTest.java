package org.ywzj.rvp.guidance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_FcsGuidanceTypeTest {

    @Test
    void illuminationAndCommandGuidedTypesDependOnFcs() {
        assertTrue(RVP_GuidanceRuntimeController.isFcsDependentGuidance(RVP_EnumGuidanceType.LH));
        assertTrue(RVP_GuidanceRuntimeController.isFcsDependentGuidance(RVP_EnumGuidanceType.SALH));
        assertTrue(RVP_GuidanceRuntimeController.isFcsDependentGuidance(RVP_EnumGuidanceType.LBR));
        assertTrue(RVP_GuidanceRuntimeController.isFcsDependentGuidance(RVP_EnumGuidanceType.SACLOS));
        assertTrue(RVP_GuidanceRuntimeController.isFcsDependentGuidance(RVP_EnumGuidanceType.SARH));
    }

    @Test
    void fireAndForgetAndNavigationTypesDoNotDependOnFcs() {
        assertFalse(RVP_GuidanceRuntimeController.isFcsDependentGuidance(RVP_EnumGuidanceType.IR));
        assertFalse(RVP_GuidanceRuntimeController.isFcsDependentGuidance(RVP_EnumGuidanceType.ARH));
        assertFalse(RVP_GuidanceRuntimeController.isFcsDependentGuidance(RVP_EnumGuidanceType.AIR));
        assertFalse(RVP_GuidanceRuntimeController.isFcsDependentGuidance(RVP_EnumGuidanceType.GPS));
        assertFalse(RVP_GuidanceRuntimeController.isFcsDependentGuidance(RVP_EnumGuidanceType.NONE));
    }
}