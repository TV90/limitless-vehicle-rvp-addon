package org.ywzj.rvp.client.visual.shake;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning.Group.sphere;
import static org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning.Parameter.*;

/** 玩家移动、实际扩张时序、单次抵达和衰减边界的数值回归，不启动游戏或 OpenGL。 */
class RVP_ShockwaveScreenShakeTest {
    /** 三轴初始相位都接近正弦峰值，便于核对抵达瞬间的实际角度。 */
    private static final long PEAK_SEED = (16384L << 8) | (16384L << 24) | (16384L << 40);

    @AfterEach
    void clearWorld() {
        // 调用现有世界清理，测试之间不能共享活动脉冲或冲击波调参。
        RVP_ScreenShakeExecutor.clearAll();
        RVP_ShockwaveRuntimeTuning.reset();
    }

    @Test
    void movingTowardExplosionReceivesWaveEarlierUsingArrivalDistance() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveScreenShake wave = new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler);
        // 初始距离 80 格；向爆心移动后第三刻已在 30 格波面内，不能仍等到第八刻。
        assertFalse(wave.update(100, 80, 0, 100));
        assertFalse(wave.update(102, 40, 20, 100));
        assertTrue(wave.update(103, 20, 30, 100));
        assertEquals(0.72F, scheduler.sample(103).yaw(), 0.00001F);
        assertEquals(0.56F, scheduler.sample(103).pitch(), 0.00001F);
        assertEquals(0.40F, scheduler.sample(103).roll(), 0.00001F);
    }

    @Test
    void movingAwayDelaysArrivalUntilWaveActuallyCatchesPlayer() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveScreenShake wave = new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler);
        assertFalse(wave.update(0, 20, 0, 100));
        // 波面已经超过初始位置，但玩家此时在更远处，仍不能震动。
        assertFalse(wave.update(4, 60, 40, 100));
        assertEquals(0.0F, scheduler.sample(4).yaw());
        assertTrue(wave.update(6, 60, 60, 100));
        assertEquals(0.36F, scheduler.sample(6).yaw(), 0.00001F);
    }

    @Test
    void oneWaveNeverRetriggersAfterPulseExpiresButAnotherWaveCan() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveScreenShake wave = new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler);
        assertTrue(wave.update(0, 30, 30, 100));
        assertFalse(wave.update(1, 30, 40, 100));
        assertEquals(0.0F, scheduler.sample(12).yaw());
        // 即使原脉冲已从通用调度器移除，离开再进入也不能用同一道波重新提交。
        assertFalse(wave.update(13, 120, 90, 100));
        assertFalse(wave.update(14, 0, 95, 100));
        assertEquals(0.0F, scheduler.sample(14).yaw());
        RVP_ShockwaveScreenShake next = new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler);
        assertTrue(next.update(14, 0, 1, 100));
        assertEquals(0.90F, scheduler.sample(14).yaw(), 0.00001F);
    }

    @Test
    void edgeHasZeroStrengthAndStillConsumesItsOnlyArrival() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveScreenShake edge = new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler);
        assertFalse(edge.update(0, 100, 100, 100));
        assertFalse(edge.update(1, 0, 100, 100));
        assertEquals(0.0F, scheduler.sample(1).yaw());
        RVP_ShockwaveScreenShake halfRange = new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler);
        assertTrue(halfRange.update(1, 50, 50, 100));
        assertEquals(0.45F, scheduler.sample(1).yaw(), 0.00001F);
    }

    @Test
    void actualExpansionSnapshotControlsArrivalAndMaximumRange() {
        // 放慢扩张后，15 刻寿命内只能到达 45 格；不可仍使用完整扩张后的 90 格范围。
        RVP_ShockwaveRuntimeTuning.set(sphere, expansionSpeed, 0.5F);
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1);
        float maximumRadius = tuning.sphereRadius(tuning.get(sphere, lifetime) / tuning.age(sphere, 1));
        assertEquals(45.0F, maximumRadius, 0.00001F);
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveScreenShake wave = new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler);
        assertFalse(wave.update(7.0, 22.5, tuning.sphereRadius(7), maximumRadius));
        assertTrue(wave.update(7.5, 22.5, tuning.sphereRadius(7.5F), maximumRadius));
        assertEquals(0.45F, scheduler.sample(7.5).yaw(), 0.00001F);
        // 后续调参只影响新快照，正在播放的波面不会改成另一套时序。
        RVP_ShockwaveRuntimeTuning.set(sphere, speed, 2.4F);
        var faster = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1);
        assertEquals(tuning.sphereRadius(7.5F), faster.sphereRadius(3.75F), 0.00001F);
        assertEquals(45.0F, faster.sphereRadius(faster.get(sphere, lifetime) / faster.age(sphere, 1)), 0.00001F);
    }

    @Test
    void submittedPulseFinishesTwelveTicksEvenWhenFrontStopsUpdating() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveScreenShake wave = new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler);
        assertTrue(wave.update(10, 50, 50, 100));
        // 模拟波面随即消失：不再调用 update，已提交任务仍然独立播放至第 22 刻。
        assertNotEquals(0.0F, scheduler.sample(21).yaw());
        assertEquals(0.0F, scheduler.sample(22).yaw());
        assertEquals(0.0F, scheduler.sample(22).pitch());
        assertEquals(0.0F, scheduler.sample(22).roll());
    }

    @Test
    void mutedOrClearedWaveCannotReplayAfterSettingIsRestored() {
        // 可变数组模拟游戏中实时调整爆炸震动倍率。
        double[] intensity = {0.0};
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> intensity[0]);
        RVP_ShockwaveScreenShake wave = new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler);
        assertTrue(wave.update(0, 0, 1, 100));
        assertEquals(0.0F, scheduler.sample(0).yaw());
        intensity[0] = 1.0;
        assertNotEquals(0.0F, scheduler.sample(1).yaw());
        // 世界卸载后已有脉冲全部清除，同一波面也不能通过再次检查复活任务。
        RVP_ScreenShakeExecutor.clearAll();
        assertEquals(0.0F, scheduler.sample(2).yaw());
        assertFalse(wave.update(2, 0, 10, 100));
        assertEquals(0.0F, scheduler.sample(2).yaw());
    }

    @Test
    void invalidOrOutOfRangeSampleDoesNotConsumeFutureArrival() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveScreenShake wave = new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler);
        assertFalse(wave.update(Double.NaN, 0, 1, 100));
        assertFalse(wave.update(0, Double.NaN, 1, 100));
        assertFalse(wave.update(0, -1, 1, 100));
        assertFalse(wave.update(0, 0, Double.POSITIVE_INFINITY, 100));
        assertFalse(wave.update(0, 0, -1, 100));
        assertFalse(wave.update(0, 0, 1, 0));
        assertFalse(wave.update(0, 0, 1, Double.NaN));
        assertFalse(wave.update(0, 101, 110, 100));
        assertTrue(wave.update(1, 0, 1, 100));
        assertEquals(0.90F, scheduler.sample(1).yaw(), 0.00001F);
    }
}
