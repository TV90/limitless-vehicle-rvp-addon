package org.ywzj.rvp.client.visual.cookoff;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.particle.RVP_WreckSparkParticle;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffDynamics.SparkKind;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings.Parameter.*;

/** 验证默认不变、冷却单调性、喷发连续性、侧翻上浮、拖影投影与命中火花隔离。 */
class RVP_WreckCookoffDynamicsTest {
    /** 向量和连续曲线的数值误差。 */
    private static final double EPSILON = 1.0E-8;

    @BeforeEach @AfterEach
    void resetSettings() { RVP_WreckCookoffSettings.reset(); }

    @Test
    void defaultsKeepSteadyEmissionOriginalColorAndLocalUpwardAcceleration() {
        var settings = RVP_WreckCookoffSettings.snapshot();
        Vec3 original = new Vec3(1, 0.4, 0.1);
        assertEquals(1, RVP_WreckCookoffDynamics.pressure(123456, 2, settings));
        assertEquals(original, RVP_WreckCookoffDynamics.flameColor(0.9, 0.8, original, settings));
        assertEquals(new Vec3(1, 0.9, 0.6), RVP_WreckCookoffDynamics.sparkColor(0.9, settings));
        assertEquals(new Vec3(0.0025, 0, 0), RVP_WreckCookoffDynamics.flameAcceleration(
                Vec3.ZERO, new Vec3(1, 0, 0), 1, 200, settings));
        assertEquals(0.5, RVP_WreckCookoffDynamics.flameFade(0.79, 0.9, settings), EPSILON);
    }

    @Test
    void pulsesStayContinuousAndBoundedWhileOutletsDoNotFlashTogether() {
        RVP_WreckCookoffSettings.set(PULSE_STRENGTH, 0.7);
        var settings = RVP_WreckCookoffSettings.snapshot();
        double min = 1, max = 0;
        boolean differed = false;
        double previous = RVP_WreckCookoffDynamics.pressure(0, 0, settings);
        for (int tick = 1; tick < 2000; tick++) {
            double current = RVP_WreckCookoffDynamics.pressure(tick, 0, settings);
            assertTrue(current >= 0.3 - EPSILON && current <= 1 + EPSILON);
            assertTrue(Math.abs(current - previous) < 0.25, "连续起伏不应逐刻随机闪烁");
            min = Math.min(min, current);
            max = Math.max(max, current);
            differed |= Math.abs(current - RVP_WreckCookoffDynamics.pressure(tick, 2, settings)) > 0.2;
            previous = current;
        }
        assertTrue(min < 0.32 && max > 0.98 && differed);
    }

    @Test
    void naturalTailFadesOutwardAndColorReachesConfiguredStops() {
        RVP_WreckCookoffSettings.set(NATURAL_TAIL, 1);
        RVP_WreckCookoffSettings.set(FLAME_GRADIENT, 1);
        var settings = RVP_WreckCookoffSettings.snapshot();
        Vec3 original = new Vec3(1, 0.4, 0.1);
        assertEquals(new Vec3(1, 0.85, 0.3), RVP_WreckCookoffDynamics.flameColor(0, 0, original, settings));
        assertEquals(new Vec3(1, 0.5, 0.065), RVP_WreckCookoffDynamics.flameColor(0.5, 0, original, settings));
        assertEquals(new Vec3(1, 0.17, 0.01).y,
                RVP_WreckCookoffDynamics.flameColor(1, 0, original, settings).y, EPSILON);
        double near = RVP_WreckCookoffDynamics.flameFade(0.1, 0.7, settings);
        double far = RVP_WreckCookoffDynamics.flameFade(0.1, 0.9, settings);
        assertTrue(near > far && far > 0);
        assertEquals(0, RVP_WreckCookoffDynamics.flameFade(0.1, 1, settings));
        assertEquals(0, RVP_WreckCookoffDynamics.fade(1, 1));
    }

    @Test
    void coolingLosesBrightnessMonotonicallyAndTheOldSnapshotIsStable() {
        RVP_WreckCookoffSettings.set(SPARK_COOLING, 1);
        var particle = RVP_WreckCookoffSettings.snapshot();
        RVP_WreckCookoffSettings.set(SPARK_COOLING, 0);
        double previous = Double.POSITIVE_INFINITY;
        for (int age = 0; age <= 100; age++) {
            Vec3 color = RVP_WreckCookoffDynamics.sparkColor(age / 100.0, particle);
            assertTrue(color.x <= previous + EPSILON);
            assertTrue(color.x >= 0 && color.y >= 0 && color.z >= 0);
            previous = color.x;
        }
        Vec3 cold = RVP_WreckCookoffDynamics.sparkColor(1, particle);
        assertEquals(0.15, cold.x, EPSILON);
        assertEquals(0.012, cold.y, EPSILON);
        assertEquals(1, RVP_WreckCookoffDynamics.sparkColor(1, RVP_WreckCookoffSettings.snapshot()).x);
    }

