package org.ywzj.rvp.client.visual.shake;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证通用调度的时序、隔离及生命周期，不依赖运行中的 Minecraft 世界。 */
class RVP_ScreenShakeSchedulerTest {
    /** 让三轴相位接近正弦峰值的种子，用于直接检查叠加上限。 */
    private static final long PEAK_SEED = (16384L << 8) | (16384L << 24) | (16384L << 40);
    /** 自定义持续 20 刻、三轴振幅 4/3/2 度且不额外摆动的测试参数。 */
    private static final RVP_ScreenShakeParameters CUSTOM =
            new RVP_ScreenShakeParameters(20, 4, 3, 2, 0, 0, 0);

    @AfterEach
    void clearTasks() {
        // 模拟世界清理，避免静态执行器的活动集合泄漏到下一项测试。
        RVP_ScreenShakeExecutor.clearAll();
    }

    @Test
    void customParametersSupportDelayCatchUpAndExpiry() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(10, () -> 1);
        // 提交未来开始的自定义参数，等待期不能产生偏移。
        assertTrue(scheduler.schedule("delayed", 100, 105, PEAK_SEED, 0, 100, CUSTOM));
        assertEquals(0.0F, scheduler.sample(104.5).yaw());
        assertEquals(4.0F, scheduler.sample(105).yaw(), 0.00001F);
        assertEquals(1.0F, scheduler.sample(115).yaw(), 0.00001F);
        assertEquals(0.0F, scheduler.sample(125).yaw());
        // 迟到十刻的事件直接从中段开始，完整过期的事件不入队。
        assertTrue(scheduler.schedule("late", 115, 105, PEAK_SEED, 0, 100, CUSTOM));
        assertEquals(1.0F, scheduler.sample(115).yaw(), 0.00001F);
        assertFalse(scheduler.schedule("expired", 125, 105, PEAK_SEED, 0, 100, CUSTOM));
    }

    @Test
    void duplicateCannotRestartButExpiredKeyCanBeReused() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(10, () -> 1);
        // 同一事件不能重启正在执行的脉冲；到期后无需等待渲染即可复用键。
        assertTrue(scheduler.schedule("event", 100, 100, PEAK_SEED, 0, 100, CUSTOM));
        assertFalse(scheduler.schedule("event", 110, 110, PEAK_SEED, 0, 100, CUSTOM));
        assertEquals(1.0F, scheduler.sample(110).yaw(), 0.00001F);
        assertTrue(scheduler.schedule("event", 120, 120, PEAK_SEED, 0, 100, CUSTOM));
        assertEquals(4.0F, scheduler.sample(120).yaw(), 0.00001F);
    }

    @Test
    void callersHaveIndependentKeysCapsScalesAndCancellation() {
        // 数组承载可实时调整的设置，验证登记后改变倍率同样生效。
        double[] scale = {1.0D};
        RVP_ScreenShakeScheduler first = new RVP_ScreenShakeScheduler(1, () -> scale[0]);
        RVP_ScreenShakeScheduler second = new RVP_ScreenShakeScheduler(5, () -> 1);
        RVP_ScreenShakeParameters other = new RVP_ScreenShakeParameters(40, 0, 6, 0, 1, 0, 3);
        // 两个调用方可提交相同事件键，并采用不同参数、倍率和上限。
        first.schedule("same", 0, 0, PEAK_SEED, 0, 100, CUSTOM);
        second.schedule("same", 0, 0, PEAK_SEED, 0, 100, other);
        RVP_ScreenShakeExecutor.ShakeOffset combined = RVP_ScreenShakeExecutor.sampleAll(0);
        assertEquals(1.0F, combined.yaw());
        assertEquals(6.0F, combined.pitch());
        scale[0] = 0.0D;
        assertEquals(0.0F, RVP_ScreenShakeExecutor.sampleAll(0).yaw());
        assertEquals(5.0F, RVP_ScreenShakeExecutor.sampleAll(0).pitch());
        // 取消第二方同名事件后重新启用第一方，证明去重与取消均按调用方隔离。
        second.cancel("same");
        scale[0] = 1.0D;
        assertEquals(1.0F, RVP_ScreenShakeExecutor.sampleAll(0).pitch());
    }

    @Test
    void intensityIsAppliedAfterSummingAndBeforeClamping() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(2, () -> 0.25D);
        // 两次振幅四度、距离衰减一半的脉冲应得到一度，不能提前按单脉冲限幅。
        scheduler.schedule("a", 0, 0, PEAK_SEED, 50, 100, CUSTOM);
        scheduler.schedule("b", 0, 0, PEAK_SEED, 50, 100, CUSTOM);
        assertEquals(1.0F, scheduler.sample(0).yaw(), 0.00001F);
    }

    @Test
    void worldClearRemovesActiveAndPendingTasksAndAllowsReuse() {
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(5, () -> 1);
        // 世界清理必须同时取消当前任务及尚未到时的延迟任务。
        scheduler.schedule("active", 0, 0, PEAK_SEED, 0, 100, CUSTOM);
        scheduler.schedule("pending", 0, 100, PEAK_SEED, 0, 100, CUSTOM);
        RVP_ScreenShakeExecutor.clearAll();
        assertEquals(0.0F, scheduler.sample(100).yaw());
        assertEquals(0.0F, RVP_ScreenShakeExecutor.sampleAll(100).yaw());
        // 原调度器对象可在新世界再次提交并自动参与统一执行。
        scheduler.schedule("active", 0, 0, PEAK_SEED, 0, 100, CUSTOM);
        assertEquals(4.0F, RVP_ScreenShakeExecutor.sampleAll(0).yaw(), 0.00001F);
        assertEquals(0.0F, RVP_ScreenShakeExecutor.sampleAll(20).yaw());
        scheduler.schedule("again", 21, 21, PEAK_SEED, 0, 100, CUSTOM);
        assertEquals(4.0F, RVP_ScreenShakeExecutor.sampleAll(21).yaw(), 0.00001F);
    }

    @Test
    void invalidParametersAndOutOfRangeRequestsCannotPolluteCamera() {
        assertThrows(IllegalArgumentException.class,
                () -> new RVP_ScreenShakeParameters(0, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RVP_ScreenShakeParameters(12, Float.NaN, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RVP_ScreenShakeParameters(12, 1, 1, 1, Double.POSITIVE_INFINITY, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RVP_ScreenShakeScheduler(-1, () -> 1));
        RVP_ScreenShakeScheduler scheduler = new RVP_ScreenShakeScheduler(5, () -> Double.NaN);
        // 影响范围外、无效时间和范围均不创建任务；无效实时倍率视为静音。
        assertFalse(scheduler.schedule("far", 0, 0, 1, 100, 100, CUSTOM));
        assertFalse(scheduler.schedule("range", 0, 0, 1, 0, Double.NaN, CUSTOM));
        assertFalse(scheduler.schedule("time", Double.NaN, 0, 1, 0, 100, CUSTOM));
        assertTrue(scheduler.schedule("silent", 0, 0, PEAK_SEED, 0, 100, CUSTOM));
        assertEquals(0.0F, scheduler.sample(0).yaw());
    }
}
