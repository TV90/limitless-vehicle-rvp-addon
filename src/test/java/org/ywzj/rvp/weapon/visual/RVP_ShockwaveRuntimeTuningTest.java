package org.ywzj.rvp.weapon.visual;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning.Group.*;
import static org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning.Parameter.*;

/** 调参曲线、互相隔离及寿命边界的回归验证。 */
class RVP_ShockwaveRuntimeTuningTest {
    /** 当前代码中球、环、马赫杆共用的整体速度默认倍率。 */
    private static final float EXPECTED_DEFAULT_SPEED = 1.2F;

    @BeforeEach
    @AfterEach
    void reset() {
        // 调用会话重置，测试之间不共享覆盖值。
        RVP_ShockwaveRuntimeTuning.reset();
    }

    @Test
    void defaultsMatchReferenceSphereAndParticleMotionWithOriginalNormalizedWallShape() {
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(EXPECTED_DEFAULT_SPEED, tuning.get(sphere, speed));
        assertEquals(EXPECTED_DEFAULT_SPEED, tuning.get(ring, speed));
        assertEquals(EXPECTED_DEFAULT_SPEED, tuning.get(mach, speed));
        float sphereDuration = 18 / EXPECTED_DEFAULT_SPEED;
        float groundDuration = 15 / EXPECTED_DEFAULT_SPEED;
        for (float age = 0; age < 20; age += 0.5F) {
            assertEquals(90 * Math.min(1, age / sphereDuration), tuning.sphereRadius(age), 0.00002F);
            float sphereAlpha = Math.max(0, 1 - (age / sphereDuration) * (age / sphereDuration));
            assertEquals(sphereAlpha, tuning.alpha(sphere, age), 0.00001F);
            float groundAlpha = Math.max(0, 1 - (float) Math.pow(age / groundDuration, 3));
            assertEquals(groundAlpha, tuning.alpha(ring, age), 0.00001F);
            assertEquals(tuning.groundRadius(ring, age), tuning.groundRadius(mach, age));
            float originalAge = age * EXPECTED_DEFAULT_SPEED * 130 / 15;
            float originalFade = originalAge >= 130 ? 0 : Math.min(1, originalAge / 4) * (1 - originalAge / 130);
            float height = Math.min(24, 40 * (0.15F + 0.65F * 0.7F))
                    * Math.max(0, Math.min(1, (originalAge - 8) / 12)) * originalFade;
            assertEquals(height, tuning.wallHeight(40, 0.7F, age), 0.00001F);
        }
    }

    @Test
    void overallSpeedCompressesTimingWithoutChangingRangeOrOtherGroups() {
        var original = RVP_ShockwaveRuntimeTuning.snapshot();
        RVP_ShockwaveRuntimeTuning.set(sphere, speed, 11);
        var fast = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(2, fast.duration(sphere, 220));
        for (float age = 0; age < 2; age += 0.25F) {
            float equivalentOriginalAge = age * 11 / EXPECTED_DEFAULT_SPEED;
            assertEquals(original.sphereRadius(equivalentOriginalAge), fast.sphereRadius(age), 0.00001F);
            assertEquals(original.alpha(sphere, equivalentOriginalAge), fast.alpha(sphere, age));
            assertEquals(original.expansion(ring, age), fast.expansion(ring, age));
        }
        assertEquals(0, fast.alpha(sphere, 2));
    }

    @Test
    void expansionOnlySpeedReachesRangeEarlyButRetainsFadeAndLifetime() {
        var original = RVP_ShockwaveRuntimeTuning.snapshot();
        RVP_ShockwaveRuntimeTuning.set(sphere, expansionSpeed, 11);
        var fast = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(90, fast.sphereRadius(2));
        assertEquals(90, fast.sphereRadius(15));
        assertEquals(original.alpha(sphere, 12), fast.alpha(sphere, 12));
        assertEquals(15, fast.duration(sphere, 220));
    }

    @Test
    void extendedLifetimeAndCustomEventDurationDoNotCutFrontsOffEarly() {
        RVP_ShockwaveRuntimeTuning.set(sphere, lifetime, 440);
        RVP_ShockwaveRuntimeTuning.set(sphere, speed, 0.5F);
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(1320, tuning.totalDuration(220));
        assertEquals(2640, tuning.totalDuration(440));
        assertTrue(tuning.alpha(sphere, 500) > 0);
        assertEquals(0, tuning.alpha(sphere, 1320));
        assertEquals(Integer.MAX_VALUE, tuning.totalDuration(Integer.MAX_VALUE));
    }

