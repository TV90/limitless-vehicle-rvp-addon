package org.ywzj.rvp.client.visual.vehicle;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Parameter;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Parameter.*;

/** 验证实际出烟计算的物理趋势、参数传递和极端炮管朝向，不启动Minecraft。 */
class RVP_DelayedMuzzleSmokeLogicTest {
    @BeforeEach
    @AfterEach
    void reset() { RVP_DelayedMuzzleSmokeSettings.resetAll(); }

    @Test
    void defaultEmissionRemainsUniformAndOptionalEnvelopeRisesThenLosesStrength() {
        var original = RVP_DelayedMuzzleSmokeSettings.snapshot();
        assertEquals(1, RVP_DelayedMuzzleSmokeLogic.emissionStrength(original, 0, 20));
        assertEquals(1, RVP_DelayedMuzzleSmokeLogic.emissionStrength(original, 19, 20));
        set(EMIT_ATTACK_TICKS, 4);
        var attack = RVP_DelayedMuzzleSmokeSettings.snapshot();
        assertTrue(RVP_DelayedMuzzleSmokeLogic.emissionStrength(attack, 0, 20)
                < RVP_DelayedMuzzleSmokeLogic.emissionStrength(attack, 3, 20));
        set(EMIT_DECAY_POWER, 1.5);
        set(EMIT_END_STRENGTH, 0.05);
        var declining = RVP_DelayedMuzzleSmokeSettings.snapshot();
        double previous = RVP_DelayedMuzzleSmokeLogic.emissionStrength(declining, 4, 20);
        for (int age = 5; age < 20; age++) {
            double current = RVP_DelayedMuzzleSmokeLogic.emissionStrength(declining, age, 20);
            assertTrue(current <= previous && current >= 0);
            previous = current;
        }
        assertEquals(0.05, previous, 1.0E-12);
    }

    @Test
    void actualSpawnConsumesConfiguredSourceSpeedLifetimeSizeAndInheritance() {
        set(VARIATION, 0);
        set(SPREAD, 0);
        set(SOURCE_LENGTH, 0.1);
        set(SOURCE_RADIUS, 0);
        set(AXIAL_SPEED_MIN, 0.05);
        set(AXIAL_SPEED_MAX, 0.05);
        set(UPDRAFT, 0.02);
        set(VELOCITY_INHERITANCE, 1);
        set(PARTICLE_SIZE, 0.4);
        set(CALIBER_INFLUENCE, 1);
        set(ALPHA, 0.25);
        set(PARTICLE_LIFETIME_MIN, 80);
        set(PARTICLE_LIFETIME_MAX, 80);
        var settings = RVP_DelayedMuzzleSmokeSettings.snapshot();
        Vec3 muzzle = new Vec3(10, 20, 30);
        var spawn = RVP_DelayedMuzzleSmokeLogic.sample(settings, 45, muzzle, new Vec3(1, 0, 0),
                new Vec3(0.02, 0, 0.03), 1, 2, 1, RandomSource.create(5));
        assertEquals(10 + 0.1 * 45 / 125, spawn.position().x, 1.0E-12);
        assertEquals(20, spawn.position().y);
        assertEquals(30, spawn.position().z);
        assertEquals(0.07, spawn.velocity().x, 1.0E-12);
        assertEquals(0.02, spawn.velocity().y, 1.0E-12);
        assertEquals(0.03, spawn.velocity().z, 1.0E-12);
        assertEquals(0.4 * 45 / 125, spawn.size(), 1.0E-7);
        assertEquals(0.25, spawn.alpha());
        assertEquals(80, spawn.lifetime());
        // 调用出生范围为零的正式分支，保证缩短范围后不会继续瞬间填满旧三格烟柱。
        set(SOURCE_LENGTH, 0);
        var nearMuzzle = RVP_DelayedMuzzleSmokeLogic.sample(RVP_DelayedMuzzleSmokeSettings.snapshot(), 125,
                muzzle, new Vec3(1, 0, 0), Vec3.ZERO, 7, 8, 1, RandomSource.create(5));
        assertEquals(muzzle, nearMuzzle.position());
    }

