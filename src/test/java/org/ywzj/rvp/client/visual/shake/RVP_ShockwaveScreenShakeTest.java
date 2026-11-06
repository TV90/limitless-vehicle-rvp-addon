package org.ywzj.rvp.client.visual.shake;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;
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
        RVP_ShockwaveShakePropagation.clear();
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
    void doubledShakeRangeReachesBeyondUnchangedVisualEffect() {
        // 调用真实默认快照计算 32 格爆炸，确认视觉最大半径仍为约 113.29 格。
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(32, 1);
        float maximumRadius = tuning.sphereRadius(tuning.get(sphere, lifetime) / tuning.age(sphere, 1));
        assertEquals(113.2905F, maximumRadius, 0.0001F);
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveShakePropagation wave = new RVP_ShockwaveShakePropagation(Vec3.ZERO, 0, 220,
                tuning, new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler));
        // 震动仍每刻传播六格；170 格处不能在旧版加速实现的第 14.5 刻就收到。
        assertFalse(wave.update(14.5, 170));
        assertEquals(87.0, wave.radiusAt(14.5), 0.0001);
        // 第 28.5 刻可见波面已经消失，独立传播到达 171 格后才触发外圈震动。
        assertEquals(0.0F, tuning.alpha(sphere, 28.5F));
        assertFalse(wave.update(28, 170));
        assertTrue(wave.update(28.5, 170));
        assertEquals(171.0, wave.radiusAt(28.5), 0.0001);
        assertEquals(0.90F * (1 - 170 / (maximumRadius * 2)), scheduler.sample(28.5).yaw(), 0.00001F);
    }

    @Test
    void doubledRangeHasHalfStrengthAtOldLimitAndZeroAtNewLimit() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        // 调用真实 24 格爆炸快照，原半径 90 格在第十五刻到达，新边界为 180 格。
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1);
        RVP_ShockwaveShakePropagation halfRange = new RVP_ShockwaveShakePropagation(Vec3.ZERO, 0, 220,
                tuning, new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler));
        assertFalse(halfRange.update(14, 90));
        assertTrue(halfRange.update(15, 90));
        assertEquals(0.45F, scheduler.sample(15).yaw(), 0.00001F);
        // 调用调度器清理已验证的旧边界脉冲，后续边界检查只观察新提交的震动。
        scheduler.clear();
        RVP_ShockwaveShakePropagation edge = new RVP_ShockwaveShakePropagation(Vec3.ZERO, 0, 220,
                tuning, new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler));
        assertFalse(edge.update(30, 180));
        assertFalse(edge.update(31, 0));
        RVP_ShockwaveShakePropagation outside = new RVP_ShockwaveShakePropagation(Vec3.ZERO, 0, 220,
                tuning, new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler));
        // 边界外不消耗抵达机会，玩家在传播结束前进入范围仍可收到唯一一次震动。
        assertFalse(outside.update(25, 181));
        assertTrue(outside.update(26, 0));
        assertEquals(0.90F, scheduler.sample(26).yaw(), 0.00001F);
    }

    @Test
    void oldArrivalCurveAndInitialRadiusStayUnscaledWithFixedSnapshot() {
        // 调用非匀速和非零初始半径设置，不能靠时间减半、半径加倍来伪装原速度。
        RVP_ShockwaveRuntimeTuning.set(sphere, expansionPower, 2);
        RVP_ShockwaveRuntimeTuning.set(sphere, initialRadius, 10);
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1);
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveShakePropagation wave = new RVP_ShockwaveShakePropagation(Vec3.ZERO, 0, 220,
                tuning, new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler));
        assertEquals(10.0, wave.radiusAt(0));
        for (int age = 0; age <= 15; age++) {
            assertEquals(tuning.sphereRadius(age), wave.radiusAt(age), 0.00001);
        }
        // 玩家实时靠近才提前收到，不能在生成时就固定距离。
        assertFalse(wave.update(4, 70));
        assertTrue(wave.update(5, 50));
        assertEquals(0.675F, scheduler.sample(5).yaw(), 0.00001F);
        // 调用后续调参后，已有事件在视觉结束后仍保持原基础速度六格/刻。
        RVP_ShockwaveRuntimeTuning.set(sphere, speed, 2.4F);
        assertEquals(160.0, wave.radiusAt(25), 0.0001);
    }

    @Test
    void slowerExpansionStillUsesOriginalSpeedInExtendedRange() {
        // 调用仅扩张速度设置减半：旧寿命内到 45 格，外圈也只能按三格/刻继续传播。
        RVP_ShockwaveRuntimeTuning.set(sphere, expansionSpeed, 0.5F);
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(24, 1);
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveShakePropagation wave = new RVP_ShockwaveShakePropagation(Vec3.ZERO, 0, 220,
                tuning, new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler));
        assertEquals(22.5, wave.radiusAt(7.5), 0.0001);
        assertEquals(67.5, wave.radiusAt(22.5), 0.0001);
        assertFalse(wave.update(22.4, 67.5));
        assertTrue(wave.update(22.5, 67.5));
        assertEquals(0.225F, scheduler.sample(22.5).yaw(), 0.00001F);
    }

    @Test
    void independentQueueSurvivesVisualExpiryHandlesLateEventsAndClearsWithWorld() {
        // 调用真实快照和两倍事件时长：原传播速度换算为三格/实际刻，旧视觉在第三十八刻回收。
        var tuning = RVP_ShockwaveRuntimeTuning.snapshot().forExplosion(32, 1);
        assertEquals(38, tuning.duration(sphere, 440));
        Vec3 center = new Vec3(1000, 50, -1000);
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 1);
        RVP_ShockwaveShakePropagation wave = new RVP_ShockwaveShakePropagation(center, 100, 440,
                tuning, new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler));
        // 调用真实登记路径，在旧视觉过期后收到事件，仍保留外圈传播，不能从零重播。
        RVP_ShockwaveShakePropagation.add(wave, 140);
        RVP_ShockwaveShakePropagation.tick(156);
        RVP_ShockwaveShakePropagation.updateAll(156, center.add(170, 0, 0));
        assertEquals(0.0F, scheduler.sample(156).yaw());
        RVP_ShockwaveShakePropagation.updateAll(157, center.add(170, 0, 0));
        assertTrue(scheduler.sample(157).yaw() > 0.0F);
        // 调用世界清理，尚未抵达的下一次爆炸不能在切换世界后触发。
        RVP_ShockwaveShakePropagation.add(new RVP_ShockwaveShakePropagation(center, 200, 440,
                tuning, new RVP_ShockwaveScreenShake(PEAK_SEED, scheduler)), 240);
        RVP_ShockwaveShakePropagation.clear();
        RVP_ScreenShakeExecutor.clearAll();
        RVP_ShockwaveShakePropagation.updateAll(257, center.add(170, 0, 0));
        assertEquals(0.0F, scheduler.sample(257).yaw());
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
