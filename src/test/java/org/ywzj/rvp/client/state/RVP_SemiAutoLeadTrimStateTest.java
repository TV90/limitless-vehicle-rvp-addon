package org.ywzj.rvp.client.state;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.util.VectorUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_SemiAutoLeadTrimStateTest {

    @Test
    void localTrimFollowsMovingBaseDirection() {
        Vec3 movedBase = VectorUtil.rotToVec(-12.0F, 35.0F);

        Vec3 actual = RVP_SemiAutoLeadTrimState.applyLocalTrim(
                movedBase,
                2.0F,
                -3.0F,
                10.0F
        );
        Vec3 expected = VectorUtil.rotToVec(-10.0F, 32.0F);

        assertEquals(0.0D, VectorUtil.angleBetween(actual, expected), 1.0E-6D);
    }

    @Test
    void finalDirectionNeverExceedsOffAxisBoundary() {
        Vec3 base = VectorUtil.rotToVec(0.0F, 0.0F);

        Vec3 actual = RVP_SemiAutoLeadTrimState.applyLocalTrim(
                base,
                20.0F,
                20.0F,
                5.0F
        );
        double actualAngleDeg = Math.toDegrees(VectorUtil.angleBetween(base, actual));

        assertEquals(5.0D, actualAngleDeg, 1.0E-4D);
    }

    @Test
    void twoAxisOffsetUsesCircularRatherThanSquareBoundary() {
        float[] actual = RVP_SemiAutoLeadTrimState.clampOffsetMagnitude(
                6.0F,
                8.0F,
                5.0F
        );

        assertEquals(3.0F, actual[0], 1.0E-6F);
        assertEquals(4.0F, actual[1], 1.0E-6F);
        assertEquals(5.0D, Math.hypot(actual[0], actual[1]), 1.0E-6D);
    }

    @Test
    void zeroTrimLeavesLeadDirectionUnchanged() {
        Vec3 base = VectorUtil.rotToVec(18.0F, -47.0F);

        Vec3 actual = RVP_SemiAutoLeadTrimState.applyLocalTrim(base, 0.0F, 0.0F, 10.0F);

        assertTrue(VectorUtil.angleBetween(base, actual) < 1.0E-6D);
    }
}
