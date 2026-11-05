package org.ywzj.rvp.client.visual;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Parameter.*;

/** 对照改动前的生产公式，验证默认不变、消散边界、连续翻卷和低密度烟量。 */
class RVP_WreckSmokeLogicTest {
    @BeforeEach
    @AfterEach
    void clearSettings() { RVP_WreckSmokeDebugSettings.resetAll(); }

    @Test
    void defaultVelocityMatchesOriginalLongAndNearTrajectoriesForThreeHundredTicks() {
        var defaults = RVP_WreckSmokeDebugSettings.defaults();
        for (boolean near : new boolean[] {false, true}) {
            Vec3 expected = new Vec3(0.07, 0.032, -0.01);
            Vec3 actual = expected;
            Vec3 expectedPosition = new Vec3(0, 0, 0);
            Vec3 actualPosition = expectedPosition;
            for (int age = 1; age <= 300; age++) {
                double rise = Math.max(0, expectedPosition.y);
                double wind = near ? 0.057 : 0.057 * (1 + rise * 0.04);
                double wave = age * 0.06 + 0.37;
                expected = new Vec3(wind + (expected.x - wind) * 0.96 + Math.sin(wave) * 0.0005,
                        near ? expected.y * 0.98 + 0.0015 : 0.25 + (expected.y - 0.25) * 0.98,
                        expected.z * 0.96 + Math.cos(wave) * 0.0005);
                // 调用正式粒子使用的步进函数，逐刻比较位置而非只检查单次输出。
                actual = RVP_WreckSmokeLogic.velocityStep(defaults, near, actual, actualPosition, age, age,
                        0.37, Math.max(0, actualPosition.y));
                expectedPosition = expectedPosition.add(expected);
                actualPosition = actualPosition.add(actual);
                assertEquals(0, actualPosition.distanceTo(expectedPosition), 1.0E-10, "near=" + near + " age=" + age);
            }
        }
    }

    @Test
    void defaultGreyAlphaSizeAndFadeRetainOriginalAppearance() {
        var defaults = RVP_WreckSmokeDebugSettings.defaults();
        for (float random : new float[] {0, 0.3f, 0.99f}) {
            float shade = 0.08f + random * 0.09f;
            float alpha = 0.66f + random * 0.18f;
            assertEquals(shade, RVP_WreckSmokeLogic.grey(shade, 0.125f, 0.125, 1));
            assertEquals(alpha, RVP_WreckSmokeLogic.alpha(alpha, 0.75f, 1, 1));
        }
        for (int age = 0; age < 60; age++) {
            float progress = age / 60.0f;
            float size = RVP_WreckSmokeLogic.nearSizeMultiplier(defaults, progress);
            assertEquals(0.75f + progress * 1.25f, size);
            assertEquals(progress < 0.6f ? 1 : (1 - progress) / 0.4f,
                    RVP_WreckSmokeLogic.alphaMultiplier(defaults, true, age, 60, size), 2.0E-7f);
            assertEquals(Math.min(15, (int) (progress * 16)), RVP_WreckSmokeLogic.nearFrame(progress, 1));
        }
    }

    @Test
    void zeroFormationFadeInAndLateFadeHaveFiniteEndpoints() {
        assertEquals(0, RVP_WreckSmokeLogic.longFrame(0, 0));
        assertEquals(2, RVP_WreckSmokeLogic.longFrame(0, 48));
        assertEquals(0, RVP_WreckSmokeLogic.longFrame(48, 48));
        assertEquals(0, RVP_WreckSmokeLogic.nearFrame(0.9f, 0));
        assertEquals(15, RVP_WreckSmokeLogic.nearFrame(0.9f, 4));
        NEAR_FADE_START.set(1);
        NEAR_FADE_IN_TICKS.set(10);
        var settings = RVP_WreckSmokeDebugSettings.snapshot();
        assertEquals(0, RVP_WreckSmokeLogic.alphaMultiplier(settings, true, 0, 60, 1));
        assertEquals(0.5f, RVP_WreckSmokeLogic.alphaMultiplier(settings, true, 5, 60, 1));
        assertEquals(1, RVP_WreckSmokeLogic.alphaMultiplier(settings, true, 59, 60, 1));
        assertEquals(0, RVP_WreckSmokeLogic.alphaMultiplier(settings, true, 60, 60, 1));
        LONG_EXPANSION_FADE.set(2);
        assertEquals(0.25f, RVP_WreckSmokeLogic.alphaMultiplier(
                RVP_WreckSmokeDebugSettings.LIVE, false, 10, 460, 2));
    }

    @Test
    void signedWindAndResponseAffectOnlySelectedSmokeAndCurlIsContinuous() {
        NEAR_EAST_WIND.set(-0.2);
        NEAR_SOUTH_WIND.set(0.1);
        NEAR_HORIZONTAL_DRAG.set(1);
        NEAR_UPDRAFT.set(0.1);
        NEAR_UPDRAFT_RESPONSE.set(1);
        NEAR_SWAY.set(0);
        var settings = RVP_WreckSmokeDebugSettings.snapshot();
        Vec3 next = RVP_WreckSmokeLogic.velocityStep(settings, true, Vec3.ZERO, Vec3.ZERO, 20, 10, 0, 0);
        assertEquals(-0.2, next.x);
        assertEquals(0.1, next.y);
        assertEquals(0.1, next.z);
        Vec3 unchangedLong = RVP_WreckSmokeLogic.velocityStep(settings, false, Vec3.ZERO, Vec3.ZERO, 20, 10, 0, 0);
        assertTrue(unchangedLong.x > 0);
        NEAR_CURL_STRENGTH.set(0.01);
        var curlSettings = RVP_WreckSmokeDebugSettings.snapshot();
        Vec3 a = RVP_WreckSmokeLogic.velocityStep(curlSettings, true, Vec3.ZERO, new Vec3(0.3, 0.4, 0.5), 20, 10, 0, 0);
        Vec3 b = RVP_WreckSmokeLogic.velocityStep(curlSettings, true, Vec3.ZERO, new Vec3(0.3001, 0.4001, 0.5001), 20.001, 10, 0, 0);
        assertTrue(a.distanceTo(next) > 0.0001);
        assertTrue(a.distanceTo(b) < 0.0001);
    }

    @Test
    void fractionalEmissionKeepsExpectedDensityAndDefaultRandomSequence() {
        RandomSource random = RandomSource.create(67);
        RandomSource reference = RandomSource.create(67);
        assertEquals(4, RVP_WreckSmokeLogic.nearCount(4, 1, random));
        assertEquals(0, RVP_WreckSmokeLogic.nearCount(4, 0, random));
        assertEquals(reference.nextDouble(), random.nextDouble());
        int total = 0;
        for (int i = 0; i < 10000; i++) { total += RVP_WreckSmokeLogic.nearCount(2, 0.125, random); }
        assertTrue(total > 2300 && total < 2700, "小数倍率不应被整数截断：" + total);
    }
}
