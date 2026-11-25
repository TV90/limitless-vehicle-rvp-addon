package org.ywzj.rvp.client.state;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 覆盖低速拖拽、角度跨界、丢包和旧弹清理，不依赖渲染线程。 */
class RVP_HitlCameraRotationTest {
    @Test void preservesSubByteTurnsAndInterpolatesWithinOneTick() {
        var rotation = new RVP_HitlCameraRotation();
        rotation.accept(1, 0, 0, 0);
        rotation.accept(2, 0.12f, -0.08f, 50_000_000);
        assertEquals(0.06f, rotation.yaw(75_000_000), 0.00001f);
        assertEquals(-0.04f, rotation.pitch(75_000_000), 0.00001f);
        assertEquals(0.12f, rotation.yaw(100_000_000), 0.00001f);
    }

    @Test void crossingNorthTakesShortestPathAndNewPacketsDoNotSnap() {
        var rotation = new RVP_HitlCameraRotation();
        rotation.accept(1, 179.9f, 0, 0);
        rotation.accept(2, -179.9f, 0, 50_000_000);
        float before = rotation.yaw(75_000_000);
        assertEquals(180f, before, 0.0001f);
        rotation.accept(3, -179.8f, 0, 75_000_000);
        assertEquals(before, rotation.yaw(75_000_000));
        assertEquals(180.2f, rotation.yaw(125_000_000), 0.0001f);
    }

    @Test void rejectsOldAndInvalidPacketsAndExpiresWithoutExtending() {
        var rotation = new RVP_HitlCameraRotation();
        rotation.accept(10, 2, 3, 0);
        rotation.accept(9, 100, 100, 20_000_000);
        rotation.accept(11, Float.NaN, 0, 20_000_000);
        assertEquals(2, rotation.yaw(40_000_000));
        assertTrue(rotation.fresh(249_999_999));
        assertFalse(rotation.fresh(250_000_000));
        rotation.clear();
        assertFalse(rotation.fresh(40_000_000));
        rotation.accept(1, 30, 4, 50_000_000);
        assertEquals(30, rotation.yaw(50_000_000));
    }
}
