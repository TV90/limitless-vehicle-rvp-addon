package org.ywzj.rvp.client.visual.vehicle;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Parameter;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.VelocityParameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 大口径延迟炮口烟的边界、时长和客户端调参测试。 */
class RVP_DelayedMuzzleSmokeTest {

    /** 每个测试结束后恢复会话参数，避免测试顺序污染全局枚举状态。 */
    @AfterEach
    void resetSettings() {
        RVP_DelayedMuzzleSmokeSettings.resetAll();
    }

    /** 45 mm 边界属于延迟烟，44.99 mm 仍不进入延迟烟路径。 */
    @Test
    void heavyCaliberBoundaryIsInclusive() {
        assertFalse(RVP_DelayedMuzzleSmokeEmitter.supportsDelayedMuzzleSmoke(44.99f));
        assertTrue(RVP_DelayedMuzzleSmokeEmitter.supportsDelayedMuzzleSmoke(45.0f));
        assertTrue(RVP_DelayedMuzzleSmokeEmitter.supportsDelayedMuzzleSmoke(125.0f));
        assertFalse(RVP_DelayedMuzzleSmokeEmitter.supportsDelayedMuzzleSmoke(Float.NaN));
    }

    /** 固定喷烟间隔必须保持为每 1 tick 一枚。 */
    @Test
    void emissionIntervalIsOneTick() {
        assertEquals(1, RVP_DelayedMuzzleSmokeEmitter.EMIT_INTERVAL_TICKS);
    }

    /** 默认延迟、连续喷烟时长和单枚寿命符合用户定版区间。 */
    @Test
    void defaultDurationsMatchApprovedRanges() {
        assertEquals(10, Parameter.DELAY_MIN.intValue());
        assertEquals(20, Parameter.DELAY_MAX.intValue());
        assertEquals(15, Parameter.EMIT_DURATION_MIN.intValue());
        assertEquals(20, Parameter.EMIT_DURATION_MAX.intValue());
        assertEquals(40, Parameter.PARTICLE_LIFETIME_MIN.intValue());
        assertEquals(60, Parameter.PARTICLE_LIFETIME_MAX.intValue());
        assertEquals(0.12, VelocityParameter.AXIAL_SPEED_MIN.doubleValue(), 1.0E-9);
        assertEquals(0.15, VelocityParameter.AXIAL_SPEED_MAX.doubleValue(), 1.0E-9);
    }

    /** 随机取值包含上下边界，避免配置范围被错误地变成半开区间。 */
    @Test
    void randomDurationsStayWithinInclusiveBounds() {
        RandomSource random = RandomSource.create(20261002L);
        for (int i = 0; i < 200; i++) {
            int delay = RVP_DelayedMuzzleSmokeSettings.randomInclusive(
                    Parameter.DELAY_MIN, Parameter.DELAY_MAX, random);
            int duration = RVP_DelayedMuzzleSmokeSettings.randomInclusive(
                    Parameter.EMIT_DURATION_MIN, Parameter.EMIT_DURATION_MAX, random);
            int lifetime = RVP_DelayedMuzzleSmokeSettings.randomInclusive(
                    Parameter.PARTICLE_LIFETIME_MIN, Parameter.PARTICLE_LIFETIME_MAX, random);
            assertTrue(delay >= 10 && delay <= 20);
            assertTrue(duration >= 15 && duration <= 20);
            assertTrue(lifetime >= 40 && lifetime <= 60);
        }
        for (int i = 0; i < 200; i++) {
            double axialSpeed = RVP_DelayedMuzzleSmokeSettings.randomDoubleInclusive(
                    VelocityParameter.AXIAL_SPEED_MIN, VelocityParameter.AXIAL_SPEED_MAX, random);
            assertTrue(axialSpeed >= 0.12 && axialSpeed <= 0.15);
        }
    }

    /** 指令参数必须拒绝破坏同组最小值/最大值关系的设置。 */
    @Test
    void settingsRejectInvertedRanges() {
        RVP_DelayedMuzzleSmokeSettings.set(Parameter.DELAY_MAX, 30);
        assertThrows(IllegalArgumentException.class,
                () -> RVP_DelayedMuzzleSmokeSettings.set(Parameter.DELAY_MIN, 31));
        assertEquals(10, Parameter.DELAY_MIN.intValue());
        assertEquals(30, Parameter.DELAY_MAX.intValue());

        RVP_DelayedMuzzleSmokeSettings.set(VelocityParameter.AXIAL_SPEED_MAX, 0.18);
        assertThrows(IllegalArgumentException.class,
                () -> RVP_DelayedMuzzleSmokeSettings.set(VelocityParameter.AXIAL_SPEED_MIN, 0.19));
        assertEquals(0.12, VelocityParameter.AXIAL_SPEED_MIN.doubleValue(), 1.0E-9);
    }
}