    @Test
    void sidewaysOutletKeepsDirectionAtRootAndTurnsUpwardAtTip() {
        RVP_WreckCookoffSettings.set(WORLD_UP_MIX, 1);
        RVP_WreckCookoffSettings.set(WIND_X, 0.1);
        var settings = RVP_WreckCookoffSettings.snapshot();
        Vec3 axis = new Vec3(0, 0, 1);
        Vec3 root = RVP_WreckCookoffDynamics.flameAcceleration(Vec3.ZERO, axis, 0, 0, settings);
        Vec3 tip = RVP_WreckCookoffDynamics.flameAcceleration(Vec3.ZERO, axis, 1, 0, settings);
        assertEquals(0.0025, root.z, EPSILON);
        assertEquals(0, root.y, EPSILON);
        assertEquals(0.0025, tip.y, EPSILON);
        assertEquals(0, tip.z, EPSILON);
        assertTrue(root.x > 0 && tip.x > 0);
    }

    @Test
    void sparkWeightsGiveExpectedPopulationAndZeroWeightsHaveDefinedFallback() {
        RVP_WreckCookoffSettings.set(SMALL_WEIGHT, 70);
        RVP_WreckCookoffSettings.set(MEDIUM_WEIGHT, 25);
        RVP_WreckCookoffSettings.set(HEAVY_WEIGHT, 5);
        var settings = RVP_WreckCookoffSettings.snapshot();
        int small = 0, medium = 0, heavy = 0;
        for (int index = 0; index < 1000; index++) {
            switch (RVP_WreckCookoffDynamics.sparkKind((index + 0.5) / 1000, settings)) {
                case SMALL -> small++;
                case MEDIUM -> medium++;
                case HEAVY -> heavy++;
            }
        }
        assertEquals(700, small);
        assertEquals(250, medium);
        assertEquals(50, heavy);
        assertTrue(RVP_WreckCookoffDynamics.sparkLifetime(0.5, SparkKind.SMALL, settings)
                < RVP_WreckCookoffDynamics.sparkLifetime(0.5, SparkKind.HEAVY, settings));
        RVP_WreckCookoffSettings.set(SMALL_WEIGHT, 0);
        RVP_WreckCookoffSettings.set(MEDIUM_WEIGHT, 0);
        RVP_WreckCookoffSettings.set(HEAVY_WEIGHT, 0);
        assertEquals(SparkKind.MEDIUM, RVP_WreckCookoffDynamics.sparkKind(0.5, RVP_WreckCookoffSettings.snapshot()));
    }

    @Test
    void trailProjectsVelocityAndClampsLengthWithoutStretchingTowardTheCamera() {
        RVP_WreckCookoffSettings.set(SPARK_TRAIL_TIME, 0.1);
        RVP_WreckCookoffSettings.set(SPARK_TRAIL_MAX, 0.5);
        var settings = RVP_WreckCookoffSettings.snapshot();
        assertEquals(Vec3.ZERO, RVP_WreckCookoffDynamics.projectedSparkTrail(new Vec3(0, 0, 2), new Quaternionf(), settings));
        Vec3 trail = RVP_WreckCookoffDynamics.projectedSparkTrail(new Vec3(1, 0, 0), new Quaternionf(), settings);
        assertEquals(0.5, trail.length(), EPSILON);
        Quaternionf camera = new Quaternionf().rotateY((float) (Math.PI / 2));
        Vec3 towardsCamera = RVP_WreckCookoffDynamics.projectedSparkTrail(new Vec3(1, 0, 0), camera, settings);
        assertTrue(towardsCamera.length() < 1.0E-6);
    }

    @Test
    void tunedContactChangesCookoffButLeavesSharedImpactEntryUntouched() {
        RVP_WreckCookoffSettings.set(SPARK_RESTITUTION, 0.8);
        RVP_WreckCookoffSettings.set(SPARK_GROUND_FRICTION, 0.9);
        var tuned = RVP_WreckSparkParticle.respondToContact(0.5, -0.4, 0.2, 0, true, RVP_WreckCookoffSettings.snapshot());
        var impact = RVP_WreckSparkParticle.respondToContact(0.5, -0.4, 0.2, 0, true);
        assertEquals(0.32, tuned.yd(), EPSILON);
        assertEquals(0.05, tuned.xd(), EPSILON);
        assertEquals(0.14, impact.yd(), EPSILON);
        assertEquals(0.3, impact.xd(), EPSILON);
    }
}
