package org.ywzj.rvp.hitl;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.gui.RVP_HitlCursorMotion;
import static org.junit.jupiter.api.Assertions.*;

class RVP_HitlCursorMotionTest {
    @Test void leftInputMovesLeftAndReturnsSmoothlyWithoutOvershoot() {
        var cursor = new RVP_HitlCursorMotion();
        cursor.update(-12, 0, 0.15);
        assertTrue(cursor.x() < -50);
        double previous = cursor.x();
        cursor.update(0, 0, 0.01);
        assertTrue(cursor.x() < -1, "松手不能瞬间归零");
        for (int i = 0; i < 180; i++) cursor.update(0, 0, 1.0 / 120);
        assertEquals(0, cursor.x(), 0.01);
        assertEquals(0, cursor.y(), 0.00001);
        assertTrue(previous < cursor.x());
    }
    @Test void exactDampingIsIndependentOfFrameRate() {
        var a = new RVP_HitlCursorMotion(); var b = new RVP_HitlCursorMotion();
        for (int i = 0; i < 30; i++) a.update(8, -4, 1.0 / 30);
        for (int i = 0; i < 144; i++) b.update(8, -4, 1.0 / 144);
        assertEquals(a.x(), b.x(), 1e-8); assertEquals(a.y(), b.y(), 1e-8);
        a.reset(); assertEquals(0, a.x()); assertEquals(0, a.y());
    }
}
