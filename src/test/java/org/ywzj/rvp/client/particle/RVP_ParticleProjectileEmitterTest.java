package org.ywzj.rvp.client.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ParticleProjectileEmitterTest {

    @Test
    void horizontalOffsetSmoothlyReachesTargetWithinConfiguredInterval() {
        double first = RVP_ParticleProjectileEmitter.interpolateHorizontalOffset(0.0D, 1.0D, 1, 4);
        double second = RVP_ParticleProjectileEmitter.interpolateHorizontalOffset(0.0D, 1.0D, 2, 4);
        double third = RVP_ParticleProjectileEmitter.interpolateHorizontalOffset(0.0D, 1.0D, 3, 4);
        double fourth = RVP_ParticleProjectileEmitter.interpolateHorizontalOffset(0.0D, 1.0D, 4, 4);

        assertTrue(first > 0.0D && first < second);
        assertTrue(second < third && third < fourth);
        assertEquals(0.5D, second, 1.0E-9D);
        assertEquals(1.0D, fourth, 1.0E-9D);
    }

    @Test
    void oneTickIntervalPreservesImmediateSamplingBehavior() {
        assertEquals(-0.75D,
                RVP_ParticleProjectileEmitter.interpolateHorizontalOffset(0.5D, -0.75D, 1, 1),
                1.0E-9D);
    }

    @Test
    void zeroStartScaleDisablesGrowthAndLargeStartDoesNotShrinkBody() {
        assertEquals(0.62f,
                RVP_ParticleProjectileEmitter.resolveBodyStartScale(0.0f, 0.62f, 4),
                1.0E-6f);
        assertEquals(0.08f,
                RVP_ParticleProjectileEmitter.resolveBodyStartScale(0.08f, 0.62f, 4),
                1.0E-6f);
        assertEquals(0.20f,
                RVP_ParticleProjectileEmitter.resolveBodyStartScale(0.80f, 0.20f, 4),
                1.0E-6f);
        assertEquals(0.62f,
                RVP_ParticleProjectileEmitter.resolveBodyStartScale(0.08f, 0.62f, 1),
                1.0E-6f);
    }
}
