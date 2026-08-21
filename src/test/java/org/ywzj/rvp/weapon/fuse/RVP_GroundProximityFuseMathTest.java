package org.ywzj.rvp.weapon.fuse;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_GroundProximityFuseMathTest {

    @Test
    void armGateRequiresPositiveDistanceAndElapsedArmTick() {
        assertFalse(RVP_GroundProximityFuseMath.isArmed(0f, 0, 100));
        assertFalse(RVP_GroundProximityFuseMath.isArmed(Float.NaN, 0, 100));
        assertFalse(RVP_GroundProximityFuseMath.isArmed(5f, 8, 7));
        assertTrue(RVP_GroundProximityFuseMath.isArmed(5f, 8, 8));
        assertTrue(RVP_GroundProximityFuseMath.isArmed(5f, 0, 0));
    }

    @Test
    void restoresExactPointForFastVerticalDescent() {
        Vec3 restored = RVP_GroundProximityFuseMath.restoreDetonationPosition(
                new Vec3(0, 10, 0), new Vec3(0, -2, 0), new Vec3(0, 0, 0), 3);

        assertVectorEquals(new Vec3(0, 3, 0), restored);
    }

    @Test
    void restoresPointForHorizontalTerrainCrossing() {
        Vec3 restored = RVP_GroundProximityFuseMath.restoreDetonationPosition(
                new Vec3(0, 5, 0), new Vec3(10, 5, 0), new Vec3(4, 3, 0), 2);

        assertVectorEquals(new Vec3(4, 5, 0), restored);
    }

    @Test
    void clampsHitProjectionToMovementSegmentAndHandlesStationaryProjectile() {
        Vec3 beforeStart = RVP_GroundProximityFuseMath.restoreDetonationPosition(
                Vec3.ZERO, new Vec3(10, 0, 0), new Vec3(-5, -2, 0), 2);
        Vec3 afterEnd = RVP_GroundProximityFuseMath.restoreDetonationPosition(
                Vec3.ZERO, new Vec3(10, 0, 0), new Vec3(15, -2, 0), 2);
        Vec3 stationary = RVP_GroundProximityFuseMath.restoreDetonationPosition(
                new Vec3(2, 3, 4), new Vec3(2, 3, 4), Vec3.ZERO, 3);

        assertVectorEquals(Vec3.ZERO, beforeStart);
        assertVectorEquals(new Vec3(10, 0, 0), afterEnd);
        assertVectorEquals(new Vec3(2, 3, 4), stationary);
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1.0E-9D);
        assertEquals(expected.y, actual.y, 1.0E-9D);
        assertEquals(expected.z, actual.z, 1.0E-9D);
    }
}
