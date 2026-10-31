package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Group;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Parameter;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 连射、迟包、换世界、持续光失联和预算的行为回归。 */
class RVP_ScreenLightManagerTest {
    /** 每个测试独立的管理器，不改实际客户端单例。 */
    private final RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
    /** 用对象身份模拟同维度重连后的不同世界。 */
    private final Object world = new Object();

    @BeforeEach
    void start() {
        // 调用会话重置，每个时间线测试都从正式默认值开始，避免互相污染衰减倍率。
        RVP_LightRuntimeTuning.reset();
        manager.beginWorld(world, 100);
    }

    @AfterEach
    void resetTuning() {
        // 调用统一重置，慢衰减测试不会影响其他测试类。
        RVP_LightRuntimeTuning.reset();
    }

    /** 只验证照亮寿命时，把光晕改为跟随照亮、关闭天空，避免它们独立延长整盏光源。 */
    private void isolateLightingLifetime() {
        // 调用统一调参入口：原曲线让光晕与照亮同寿命，天空峰值0表示不写天空。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloDecayCurve, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyPeakBrightness, 0);
    }

    /** 读取无视锥裁剪的实际快照，用于检查生命周期，预算仍正常执行。 */
    private List<RVP_ScreenLight> sample(float partialTick) {
        return manager.collectVisible(Vec3.ZERO, partialTick, 256, 8, light -> true);
    }

    @Test
    void repeatedMuzzleFireRenewsOneLightWithoutAddingBrightness() {
        RVP_LightParameters parameters = RVP_LightParameters.muzzle(7.62F, false);
        for (int i = 0; i < 100; i++) {
            manager.addTransient(new Vec3(1, 0, 0), RVP_LightSourceType.MUZZLE, "same muzzle", parameters, 100);
        }
        assertEquals(1, manager.size());
        assertEquals(parameters.intensity() * 4, sample(0).get(0).intensity());
        manager.tick(world, 101);
        manager.addTransient(new Vec3(1.5, 0, 0), RVP_LightSourceType.MUZZLE, "same muzzle", parameters, 101);
        assertEquals(1, manager.size());
        assertEquals(new Vec3(1.5, 0, 0), sample(0).get(0).position());
    }

