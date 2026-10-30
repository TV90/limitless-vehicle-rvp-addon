package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.ywzj.rvp.light.RVP_RocketPlumeParameters;
import org.ywzj.rvp.light.RVP_LightParameters;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 新喷焰尺寸、独立预算、燃尽/断流与世界生命周期的真实管理器回归。 */
class RVP_RocketPlumeManagerTest {
    @Test
    void configuredScaleChangesBothDimensionsWithoutChangingBrightness() {
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(new Object(), 10);
        manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(0, 0, -4), 0.3F, 0, 1, false);
        manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(0, 0, -1), 0.6F, 0, 1, false);
        var plumes = manager.collect(Vec3.ZERO, 0, plume -> true);
        assertEquals(2, plumes.size());
        assertEquals(3, plumes.get(0).length(), 0.00001);
        assertEquals(0.45, plumes.get(0).radius(), 0.00001);
        assertEquals(6, plumes.get(1).length(), 0.00001);
        assertEquals(0.9, plumes.get(1).radius(), 0.00001);
        assertEquals(1, plumes.get(1).strength());
        assertEquals(1, plumes.get(0).direction().length(), 0.00001);
    }

    @Test
    void missingAndInvalidScalesUseExistingDefault() {
        for (Float input : new Float[] {null, 0F, -1F, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE}) {
            assertEquals(0.3F, RVP_RocketPlumeParameters.scale(input));
        }
        assertEquals(0.015F, RVP_RocketPlumeParameters.scale(0.015F));
    }

    @Test
    void twelveDistantEnginesDoNotShareEightLightOr256BlockBudgets() {
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(new Object(), 10);
        for (int index = 0; index < 12; index++) {
            manager.upsert(UUID.randomUUID(), new Vec3(index, 0, -4096), new Vec3(0, 0, -1), null, 0, 1, false);
        }
        assertEquals(12, manager.collect(Vec3.ZERO, 0.5F, plume -> true).size());
    }

    @Test
    void burnoutIsImmediateAndLostHeartbeatExpiresAfterTwoTicks() {
        Object world = new Object();
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(world, 10);
        UUID key = UUID.randomUUID();
        manager.upsert(key, Vec3.ZERO, new Vec3(0, 0, -1), null, 0, 1, false);
        manager.beginWorld(world, 11);
        assertEquals(1, manager.collect(Vec3.ZERO, 0.99F, plume -> true).size());
        manager.beginWorld(world, 12);
        assertFalse(manager.hasActive());
        manager.upsert(key, Vec3.ZERO, new Vec3(0, 0, -1), null, 0, 1, false);
        manager.remove(key);
        assertFalse(manager.hasActive());
    }

    @Test
    void previewFadesWithoutShrinkingAndPausesWithGameClock() {
        Object world = new Object();
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(world, 10);
        manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(1, 0, 0), null, 0, 1, true);
        manager.beginWorld(world, 30);
        var sample = manager.collect(Vec3.ZERO, 0, plume -> true).get(0);
        assertEquals(0.5, sample.strength(), 0.00001);
        assertEquals(3, sample.length(), 0.00001);
        assertEquals(sample, manager.collect(Vec3.ZERO, 0, plume -> true).get(0));
        manager.beginWorld(world, 50);
        assertFalse(manager.hasActive());
    }

    @Test
    void reconnectAndClockRollbackDiscardPriorGeometry() {
        Object world = new Object();
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(world, 10);
        manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(1, 0, 0), null, 0, 1, true);
        manager.beginWorld(new Object(), 10);
        assertFalse(manager.hasActive());
        manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(1, 0, 0), null, 0, 1, true);
        manager.beginWorld(world, 0);
        assertFalse(manager.hasActive());
    }

    @Test
    void invalidInputCannotCreateScreenWideCorruptionAndBudgetsRemainBounded() {
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(new Object(), 10);
        manager.upsert(UUID.randomUUID(), new Vec3(Double.NaN, 0, 0), new Vec3(1, 0, 0), null, 0, 1, false);
        manager.upsert(UUID.randomUUID(), Vec3.ZERO, Vec3.ZERO, null, 0, 1, false);
        manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(Double.MAX_VALUE, 0, 0), null, 0, 1, false);
        manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(1, 0, 0), null, 0, Float.NaN, false);
        assertFalse(manager.hasActive());
        for (int index = 0; index < 300; index++) {
            manager.upsert(UUID.randomUUID(), new Vec3(index, 0, 0), new Vec3(1, 0, 0), null, 0, 1, false);
        }
        assertEquals(256, manager.size());
        assertEquals(128, manager.collect(Vec3.ZERO, 0, plume -> true).size());
        assertTrue(manager.collect(Vec3.ZERO, 0, plume -> false).isEmpty());
    }

    @Test
    void haloAndEnvironmentShareCaliberRangeWithoutChangingPlumeDimensions() {
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(new Object(), 10);
        for (float caliber : new float[] {0, 100, 300, 4800, Float.NaN}) {
            UUID key = UUID.randomUUID();
            manager.upsert(key, Vec3.ZERO, new Vec3(1, 0, 0), 0.3F, caliber, 1, true);
            var sample = manager.collect(Vec3.ZERO, 0, plume -> true).get(0);
            assertEquals(RVP_LightParameters.engine(caliber).radius() * 5, sample.haloRadius());
            assertEquals(sample.haloRadius(), sample.withStrength(0.5F).haloRadius());
            assertEquals(3, sample.length(), 0.00001F);
            assertEquals(0.45F, sample.radius(), 0.00001F);
            manager.remove(key);
        }
    }

    @Test
    void worldHaloProjectionTracksDistanceAndDoesNotExpandTheCoreDraw() {
        Matrix4f view = new Matrix4f();
        Matrix4f projection = new Matrix4f().perspective((float) Math.PI / 2, 1, 0.1F, 2048);
        RVP_RocketPlume near = new RVP_RocketPlume(new Vec3(0, 0, -100), new Vec3(1, 0, 0), 3, 0.45F, 30, 1, 0);
        RVP_RocketPlume far = new RVP_RocketPlume(new Vec3(0, 0, -200), new Vec3(1, 0, 0), 3, 0.45F, 30, 1, 0);
        var nearHalo = RVP_RocketPlumePass.haloBounds(near, view, projection, Vec3.ZERO);
        var farHalo = RVP_RocketPlumePass.haloBounds(far, view, projection, Vec3.ZERO);
        var core = RVP_RocketPlumePass.shapeBounds(near, view, projection, Vec3.ZERO, 1080);
        assertNotNull(nearHalo); assertNotNull(farHalo); assertNotNull(core);
        assertTrue(nearHalo.right() - nearHalo.left() > 1.8F * (farHalo.right() - farHalo.left()));
        assertTrue(nearHalo.right() - nearHalo.left() > 10F * (core.right() - core.left()));
        RVP_RocketPlume behind = new RVP_RocketPlume(new Vec3(0, 0, 100), new Vec3(1, 0, 0), 3, 0.45F, 500, 1, 0);
        assertNull(RVP_RocketPlumePass.haloBounds(behind, view, projection, Vec3.ZERO));
    }
}
