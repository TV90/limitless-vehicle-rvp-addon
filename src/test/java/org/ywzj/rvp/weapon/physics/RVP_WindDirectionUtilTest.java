package org.ywzj.rvp.weapon.physics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_WindDirectionUtilTest {

    @Test
    void currentFacingOverridesDifferentMotionDirection() {
        Vec3 currentFacingNorth = new Vec3(0.0, 0.0, -1.0);
        Vec3 currentMotionEast = new Vec3(1.0, 0.0, 0.0);

        Vec3 wind = RVP_WindDirectionUtil.resolveParentFacingReverse(
                currentFacingNorth, currentMotionEast, 0.0f);

        assertVectorEquals(new Vec3(0.0, 0.0, 1.0), wind);
    }

    @Test
    void verticalFacingFallsBackToReverseCurrentHorizontalMotion() {
        Vec3 wind = RVP_WindDirectionUtil.resolveParentFacingReverse(
                new Vec3(0.0, -1.0, 0.0), new Vec3(1.0, -0.2, 0.0), 0.0f);

        assertVectorEquals(new Vec3(-1.0, 0.0, 0.0), wind);
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1.0E-9);
        assertEquals(expected.y, actual.y, 1.0E-9);
        assertEquals(expected.z, actual.z, 1.0E-9);
    }
}