    @Test
    void snapshotsAndGroupResetDoNotAlterExistingExplosions() {
        var before = RVP_ShockwaveRuntimeTuning.snapshot();
        RVP_ShockwaveRuntimeTuning.set(ring, speed, 5);
        RVP_ShockwaveRuntimeTuning.set(mach, speed, 7);
        var during = RVP_ShockwaveRuntimeTuning.snapshot();
        RVP_ShockwaveRuntimeTuning.reset(ring);
        var after = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(EXPECTED_DEFAULT_SPEED, before.get(ring, speed));
        assertEquals(5, during.get(ring, speed));
        assertEquals(EXPECTED_DEFAULT_SPEED, after.get(ring, speed));
        assertEquals(7, after.get(mach, speed));
    }

    @Test
    void rejectsNonFiniteUnsupportedAndInvertedHeightParameters() {
        for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0, 101}) {
            assertThrows(IllegalArgumentException.class, () -> RVP_ShockwaveRuntimeTuning.set(sphere, speed, value));
        }
        assertThrows(IllegalArgumentException.class, () -> RVP_ShockwaveRuntimeTuning.set(sphere, width, 0.5F));
        assertThrows(IllegalArgumentException.class, () -> RVP_ShockwaveRuntimeTuning.set(mach, minHeight, 25));
        assertThrows(IllegalArgumentException.class, () -> RVP_ShockwaveRuntimeTuning.set(mach, maxHeight, 1));
        assertEquals(2, RVP_ShockwaveRuntimeTuning.snapshot().get(mach, minHeight));
    }

    @Test
    void zeroTransitionsAndLateFadeRemainFiniteAndGeometryStaysBounded() {
        RVP_ShockwaveRuntimeTuning.set(mach, riseTicks, 0);
        RVP_ShockwaveRuntimeTuning.set(mach, riseDelayTicks, 8);
        RVP_ShockwaveRuntimeTuning.set(mach, fadeInTicks, 0);
        RVP_ShockwaveRuntimeTuning.set(mach, fadeStartTicks, 2400);
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(0, tuning.wallHeight(40, 0.7F,
                (8F - 0.01F) / EXPECTED_DEFAULT_SPEED));
        assertTrue(tuning.wallHeight(40, 0.7F,
                (8F + 0.01F) / EXPECTED_DEFAULT_SPEED) > 0);
        assertEquals(1, tuning.alpha(mach, 14 / EXPECTED_DEFAULT_SPEED));
        assertEquals(0, tuning.alpha(mach, (15F + 0.01F) / EXPECTED_DEFAULT_SPEED));
        RVP_ShockwaveRuntimeTuning.set(sphere, radiusScale, 16);
        tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(2048, 1);
        assertEquals(2048, tuning.sphereRadius(220));
    }

    @Test
    void explicitBlockCountAndDampingMatchTickByTickParticleTravel() {
        RVP_ShockwaveRuntimeTuning.set(ring, sampledBlocks, 2000);
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(8, 1);
        float distance = 0;
        float velocity = 1.5F;
        for (int tick = 0; tick <= 15; tick++) {
            assertEquals(distance, tuning.groundRadius(ring,
                    tick / EXPECTED_DEFAULT_SPEED), 0.00001F);
            if (tick < 15) {
                assertEquals(distance + velocity * 0.5F, tuning.groundRadius(ring,
                        (tick + 0.5F) / EXPECTED_DEFAULT_SPEED), 0.00001F);
            }
            distance += velocity;
            velocity *= 0.8F;
        }
        RVP_ShockwaveRuntimeTuning.set(ring, damping, 1);
        assertEquals(22.5F, RVP_ShockwaveRuntimeTuning.snapshot().groundRadius(ring,
                15 / EXPECTED_DEFAULT_SPEED), 0.00001F);
    }

    @Test
    void referenceSphereRadiusAndScaledLifetimeFollowBytecodeInsteadOfStaleComments() {
        for (float radius : new float[] {8, 16, 24, 32, 64}) {
            double expectedRadius = 90 * Math.pow(radius / 24.0, 0.8);
            double expectedTicks = 12 * Math.max(0.45, Math.min(2, expectedRadius / 60));
            var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(radius, 1);
            assertEquals(expectedRadius, tuning.sphereRadius(100), 0.0001);
            assertEquals((int) Math.ceil(expectedTicks / EXPECTED_DEFAULT_SPEED),
                    tuning.duration(sphere, 220));
            float halfAge = (float) expectedTicks / (2 * EXPECTED_DEFAULT_SPEED);
            assertEquals(expectedRadius / 2, tuning.sphereRadius(halfAge), 0.0001);
            assertEquals(0.75F, tuning.alpha(sphere, halfAge), 0.00001F);
        }
    }

    @Test
    void ringTierBoundariesAndHemisphereEstimateAreExplicit() {
        assertEquals(1072, RVP_ShockwaveReference.estimateBlocks(8));
        assertEquals(8579, RVP_ShockwaveReference.estimateBlocks(16));
        assertEquals(0, RVP_ShockwaveReference.ringSpeed(199, 1.5F));
        assertEquals(0.69F, RVP_ShockwaveReference.ringSpeed(200, 1.5F), 0.00001F);
        assertEquals(2.8488F, RVP_ShockwaveReference.ringSpeed(1999, 1.5F), 0.00001F);
        assertEquals(1.5F, RVP_ShockwaveReference.ringSpeed(2000, 1.5F));
        assertEquals(8.5575F, RVP_ShockwaveReference.ringSpeed(18000, 1.5F), 0.00001F);
    }

    @Test
    void pureFrontsReleaseBudgetEarlyWhileCloudsKeepTheirTimeline() {
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(16, 1);
        assertEquals(tuning.duration(sphere, 220), tuning.frontDuration(220));
        assertEquals(220, tuning.totalDuration(220));
        RVP_ShockwaveRuntimeTuning.set(sphere, expansionPower, 2);
        tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1);
        float halfAge = 9 / EXPECTED_DEFAULT_SPEED;
        assertEquals(67.5F, tuning.sphereRadius(halfAge), 0.0001F);
        assertEquals(0.4375F, tuning.alpha(sphere, halfAge), 0.00001F);
    }

    @Test
    void disabledGroundComponentsCannotKeepInvisibleEffectsAlive() {
        // 调用参数入口模拟玩家给已关闭组件设置极慢动画，仍须按球体寿命释放预算。
        RVP_ShockwaveRuntimeTuning.set(ring, lifetime, 2400);
        RVP_ShockwaveRuntimeTuning.set(mach, speed, 0.05F);
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(8, 1);
        assertTrue(RVP_ShockwaveRuntimeTuning.enabled(sphere));
        assertFalse(RVP_ShockwaveRuntimeTuning.enabled(ring));
        assertFalse(RVP_ShockwaveRuntimeTuning.enabled(mach));
        assertEquals(tuning.duration(sphere, 220), tuning.frontDuration(220));
        assertEquals(220, tuning.totalDuration(220));
    }

    @Test
    void referenceAppearanceDefaultsAndChangesPreserveSnapshotIsolation() {
        // 调用当前外观参数，核对雾强度、厚度、折射与色散默认值。
        var original = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(0.2F, original.get(sphere, brightness));
        assertEquals(1, original.get(sphere, thickness));
        assertEquals(1, original.get(sphere, refraction));
        assertEquals(0.06F, original.get(sphere, dispersion));
        RVP_ShockwaveRuntimeTuning.set(sphere, speed, 2);
        RVP_ShockwaveRuntimeTuning.set(sphere, brightness, 0.4F);
        RVP_ShockwaveRuntimeTuning.set(sphere, thickness, 0.2F);
        RVP_ShockwaveRuntimeTuning.set(sphere, refraction, 0);
        var adjusted = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(original.sphereRadius(9),
                adjusted.sphereRadius(9 * EXPECTED_DEFAULT_SPEED / 2));
        assertEquals(0.2F, original.get(sphere, brightness));
        assertEquals(1, original.get(sphere, thickness));
        assertEquals(1, original.get(sphere, refraction));
        assertEquals(0.4F, adjusted.get(sphere, brightness));
        assertEquals(0.2F, adjusted.get(sphere, thickness));
        assertEquals(0, adjusted.get(sphere, refraction));
    }

    @Test
    void volumeThicknessCompressesOnTheSameScaledLifetimeAndCanReachZero() {
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1);
        assertEquals(0.6F, tuning.sphereThickness(0), 0.000001F);
        assertEquals(0.6F, tuning.sphereThickness(9 / EXPECTED_DEFAULT_SPEED), 0.000001F);
        assertEquals(0.4375F, tuning.sphereThickness(13.5F / EXPECTED_DEFAULT_SPEED), 0.000001F);
        assertEquals(0.25F, tuning.sphereThickness(18 / EXPECTED_DEFAULT_SPEED), 0.000001F);
        RVP_ShockwaveRuntimeTuning.set(sphere, speed, 2);
        var fast = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1);
        assertEquals(tuning.sphereThickness(13.5F / EXPECTED_DEFAULT_SPEED),
                fast.sphereThickness(6.75F), 0.000001F);
        RVP_ShockwaveRuntimeTuning.set(sphere, finalThicknessRatio, 0);
        assertEquals(0, RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1).sphereThickness(9));
    }

    @Test
    void compressionControlsChangeThicknessWithoutChangingExpansionOrFade() {
        var original = RVP_ShockwaveRuntimeTuning.snapshot();
        RVP_ShockwaveRuntimeTuning.set(sphere, thickness, 0.4F);
        RVP_ShockwaveRuntimeTuning.set(sphere, initialThicknessRatio, 1.5F);
        RVP_ShockwaveRuntimeTuning.set(sphere, finalThicknessRatio, 0.5F);
        RVP_ShockwaveRuntimeTuning.set(sphere, compressionPower, 2);
        var adjusted = RVP_ShockwaveRuntimeTuning.snapshot();
        float halfAge = 9 / EXPECTED_DEFAULT_SPEED;
        assertEquals(0.5F, adjusted.sphereThickness(halfAge), 0.000001F);
        assertEquals(original.sphereRadius(halfAge), adjusted.sphereRadius(halfAge));
        assertEquals(original.alpha(sphere, halfAge), adjusted.alpha(sphere, halfAge));
    }

    @Test
    void pressureStartsSmoothlyThenReleasesForceWithoutExtendingLifetime() {
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(0, tuning.pressureRefraction(-1));
        assertEquals(0, tuning.pressureRefraction(0));
        assertTrue(tuning.pressureRefraction(0.18F / EXPECTED_DEFAULT_SPEED)
                < tuning.pressureRefraction(1.44F / EXPECTED_DEFAULT_SPEED));
        assertTrue(tuning.pressureRefraction(2.25F / EXPECTED_DEFAULT_SPEED)
                > tuning.pressureRefraction(9 / EXPECTED_DEFAULT_SPEED));
        assertTrue(tuning.pressureRefraction(9 / EXPECTED_DEFAULT_SPEED)
                > tuning.pressureRefraction(15.75F / EXPECTED_DEFAULT_SPEED));
        assertEquals(0, tuning.pressureRefraction(18 / EXPECTED_DEFAULT_SPEED));
        assertEquals(0, tuning.pressureRefraction(40));
        assertEquals(15, tuning.duration(sphere, 220));
    }

    @Test
    void pressureRhythmFollowsExplosionSizeAndOverallSpeed() {
        var standard = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1);
        var small = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(8, 1);
        float smallLife = 12 * RVP_ShockwaveReference.sphereTimeScale(
                RVP_ShockwaveReference.sphereRadius(8, 1.5F));
        float standardHalfAge = 9 / EXPECTED_DEFAULT_SPEED;
        assertEquals(standard.pressureRefraction(standardHalfAge),
                small.pressureRefraction(smallLife / (2 * EXPECTED_DEFAULT_SPEED)), 0.00001F);
        RVP_ShockwaveRuntimeTuning.set(sphere, speed, 2);
        var fast = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1);
        assertEquals(standard.pressureRefraction(standardHalfAge), fast.pressureRefraction(4.5F));
        assertEquals(standard.pressureThickness(standardHalfAge), fast.pressureThickness(4.5F));
    }

    @Test
    void pressureControlAndZeroRefractionPreserveBodyAppearanceAndSnapshots() {
        var original = RVP_ShockwaveRuntimeTuning.snapshot();
        RVP_ShockwaveRuntimeTuning.set(sphere, pressureStrength, 2);
        var stronger = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(original.pressureRefraction(9) * 2, stronger.pressureRefraction(9));
        assertEquals(original.sphereRadius(9), stronger.sphereRadius(9));
        assertEquals(original.sphereThickness(9), stronger.sphereThickness(9));
        assertEquals(original.alpha(sphere, 9), stronger.alpha(sphere, 9));
        assertEquals(original.get(sphere, brightness), stronger.get(sphere, brightness));
        RVP_ShockwaveRuntimeTuning.set(sphere, pressureStrength, 0);
        assertEquals(0, RVP_ShockwaveRuntimeTuning.snapshot().pressureRefraction(9));
        assertTrue(original.pressureRefraction(9) > 0);
        RVP_ShockwaveRuntimeTuning.set(sphere, pressureStrength, 1);
        RVP_ShockwaveRuntimeTuning.set(sphere, refraction, 0);
        assertEquals(0, RVP_ShockwaveRuntimeTuning.snapshot().pressureRefraction(9));
    }

    @Test
    void pressureRemainsInsideBodyIncludingVeryThinAndZeroThickness() {
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot();
        for (float age = 0; age <= 18; age += 0.25F) {
            assertTrue(tuning.pressureThickness(age) > 0);
            assertTrue(tuning.pressureThickness(age) < tuning.sphereThickness(age));
        }
        RVP_ShockwaveRuntimeTuning.set(sphere, thickness, 0.02F);
        tuning = RVP_ShockwaveRuntimeTuning.snapshot();
        assertEquals(tuning.sphereThickness(18), tuning.pressureThickness(18));
        RVP_ShockwaveRuntimeTuning.set(sphere, finalThicknessRatio, 0);
        assertEquals(0, RVP_ShockwaveRuntimeTuning.snapshot().pressureThickness(18));
    }
}
