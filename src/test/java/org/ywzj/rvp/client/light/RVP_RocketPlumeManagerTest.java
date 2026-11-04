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

    /** 同刻多次渲染和逐刻续期不能反复点火；位置继续随导弹更新，部分 tick 负责平滑显现。 */
    @Test
    void engineIgnitionGrowsSmoothlyAndHeartbeatDoesNotRestartIt() {
        Object world = new Object();
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        UUID key = UUID.randomUUID();
        manager.beginWorld(world, 100);
        manager.upsertEngine(key, Vec3.ZERO, new Vec3(0, 0, -1), 0.3F, 300, 1, 7, 100);
        assertTrue(manager.collect(Vec3.ZERO, 0, plume -> true).isEmpty());
        var firstFrame = manager.collect(Vec3.ZERO, 0.5F, plume -> true).get(0);
        manager.beginWorld(world, 101);
        for (int frame = 0; frame < 3; frame++) {
            manager.upsertEngine(key, new Vec3(2, 0, 0), new Vec3(0, 0, -1), 0.3F, 300, 1, 7, 99);
        }
        var halfway = manager.collect(Vec3.ZERO, 0.5F, plume -> true).get(0);
        assertEquals(0.5F, halfway.transition(), 0.00001F);
        assertTrue(halfway.transition() > firstFrame.transition());
        assertEquals(1.5F, halfway.length(), 0.00001F);
        assertEquals(0.45F * Math.sqrt(0.5), halfway.radius(), 0.00001);
        assertEquals(new Vec3(2, 0, 0), halfway.position());
        assertEquals(halfway, manager.collect(Vec3.ZERO, 0.5F, plume -> true).get(0), "暂停/重复采样不得推进过渡");
        manager.beginWorld(world, 102);
        manager.upsertEngine(key, Vec3.ZERO, new Vec3(0, 0, -1), 0.3F, 300, 1, 7, 98);
        var steady = manager.collect(Vec3.ZERO, 1, plume -> true).get(0);
        assertEquals(1, steady.transition());
        assertEquals(3, steady.length(), 0.00001F);
        assertEquals(0.45F, steady.radius(), 0.00001F);
        assertEquals(firstFrame.haloRadius(), steady.haloRadius(), "过渡只改变亮度，不扩大光晕范围");
    }

    /** 燃尽前逐帧收束，到权威结束刻无残焰；下一次点火重新从零展开。 */
    @Test
    void engineBurnoutShrinksAndFadesBeforeDeadlineThenNextPulseStartsFresh() {
        Object world = new Object();
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        UUID key = UUID.randomUUID();
        for (int tick = 100; tick <= 110; tick++) {
            manager.beginWorld(world, tick);
            manager.upsertEngine(key, Vec3.ZERO, new Vec3(1, 0, 0), 0.3F, 0, 0.7F, 7, 110 - tick);
            if (tick == 107) {
                var middle = manager.collect(Vec3.ZERO, 0, plume -> true).get(0);
                var later = manager.collect(Vec3.ZERO, 0.5F, plume -> true).get(0);
                assertEquals(0.5F, middle.transition());
                assertTrue(later.transition() < middle.transition());
                assertTrue(later.length() < middle.length());
                assertTrue(later.radius() < middle.radius());
                assertEquals(0.7F, middle.strength(), "阶段强度与显示过渡各自保留，交给实际绘制统一合成");
                assertEquals(middle.transition(), middle.withStrength(0.2F).transition());
            }
            if (tick == 110) assertTrue(manager.collect(Vec3.ZERO, 0, plume -> true).isEmpty());
        }
        manager.remove(key);
        manager.beginWorld(world, 120);
        manager.upsertEngine(key, Vec3.ZERO, new Vec3(1, 0, 0), null, 0, 0.7F, 7, 30);
        assertTrue(manager.collect(Vec3.ZERO, 0, plume -> true).isEmpty());
        assertTrue(manager.collect(Vec3.ZERO, 0.5F, plume -> true).get(0).transition() < 0.1F);
    }

    /** 短脉冲的渐入与渐出允许重叠，无限窗口仅渐入；非法时间不产生 NaN 画面。 */
    @Test
    void transitionHandlesShortPulsesAndUnknownDeadlineWithoutDiscontinuity() {
        assertEquals(0, RVP_RocketPlumeParameters.transition(0, 2));
        assertTrue(RVP_RocketPlumeParameters.transition(1, 1) > 0);
        assertTrue(RVP_RocketPlumeParameters.transition(1, 1) < 1);
        assertEquals(0, RVP_RocketPlumeParameters.transition(2, 0));
        assertEquals(0.5F, RVP_RocketPlumeParameters.transition(1.5, Double.POSITIVE_INFINITY));
        assertEquals(1, RVP_RocketPlumeParameters.transition(3, Double.POSITIVE_INFINITY));
        assertEquals(0, RVP_RocketPlumeParameters.transition(Double.NaN, 100));
    }

    /** 新正式入口仍遵守心跳超时和立即清理，不能为了渐出在原地留下脱离弹体的火焰。 */
    @Test
    void timedEngineStillClearsOnHeartbeatLossWorldChangeAndRemoval() {
        Object world = new Object();
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        UUID key = UUID.randomUUID();
        manager.beginWorld(world, 100);
        manager.upsertEngine(key, Vec3.ZERO, new Vec3(1, 0, 0), null, 0, 1, 7, -1);
        assertFalse(manager.collect(Vec3.ZERO, 0.5F, plume -> true).isEmpty());
        manager.beginWorld(world, 102);
        assertFalse(manager.hasActive());
        manager.upsertEngine(key, Vec3.ZERO, new Vec3(1, 0, 0), null, 0, 1, 7, 100);
        manager.beginWorld(new Object(), 103);
        assertFalse(manager.hasActive());
        manager.upsertEngine(key, Vec3.ZERO, new Vec3(1, 0, 0), null, 0, 1, 7, 100);
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
