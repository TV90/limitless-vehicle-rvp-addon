package org.ywzj.rvp.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_MotorFlameRenderGateTest {

    @Test
    void ordinaryEntityUsesItsLocalMotorState() {
        assertTrue(RVP_MotorFlameRenderGate.shouldRender(false, true, false));
        assertFalse(RVP_MotorFlameRenderGate.shouldRender(false, false, true));
    }

    @Test
    void remoteCloneUsesAuthoritativeSnapshotAndFailsClosed() {
        assertTrue(RVP_MotorFlameRenderGate.shouldRender(true, false, true));
        assertFalse(RVP_MotorFlameRenderGate.shouldRender(true, true, false));
    }
}
