package org.ywzj.rvp.weapon.physics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_DeploymentMotionUtilTest {

    @Test
    void twentyTickHalfLifeProducesDocumentedCurve() {
        Vec3 initial = new Vec3(1.5D, -9.0D, -0.5D);
        Vec3 velocity = initial;
        for (int tick = 1; tick <= 60; tick++) {
            // 调用本项目部署数学工具：验证权威 X/Z 分量按半衰期连续衰减且不携带 Y。
            velocity = RVP_DeploymentMotionUtil.decayHorizontal(velocity, 20f);
            if (tick == 20) {
                assertHorizontalEquals(initial.scale(0.5D), velocity);
            } else if (tick == 40) {
                assertHorizontalEquals(initial.scale(0.25D), velocity);
            } else if (tick == 60) {
                assertHorizontalEquals(initial.scale(0.125D), velocity);
            }
            assertEquals(0.0D, velocity.y, 1.0E-12D);
        }
    }

    @Test
    void decayIsMonotonicAndKeepsHorizontalDirection() {
        Vec3 velocity = new Vec3(-1.0D, 0.0D, 2.0D);
        Vec3 direction = velocity.normalize();
        double previousLength = velocity.length();
        for (int tick = 0; tick < 80; tick++) {
            velocity = RVP_DeploymentMotionUtil.decayHorizontal(velocity, 20f);
            assertTrue(velocity.length() < previousLength);
            assertTrue(velocity.normalize().dot(direction) > 0.999999D);
            previousLength = velocity.length();
        }
    }

    @Test
    void externalVelocityChangesAreAbsorbedIntoBaseComponent() {
        Vec3 base = new Vec3(0.0D, -0.5D, 0.0D);
        Vec3 previousComposed = new Vec3(1.2D, -0.5D, 0.4D);
        Vec3 collisionResult = new Vec3(-0.6D, -0.2D, 0.2D);

        Vec3 absorbedBase = RVP_DeploymentMotionUtil.absorbExternalDelta(
                base, collisionResult, previousComposed);
        Vec3 unchangedOtherComponents = previousComposed.subtract(base);

        assertEquals(collisionResult.x,
                absorbedBase.add(unchangedOtherComponents).x, 1.0E-9D);
        assertEquals(collisionResult.y,
                absorbedBase.add(unchangedOtherComponents).y, 1.0E-9D);
        assertEquals(collisionResult.z,
                absorbedBase.add(unchangedOtherComponents).z, 1.0E-9D);
    }

    private static void assertHorizontalEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1.0E-9D);
        assertEquals(expected.z, actual.z, 1.0E-9D);
    }
}
