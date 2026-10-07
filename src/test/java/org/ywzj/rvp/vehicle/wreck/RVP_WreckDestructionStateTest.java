package org.ywzj.rvp.vehicle.wreck;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.vehicle.wreck.RVP_WreckDestructionState.Mode;

import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.*;

/** 验证互斥概率、实际时间边界及保存后一次性执行，不依赖客户端观察时间。 */
class RVP_WreckDestructionStateTest {
    @Test
    void allOneHundredOutcomesHaveExactThirtyThirtyFortyWeights() {
        EnumMap<Mode, Integer> counts = new EnumMap<>(Mode.class);
        for (int roll = 0; roll < 100; roll++) {
            // 调用生产分段，穷举均匀整数域验证权重而非依赖有波动的随机统计。
            counts.merge(RVP_WreckDestructionState.selectMode(roll), 1, Integer::sum);
        }
        assertEquals(30, counts.get(Mode.IMMEDIATE));
        assertEquals(30, counts.get(Mode.BURN_ONLY));
        assertEquals(40, counts.get(Mode.DELAYED));
        assertThrows(IllegalArgumentException.class, () -> RVP_WreckDestructionState.selectMode(100));
    }

    @Test
    void defaultLifetimeProducesTwentyFourSecondsAndNinePointSixToNineteenPointTwoLaunch() {
        // 调用双端公共时长和延迟规则，默认 60 秒残骸 ×40%=480 tick。
        long duration = RVP_WreckDestructionState.cookoffDurationTicks(60, RVP_WreckDestructionState.WRECK_LIFETIME_PERCENT);
        assertEquals(480, duration);
        assertEquals(192, RVP_WreckDestructionState.launchDelay(duration, 0));
        assertEquals(384, RVP_WreckDestructionState.launchDelay(duration, 192));
        assertThrows(IllegalArgumentException.class, () -> RVP_WreckDestructionState.launchDelay(duration, 193));
    }

    @Test
    void roundedTickBoundsNeverEscapeFortyToEightyPercentIncludingShortLifetimes() {
        for (long duration = 2; duration <= 1500; duration++) {
            // 调用生产边界，外部按百分比不等式验证包含性和舍入正确性。
            long min = RVP_WreckDestructionState.minimumLaunchDelay(duration);
            long max = RVP_WreckDestructionState.maximumLaunchDelay(duration);
            assertTrue(min * 5 >= duration * 2);
            assertTrue((min - 1) * 5 < duration * 2);
            assertTrue(max * 5 <= duration * 4);
            assertTrue((max + 1) * 5 > duration * 4);
            assertTrue(min <= max);
        }
    }

    @Test
    void immediateLaunchNeverBurnsAndDoesNotBlockOrdinarySmoke() {
        RVP_WreckDestructionState state = new RVP_WreckDestructionState(Mode.IMMEDIATE, 1000, 0, 0, 30, true);
        // 调用生产时间门，立即飞头从首 tick 起就跳过殉燃。
        assertFalse(state.isBurning(1000));
        assertFalse(state.isBurning(1001));
        assertTrue(state.canEmitLongSmoke(1000));
        assertFalse(state.isLaunchDue(1000));
    }

    @Test
    void burnOnlyNeverLaunchesEvenAfterEntireWreckLifetime() {
        RVP_WreckDestructionState state = new RVP_WreckDestructionState(Mode.BURN_ONLY, 1000, 480, -1, 30, false);
        // 调用生产时间门，仅殉燃分支没有任何到时飞头的出口。
        assertFalse(state.isBurning(999));
        assertTrue(state.isBurning(1000));
        assertTrue(state.isBurning(1479));
        assertFalse(state.isBurning(1480));
        assertFalse(state.isLaunchDue(1000));
        assertFalse(state.isLaunchDue(100000));
        assertFalse(state.canEmitLongSmoke(1509));
        assertTrue(state.canEmitLongSmoke(1510));
    }

    @Test
    void delayedLaunchUsesAbsoluteDestructionTimeAndRemainsConsumedAfterReload() {
        RVP_WreckDestructionState state = new RVP_WreckDestructionState(Mode.DELAYED, 1000, 480, 288, 60, false);
        // 调用持久化编解码模拟离开追踪和读档，恢复时仍使用 1000+288 的原定时间。
        RVP_WreckDestructionState loaded = RVP_WreckDestructionState.fromTag(state.toTag());
        assertEquals(state, loaded);
        assertFalse(loaded.isLaunchDue(1287));
        assertTrue(loaded.isLaunchDue(1288));
        assertTrue(loaded.isLaunchDue(1300));
        RVP_WreckDestructionState consumed = loaded.markPartsLaunched();
        RVP_WreckDestructionState reloaded = RVP_WreckDestructionState.fromTag(consumed.toTag());
        assertEquals(consumed, reloaded);
        assertFalse(reloaded.isLaunchDue(1300));
        assertFalse(reloaded.isLaunchDue(100000));
        // 调用同一快照的视觉门；飞头不会把时间轴归零，过期重进范围也不能重新点燃。
        assertTrue(reloaded.isBurning(1300));
        assertFalse(reloaded.isBurning(1480));
        assertFalse(reloaded.canEmitLongSmoke(1539));
        assertTrue(reloaded.canEmitLongSmoke(1540));
    }

    @Test
    void missingOrContradictoryPersistenceIsRejectedWithoutInventingAPlan() {
        assertNull(RVP_WreckDestructionState.fromTag(null));
        assertNull(RVP_WreckDestructionState.fromTag(new CompoundTag()));
        RVP_WreckDestructionState state = new RVP_WreckDestructionState(Mode.DELAYED, 1000, 480, 288, 30, false);
        // 调用生产编码后制造缺键/未知枚举/非法时刻，确认不会补默认值恢复抽签。
        CompoundTag missing = state.toTag();
        missing.remove("StartedAt");
        assertNull(RVP_WreckDestructionState.fromTag(missing));
        CompoundTag unknown = state.toTag();
        unknown.putString("Mode", "UNKNOWN");
        assertNull(RVP_WreckDestructionState.fromTag(unknown));
        CompoundTag tooEarly = state.toTag();
        tooEarly.putLong("LaunchDelayTicks", 191);
        assertNull(RVP_WreckDestructionState.fromTag(tooEarly));
    }

    @Test
    void disabledDurationAndLargeConfigurationAreFinite() {
        // 调用公共计算，0 关闭殉燃，最大合法 common 整数使用 long 不溢出。
        assertEquals(0, RVP_WreckDestructionState.cookoffDurationTicks(0, 40));
        assertEquals(0, RVP_WreckDestructionState.cookoffDurationTicks(60, Double.NaN));
        assertEquals(17179869176L, RVP_WreckDestructionState.cookoffDurationTicks(Integer.MAX_VALUE, 40));
    }
}