    @Test
    void verticalMuzzlesAndVariableLifetimesProduceFiniteIndependentParticles() {
        var settings = RVP_DelayedMuzzleSmokeSettings.snapshot();
        RandomSource random = RandomSource.create(19);
        int firstLifetime = -1;
        boolean varied = false;
        for (int index = 0; index < 100; index++) {
            Vec3 axis = new Vec3(0, index % 2 == 0 ? 1 : -1, 0);
            var spawn = RVP_DelayedMuzzleSmokeLogic.sample(settings, 125, Vec3.ZERO, axis,
                    Vec3.ZERO, index % 8, 8, 1, random);
            assertTrue(Double.isFinite(spawn.position().lengthSqr()));
            assertTrue(Double.isFinite(spawn.velocity().lengthSqr()));
            assertTrue(spawn.lifetime() >= 40 && spawn.lifetime() <= 60);
            if (firstLifetime < 0) { firstLifetime = spawn.lifetime(); }
            varied |= firstLifetime != spawn.lifetime();
        }
        assertTrue(varied, "同一发的烟团也应逐枚随机寿命");
    }

    @Test
    void fractionalEmissionSurvivesDistanceReductionWithoutExtraCatchupBursts() {
        set(PARTICLES_PER_TICK, 0.25);
        var near = RVP_DelayedMuzzleSmokeSettings.snapshot();
        var counter = new RVP_DelayedMuzzleSmokeLogic.EmissionCounter();
        int count = 0;
        for (int tick = 0; tick < 20; tick++) { count += counter.next(near, 1, 32); }
        assertEquals(5, count);
        set(FAR_DENSITY, 0.2);
        var far = RVP_DelayedMuzzleSmokeSettings.snapshot();
        count = 0;
        for (int tick = 0; tick < 20; tick++) { count += counter.next(far, 1, 256); }
        assertEquals(1, count);
        assertEquals(0, counter.next(far, 0, 256));
    }

    @Test
    void smokeSlowsTowardWindAndDownwardVelocityTurnsUpGradually() {
        set(SPREAD, 0);
        set(HORIZONTAL_DRAG, 0.5);
        set(WIND_X, 0.1);
        set(UPDRAFT, 0.02);
        set(UPDRAFT_RESPONSE, 0.1);
        var settings = RVP_DelayedMuzzleSmokeSettings.snapshot();
        Vec3 velocity = RVP_DelayedMuzzleSmokeLogic.velocityStep(settings,
                new Vec3(1, -0.1, 0.2), Vec3.ZERO, 100, 1, 0);
        assertEquals(0.55, velocity.x, 1.0E-12);
        assertEquals(-0.088, velocity.y, 1.0E-12);
        assertEquals(0.1, velocity.z, 1.0E-12);
        for (int age = 2; age < 200; age++) {
            velocity = RVP_DelayedMuzzleSmokeLogic.velocityStep(settings, velocity, Vec3.ZERO, 100 + age, age, 0);
        }
        assertEquals(0.1, velocity.x, 1.0E-8);
        assertEquals(0.02, velocity.y, 1.0E-8);
        assertEquals(0, velocity.z, 1.0E-8);
    }

