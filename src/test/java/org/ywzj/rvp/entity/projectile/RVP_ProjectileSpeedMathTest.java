package org.ywzj.rvp.entity.projectile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_ProjectileSpeedMathTest {
    /** 当前速率必须接受减速结果，不能用更早 Tick 的历史峰值回填。 */
    @Test
    void currentSpeedFollowsAuthoritativeDeceleration() {
        double historicalPeak = 8.0D;
        double deceleratedSpeed = 3.25D;

        assertEquals(3.25D,
                RVP_ProjectileSpeedMath.resolveCurrentSpeed(deceleratedSpeed), 1.0E-12D);
        assertEquals(8.0D,
                RVP_ProjectileSpeedMath.resolvePeakSpeed(historicalPeak, deceleratedSpeed), 1.0E-12D);
    }

    /** 新的实际高速只更新统计峰值，不改变当前速率规则。 */
    @Test
    void peakSpeedTracksNewMaximumIndependently() {
        assertEquals(9.5D,
                RVP_ProjectileSpeedMath.resolvePeakSpeed(8.0D, 9.5D), 1.0E-12D);
        assertEquals(9.5D,
                RVP_ProjectileSpeedMath.resolveCurrentSpeed(9.5D), 1.0E-12D);
    }

    /** 静止弹体仍保留最小数值基准，但该下限不能伪造历史峰值。 */
    @Test
    void zeroSpeedUsesReferenceFloorWithoutInflatingPeak() {
        assertEquals(RVP_ProjectileSpeedMath.MIN_REFERENCE_SPEED,
                RVP_ProjectileSpeedMath.resolveCurrentSpeed(0.0D), 1.0E-12D);
        assertEquals(0.0D,
                RVP_ProjectileSpeedMath.resolvePeakSpeed(0.0D, 0.0D), 1.0E-12D);
    }
}
