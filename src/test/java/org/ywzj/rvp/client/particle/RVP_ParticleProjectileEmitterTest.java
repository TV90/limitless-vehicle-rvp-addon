package org.ywzj.rvp.client.particle;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void trailHotPhaseUsesSmoothFourTickVisualAgeAndCanBeDisabled() {
        assertEquals(0.0f,
                RVP_WhitePhosphorusParticle.resolveTrailHotBlendWeight(0, 0),
                1.0E-6f);
        assertEquals(1.0f,
                RVP_WhitePhosphorusParticle.resolveTrailHotBlendWeight(0, 4),
                1.0E-6f);
        assertEquals(0.5f,
                RVP_WhitePhosphorusParticle.resolveTrailHotBlendWeight(2, 4),
                1.0E-6f);
        assertEquals(0.0f,
                RVP_WhitePhosphorusParticle.resolveTrailHotBlendWeight(4, 4),
                1.0E-6f);
        assertEquals(0.0f,
                RVP_WhitePhosphorusParticle.resolveTrailHotBlendWeight(40, 4),
                1.0E-6f);
    }

    @Test
    void initialTrailDensityStopsAfterTenSuccessfulTicks() {
        int successfulTrailTicks = 0;
        for (int tick = 0; tick < 10; tick++) {
            int extraCount = RVP_ParticleProjectileEmitter.resolveInitialExtraCount(
                    4, 10, 0.8D, successfulTrailTicks);
            assertEquals(4, extraCount);
            assertEquals(5, 1 + extraCount);
            successfulTrailTicks = RVP_ParticleProjectileEmitter.nextSuccessfulTrailTicks(
                    successfulTrailTicks, true);
        }

        assertEquals(0, RVP_ParticleProjectileEmitter.resolveInitialExtraCount(
                4, 10, 0.8D, successfulTrailTicks));
        assertEquals(1, 1 + RVP_ParticleProjectileEmitter.resolveInitialExtraCount(
                4, 10, 0.8D, successfulTrailTicks));
    }

    @Test
    void missingMovementOrInterruptedTrackingDoesNotConsumeInitialTicks() {
        int successfulTrailTicks = 3;
        successfulTrailTicks = RVP_ParticleProjectileEmitter.nextSuccessfulTrailTicks(
                successfulTrailTicks, false);
        successfulTrailTicks = RVP_ParticleProjectileEmitter.nextSuccessfulTrailTicks(
                successfulTrailTicks, false);

        assertEquals(3, successfulTrailTicks);
        assertEquals(4, RVP_ParticleProjectileEmitter.resolveInitialExtraCount(
                4, 10, 0.8D, successfulTrailTicks));
    }

    @Test
    void zeroSpreadReturnsCenterWithoutConsumingRandomNumbers() {
        AtomicInteger randomCalls = new AtomicInteger();
        assertEquals(0, RVP_ParticleProjectileEmitter.resolveInitialExtraCount(
                0, 10, 0.8D, 0));
        assertEquals(0, RVP_ParticleProjectileEmitter.resolveInitialExtraCount(
                4, 0, 0.8D, 0));
        assertEquals(0, RVP_ParticleProjectileEmitter.resolveInitialExtraCount(
                4, 10, 0.0D, 0));
        Vec3 offset = RVP_ParticleProjectileEmitter.sampleUniformSphereOffset(0.0D, () -> {
            randomCalls.incrementAndGet();
            return 0.5D;
        });

        assertEquals(Vec3.ZERO, offset);
        assertEquals(0, randomCalls.get());
    }

    @Test
    void positiveSpreadSamplesUniformSphereVolumeAroundCenter() {
        double spread = 0.8D;
        int sampleCount = 50_000;
        Random random = new Random(0x525650L);
        Vec3 sum = Vec3.ZERO;
        double radiusSum = 0.0D;

        for (int index = 0; index < sampleCount; index++) {
            Vec3 offset = RVP_ParticleProjectileEmitter.sampleUniformSphereOffset(
                    spread, random::nextDouble);
            assertTrue(offset.lengthSqr() <= spread * spread + 1.0E-12D);
            sum = sum.add(offset);
            radiusSum += offset.length();
        }

        Vec3 mean = sum.scale(1.0D / sampleCount);
        assertEquals(0.0D, mean.x, 0.01D);
        assertEquals(0.0D, mean.y, 0.01D);
        assertEquals(0.0D, mean.z, 0.01D);
        assertEquals(spread * 0.75D, radiusSum / sampleCount, 0.01D);
    }
}