    @Test
    void renewingMergedExplosionInvalidatesOldCancellationHandle() {
        RVP_LightParameters parameters = RVP_LightParameters.explosion(4);
        long old = manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 100);
        manager.tick(world, 101);
        long renewed = manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 101);
        assertNotEquals(old, renewed);
        manager.removeTransient(old);
        assertEquals(1, manager.size());
        manager.removeTransient(renewed);
        assertEquals(0, manager.size());
    }

    @Test
    void differentMuzzlesAndDifferentSourceTypesRemainIndependent() {
        RVP_LightParameters parameters = RVP_LightParameters.muzzle(20, false);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.MUZZLE, "left", parameters, 100);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.MUZZLE, "right", parameters, 100);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, "left", parameters, 100);
        assertEquals(3, manager.size());
    }

    @Test
    void nearbyAnonymousExplosionsMergeButDistantOnesDoNot() {
        RVP_LightParameters parameters = RVP_LightParameters.explosion(4);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 100);
        manager.addTransient(new Vec3(0.5, 0, 0), RVP_LightSourceType.EXPLOSION, null, parameters, 100);
        manager.addTransient(new Vec3(12, 0, 0), RVP_LightSourceType.EXPLOSION, null, parameters, 100);
        assertEquals(2, manager.size());
    }

    @Test
    void explosionFadesAndWarmsWithoutAdvancingAtRenderRate() {
        // 本项只验证照亮淡出与帧率无关：光晕跟随照亮寿命、天空关闭。
        isolateLightingLifetime();
        manager.addTransient(new Vec3(1, 0, 0), RVP_LightSourceType.EXPLOSION, null,
                RVP_LightParameters.explosion(4), 100);
        RVP_ScreenLight first = sample(0).get(0);
        manager.tick(world, 104);
        RVP_ScreenLight middle = sample(0.5F).get(0);
        assertTrue(middle.intensity() < first.intensity());
        assertTrue(middle.green() < first.green());
        assertTrue(middle.blue() < first.blue());
        for (int i = 0; i < 240; i++) { assertEquals(middle, sample(0.5F).get(0)); }
        manager.tick(world, 132);
        assertFalse(manager.hasActiveLights());
    }

    @Test
    void delayedEventsCatchUpAndAlreadyExpiredEventsAreRejected() {
        // 本项只验证照亮寿命与迟包判定：关闭独立光晕曲线与天空。
        isolateLightingLifetime();
        RVP_LightParameters parameters = RVP_LightParameters.explosion(4);
        assertEquals(0, manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 60));
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 96);
        assertTrue(sample(0).get(0).intensity() < parameters.intensity() * 20);
        assertEquals(28, sample(0).get(0).remainingTicks());
    }

    @Test
    void slightlyLeadingServerClockStartsAtAgeZeroInsteadOfDroppingFlash() {
        // 本项只验证照亮寿命：关闭独立光晕曲线与天空。
        isolateLightingLifetime();
        RVP_LightParameters parameters = RVP_LightParameters.explosion(4);
        assertNotEquals(0, manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 105));
        assertEquals(parameters.durationTicks(), sample(0).get(0).remainingTicks());
        manager.tick(world, 132);
        assertTrue(sample(0).isEmpty());
    }

    @Test
    void explosionLivesExactlyThirtyTwoTicksWithPointThreeDecayAndRenderRateCannotExtendIt() {
        // 本项只验证照亮寿命：关闭独立光晕曲线与天空。
        isolateLightingLifetime();
        // 调用真实爆炸预设与管理器，覆盖1.6秒中点、最后半刻和精确过期边界。
        RVP_LightParameters parameters = RVP_LightParameters.explosion(4);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 100);
        manager.tick(world, 116);
        RVP_ScreenLight middle = sample(0).get(0);
        assertEquals(parameters.intensity() * 5 * Math.pow(.5, .3), middle.intensity(), .00001);
        assertEquals(16, middle.remainingTicks());
        manager.tick(world, 131);
        RVP_ScreenLight last = sample(.5F).get(0);
        assertTrue(last.intensity() > 0);
        assertEquals(.5F, last.remainingTicks());
        for (int i = 0; i < 240; i++) { assertEquals(last, sample(.5F).get(0)); }
        manager.tick(world, 132);
        assertTrue(sample(0).isEmpty());
    }

    @Test
    void decayScaleStretchesAndShortensTheWholeExplosionAndMuzzleLight() {
        for (RVP_LightSourceType type : new RVP_LightSourceType[] {RVP_LightSourceType.EXPLOSION, RVP_LightSourceType.MUZZLE}) {
            // 调用两类真实预设和管理器，检查慢衰减下照亮、光晕都超过原始寿命后仍有效。
            RVP_LightRuntimeTuning.reset();
            Group group = type == RVP_LightSourceType.EXPLOSION ? Group.explosion : Group.muzzle;
            // 调用原曲线设置，本项专门验证原曲线下照亮与光晕共同伸缩；新独立时长另有回归。
            RVP_LightRuntimeTuning.set(group, Parameter.haloDecayCurve, 0);
            // 天空有独立时间线（默认3+29刻）会把整盏光延长到 32 刻，本项只验证照亮/光晕寿命，先关掉天空。
            if (Parameter.skyPeakBrightness.supports(group)) {
                RVP_LightRuntimeTuning.set(group, Parameter.skyPeakBrightness, 0);
            }
            RVP_LightParameters parameters = type == RVP_LightSourceType.EXPLOSION
                    ? RVP_LightParameters.explosion(4) : RVP_LightParameters.muzzle(125, false);
            RVP_ScreenLightManager lightManager = new RVP_ScreenLightManager();
            lightManager.beginWorld(world, 100);
            RVP_LightRuntimeTuning.set(group, Parameter.decayScale, .5F);
            long handle = lightManager.addTransient(Vec3.ZERO, type, null, parameters, 100);
            lightManager.tick(world, 100 + parameters.durationTicks() + 1);
            var extended = lightManager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
            assertTrue(extended.intensity() > 0);
            assertTrue(extended.haloIntensity() > 0);
            assertEquals(parameters.durationTicks() - 1, extended.remainingTicks());
            assertTrue(lightManager.hasTransient(handle));
            lightManager.tick(world, 100 + parameters.durationTicks() * 2);
            assertFalse(lightManager.hasTransient(handle));
            assertFalse(lightManager.hasActiveLights());

            // 调用快衰减路径，整盏光源在半寿命处结束，效果包装器查询也同步变为false。
            lightManager.beginWorld(world, 200);
            RVP_LightRuntimeTuning.set(group, Parameter.decayScale, 2);
            long shortened = lightManager.addTransient(Vec3.ZERO, type, null, parameters, 200);
            lightManager.tick(world, 200 + (parameters.durationTicks() + 1) / 2);
            assertFalse(lightManager.hasTransient(shortened));
            assertTrue(lightManager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).isEmpty());
        }
    }

    @Test
    void liveDecayChangesKeepBirthTimeAndDelayedSourcesUseTheAdjustedLifetime() {
        // 调用原曲线设置，保留本项对原有同步结束边界的回归覆盖。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloDecayCurve, 0);
        // 天空自有时间线会独立延长整盏光，本项只验证照亮/光晕寿命，先关掉天空。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyPeakBrightness, 0);
        // 调用实时调参与迟包登记，不重置出生年龄；恢复参数不能让已结束的条目复活。
        RVP_LightParameters parameters = RVP_LightParameters.explosion(4);
        long handle = manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 100);
        manager.tick(world, 108);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.decayScale, .5F);
        var slowed = sample(0).get(0);
        assertEquals(56, slowed.remainingTicks());
        assertTrue(slowed.intensity() > 0 && slowed.intensity() < 25);
        assertTrue(slowed.haloIntensity() > 0 && slowed.haloIntensity() < 16);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.decayScale, 2);
        assertEquals(8, sample(0).get(0).remainingTicks());
        manager.tick(world, 116);
        assertFalse(manager.hasTransient(handle));
        RVP_LightRuntimeTuning.reset(Group.explosion, Parameter.decayScale);
        assertFalse(manager.hasActiveLights());

        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.decayScale, .5F);
        long late = manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 76);
        assertNotEquals(0, late);
        assertEquals(24, sample(0).get(0).remainingTicks());
        manager.removeTransient(late);
        assertFalse(manager.hasTransient(late));
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.decayScale, 2);
        assertEquals(0, manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 76));
    }

    @Test
    void zeroDecayScaleHasAFiniteSlowFadeAndEventuallyExpires() {
        // 调用0倍率下的有限下限规则，防止调参生成永不结束的光源。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.decayScale, 0);
        long handle = manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null,
                RVP_LightParameters.explosion(4), 100);
        manager.tick(world, 739);
        assertTrue(manager.hasTransient(handle));
        manager.tick(world, 740);
        assertFalse(manager.hasTransient(handle));
        assertFalse(manager.hasActiveLights());
    }

    @Test
    void persistentLightTracksOneUUIDAndExpiresWhenHeartbeatStops() {
        UUID key = UUID.randomUUID();
        manager.upsertPersistent(key, new Vec3(1, 0, 0), RVP_LightParameters.engine());
        manager.tick(world, 101);
        manager.upsertPersistent(key, new Vec3(2, 0, 0), RVP_LightParameters.engine());
        assertEquals(1, manager.size());
        assertEquals(new Vec3(2, 0, 0), sample(0).get(0).position());
        assertEquals(key, sample(0).get(0).persistentKey());
        manager.tick(world, 103);
        assertFalse(manager.hasActiveLights());
    }

    @Test
    void explicitEngineRemovalIsImmediate() {
        UUID key = UUID.randomUUID();
        manager.upsertPersistent(key, Vec3.ZERO, RVP_LightParameters.engine());
        manager.removePersistent(key);
        assertTrue(sample(0).isEmpty());
    }

    @Test
    void worldIdentityAndClockRollbackClearBothKindsOfLight() {
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 100);
        manager.upsertPersistent(UUID.randomUUID(), Vec3.ZERO, RVP_LightParameters.engine());
        manager.beginWorld(new Object(), 100);
        assertEquals(0, manager.size());
        manager.upsertPersistent(UUID.randomUUID(), Vec3.ZERO, RVP_LightParameters.engine());
        manager.beginWorld(world, 99);
        assertEquals(0, manager.size());
        manager.upsertPersistent(UUID.randomUUID(), Vec3.ZERO, RVP_LightParameters.engine());
        manager.tick(world, 98);
        assertEquals(0, manager.size());
    }

    @Test
    void burstFloodRemainsBoundedAndGPUReceivesAtMostEight() {
        for (int i = 0; i < 1000; i++) {
            manager.addTransient(new Vec3(i % 50, 0, 0), RVP_LightSourceType.MUZZLE, i,
                    RVP_LightParameters.muzzle(20, false), 100);
        }
        assertEquals(RVP_ScreenLightManager.MAX_TRACKED, manager.size());
        assertEquals(8, sample(0).size());
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 0, light -> true).isEmpty());
    }

    @Test
    void distanceAndVisibilityCullBeforeBudget() {
        for (int i = 0; i < 20; i++) {
            manager.addTransient(new Vec3(i * 50, 0, 0), RVP_LightSourceType.MUZZLE, i,
                    RVP_LightParameters.muzzle(20, false), 100);
        }
        List<RVP_ScreenLight> visible = manager.collectVisible(Vec3.ZERO, 0, 128, 8,
                light -> light.position().x >= 25);
        assertEquals(2, visible.size());
        assertTrue(visible.stream().allMatch(light -> light.position().x >= 25 && light.position().x < 150));
    }

    @Test
    void malformedPositionsAndDisabledLightsNeverEnterShaderInput() {
        assertEquals(0, manager.addTransient(new Vec3(Double.NaN, 0, 0), RVP_LightSourceType.EXPLOSION,
                null, RVP_LightParameters.explosion(4), 100));
        assertEquals(0, manager.size());
    }

    @Test
    void engineVolumeWithinScaledRangeSurvivesTheObserverDistanceBudget() {
        UUID key = UUID.randomUUID();
        manager.upsertPersistent(key, new Vec3(0, 0, -2500), RVP_LightParameters.engine(4800));
        var selected = manager.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true);
        assertEquals(1, selected.size());
        assertEquals(2500, selected.get(0).radius());
        manager.upsertPersistent(key, new Vec3(0, 0, -2800), RVP_LightParameters.engine(4800));
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true).isEmpty());
    }
}