    @Test
    void adjacentParticlesShareContinuousCurlRatherThanIndependentRandomKicks() {
        Vec3 position = new Vec3(2.2, 1.7, -3.6);
        Vec3 original = RVP_DelayedMuzzleSmokeLogic.curl(position, 0.7, 2);
        Vec3 nearby = RVP_DelayedMuzzleSmokeLogic.curl(position.add(0.0001, 0, 0), 0.7, 2);
        assertTrue(original.distanceTo(nearby) < 0.001);
        assertTrue(original.distanceTo(RVP_DelayedMuzzleSmokeLogic.curl(position, 1.2, 2)) > 0.01);
        // 用数值微分验证气流散度近零，防止旋涡实现把烟吸成点或无端向外炸散。
        double delta = 1.0E-5;
        double divergence = 0;
        for (int axis = 0; axis < 3; axis++) {
            Vec3 offset = new Vec3(axis == 0 ? delta : 0, axis == 1 ? delta : 0, axis == 2 ? delta : 0);
            Vec3 derivative = RVP_DelayedMuzzleSmokeLogic.curl(position.add(offset), 0.7, 2)
                    .subtract(RVP_DelayedMuzzleSmokeLogic.curl(position.subtract(offset), 0.7, 2)).scale(1 / (2 * delta));
            divergence += axis == 0 ? derivative.x : axis == 1 ? derivative.y : derivative.z;
        }
        assertEquals(0, divergence, 1.0E-7);
        set(SPREAD, 0);
        var still = RVP_DelayedMuzzleSmokeSettings.snapshot();
        set(CURL_STRENGTH, 0.003);
        var swirling = RVP_DelayedMuzzleSmokeSettings.snapshot();
        assertTrue(RVP_DelayedMuzzleSmokeLogic.velocityStep(still, Vec3.ZERO, position, 20, 1, 0)
                .distanceTo(RVP_DelayedMuzzleSmokeLogic.velocityStep(swirling, Vec3.ZERO, position, 20, 1, 0)) > 0.0001);
    }

    @Test
    void expansionThinsSmokeImmediatelyWhileFadeInAndLifetimeFadeRemainIndependent() {
        var original = RVP_DelayedMuzzleSmokeSettings.snapshot();
        assertEquals(1, RVP_DelayedMuzzleSmokeLogic.alphaMultiplier(original, 20, 50));
        assertTrue(RVP_DelayedMuzzleSmokeLogic.alphaMultiplier(original, 40, 50) < 1);
        set(EXPANSION_FADE, 2);
        var thinning = RVP_DelayedMuzzleSmokeSettings.snapshot();
        assertTrue(RVP_DelayedMuzzleSmokeLogic.alphaMultiplier(thinning, 10, 50)
                > RVP_DelayedMuzzleSmokeLogic.alphaMultiplier(thinning, 20, 50));
        set(FADE_IN_TICKS, 4);
        var fadeIn = RVP_DelayedMuzzleSmokeSettings.snapshot();
        assertEquals(0, RVP_DelayedMuzzleSmokeLogic.alphaMultiplier(fadeIn, 0, 50));
        assertTrue(RVP_DelayedMuzzleSmokeLogic.alphaMultiplier(fadeIn, 2, 50) > 0);
        assertEquals(0, RVP_DelayedMuzzleSmokeLogic.alphaMultiplier(fadeIn, 50, 50));
        assertTrue(RVP_DelayedMuzzleSmokeLogic.sizeMultiplier(fadeIn, 0.8)
                > RVP_DelayedMuzzleSmokeLogic.sizeMultiplier(fadeIn, 0.1));
    }

    @Test
    void environmentLightBlendKeepsSkyAndBlockChannelsSeparate() {
        int original = 15 << 20;
        int environment = 15 << 4;
        assertEquals(original, RVP_DelayedMuzzleSmokeLogic.blendLight(original, environment, 0));
        assertEquals(environment, RVP_DelayedMuzzleSmokeLogic.blendLight(original, environment, 1));
        assertEquals(8 << 20 | 8 << 4, RVP_DelayedMuzzleSmokeLogic.blendLight(original, environment, 0.5));
    }

    private static void set(Parameter parameter, double value) {
        // 调用面板实际绑定，测试同样覆盖区间联动与完整快照流程。
        RVP_DelayedMuzzleSmokeSettings.setFromPanel(parameter, value);
    }
}
