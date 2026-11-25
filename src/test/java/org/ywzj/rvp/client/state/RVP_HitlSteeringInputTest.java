package org.ywzj.rvp.client.state;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RVP_HitlSteeringInputTest {
    @Test void inputJustBeforeNetworkTickIsNotCutByFortyPercent() {
        var input = new RVP_HitlSteeringInput();
        input.advance(0);
        input.add(50, -20, 49_000_000);
        input.advance(50_000_000);
        assertTrue(input.yaw() > 7.4F);
        assertTrue(input.pitch() < -2.96F);
    }

    @Test void recenteringDoesNotDependOnRenderOrNetworkSamplingFrequency() {
        var a = new RVP_HitlSteeringInput();
        var b = new RVP_HitlSteeringInput();
        a.add(100, -50, 0); b.add(100, -50, 0);
        for (int i = 1; i <= 144; i++) a.advance(Math.round(i * 1.0e9 / 144));
        b.advance(1_000_000_000L);
        assertEquals(b.yaw(), a.yaw(), 0.00001);
        assertEquals(b.pitch(), a.pitch(), 0.00001);
        assertEquals(0, a.yaw(), 0.001);
    }

    @Test void fullMouseDisplacementSurvivesCoalescingIntoOneFrame() {
        var single = new RVP_HitlSteeringInput();
        var split = new RVP_HitlSteeringInput();
        single.add(80, -60, 0);
        for (int i = 0; i < 8; i++) split.add(10, -7.5, 0);
        assertEquals(12, single.yaw(), 0.00001);
        assertEquals(split.yaw(), single.yaw(), 0.00001);
        assertEquals(split.pitch(), single.pitch(), 0.00001);
    }

    @Test void invalidInputCannotPoisonControlsAndNewSessionStartsNeutral() {
        var input = new RVP_HitlSteeringInput();
        input.add(1e30, -1e30, 0);
        assertEquals(45, input.yaw()); assertEquals(-45, input.pitch());
        input.add(Double.NaN, Double.POSITIVE_INFINITY, 0);
        assertEquals(45, input.yaw()); assertEquals(-45, input.pitch());
        input.advance(50_000_000);
        assertEquals(27, input.yaw(), 0.0001);
        input.reset();
        assertEquals(0, input.yaw()); assertEquals(0, input.pitch());
        input.add(10, 0, -1_000_000_000L);
        input.advance(-950_000_000L);
        assertEquals(0.9, input.yaw(), 0.00001);
    }
}
