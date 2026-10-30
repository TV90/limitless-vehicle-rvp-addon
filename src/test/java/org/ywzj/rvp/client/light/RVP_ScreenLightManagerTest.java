package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.light.RVP_LightParameters;
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
    void start() { manager.beginWorld(world, 100); }

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
        assertEquals(parameters.intensity(), sample(0).get(0).intensity());
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
        manager.addTransient(new Vec3(1, 0, 0), RVP_LightSourceType.EXPLOSION, null,
                RVP_LightParameters.explosion(4), 100);
        RVP_ScreenLight first = sample(0).get(0);
        manager.tick(world, 104);
        RVP_ScreenLight middle = sample(0.5F).get(0);
        assertTrue(middle.intensity() < first.intensity());
        assertTrue(middle.green() < first.green());
        assertTrue(middle.blue() < first.blue());
        for (int i = 0; i < 240; i++) { assertEquals(middle, sample(0.5F).get(0)); }
        manager.tick(world, 108);
        assertFalse(manager.hasActiveLights());
    }

    @Test
    void delayedEventsCatchUpAndAlreadyExpiredEventsAreRejected() {
        RVP_LightParameters parameters = RVP_LightParameters.explosion(4);
        assertEquals(0, manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 90));
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 96);
        assertTrue(sample(0).get(0).intensity() < parameters.intensity());
        assertEquals(4, sample(0).get(0).remainingTicks());
    }

    @Test
    void slightlyLeadingServerClockStartsAtAgeZeroInsteadOfDroppingFlash() {
        RVP_LightParameters parameters = RVP_LightParameters.explosion(4);
        assertNotEquals(0, manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, parameters, 105));
        assertEquals(parameters.durationTicks(), sample(0).get(0).remainingTicks());
        manager.tick(world, 108);
        assertTrue(sample(0).isEmpty());
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
    void engineVolumeWithinFiveHundredBlocksSurvivesTheObserverDistanceBudget() {
        UUID key = UUID.randomUUID();
        manager.upsertPersistent(key, new Vec3(0, 0, -500), RVP_LightParameters.engine(4800));
        var selected = manager.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true);
        assertEquals(1, selected.size());
        assertEquals(500, selected.get(0).radius());
        manager.upsertPersistent(key, new Vec3(0, 0, -800), RVP_LightParameters.engine(4800));
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true).isEmpty());
    }
}
