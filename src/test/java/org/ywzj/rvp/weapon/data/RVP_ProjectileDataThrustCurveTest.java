package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_BallisticTrajectoryMath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RVP_ProjectileData} 推进模型扩展（变质量 A1、推力曲线 A2、单位换算 A3）单元测试。
 *
 * <p>覆盖推力曲线按点火后 Tick 解析、变质量沿主燃烧窗口线性递减、推进判定对推力曲线的兼容，
 * 以及 RVP 游戏单位制下加速度 = thrust / mass（不除 {@code TICKS_PER_SECOND_SQUARED}）的换算契约。</p>
 */
class RVP_ProjectileDataThrustCurveTest {

    private final Gson gson = new Gson();

    @Test
    void resolvesThrustCurveByMotorTick() {
        RVP_ProjectileData data = gson.fromJson("""
                {
                  "has_rocket_engine": true,
                  "mass": 0.05,
                  "thrust": 0.03,
                  "motor_burn_time": 100,
                  "thrust_curve": {
                    "[[0,20]]": 0.06,
                    "[[20,100]]": 0.02
                  }
                }
                """, RVP_ProjectileData.class);

        assertTrue(data.hasThrustCurve());
        assertEquals(0.06f, data.resolveThrustAt(0), 1.0E-6f);
        assertEquals(0.06f, data.resolveThrustAt(20), 1.0E-6f);
        assertEquals(0.02f, data.resolveThrustAt(21), 1.0E-6f);
        assertEquals(0.02f, data.resolveThrustAt(100), 1.0E-6f);
        // 曲线为权威：区间外推力为 0（含燃烧窗口前/后）。
        assertEquals(0f, data.resolveThrustAt(101), 1.0E-6f);
        assertEquals(0f, data.resolveThrustAt(-1), 1.0E-6f);
    }

    @Test
    void fallsBackToScalarThrustWhenCurveAbsent() {
        RVP_ProjectileData data = gson.fromJson("""
                {"has_rocket_engine": true, "mass": 0.05, "thrust": 0.03, "motor_burn_time": 100}
                """, RVP_ProjectileData.class);

        assertFalse(data.hasThrustCurve());
        assertEquals(0.03f, data.resolveThrustAt(0), 1.0E-6f);
        assertEquals(0.03f, data.resolveThrustAt(500), 1.0E-6f);
    }

    @Test
    void variableMassDecreasesLinearlyOverBurnWindow() {
        RVP_ProjectileData data = gson.fromJson("""
                {
                  "has_rocket_engine": true,
                  "mass": 0.05,
                  "fuel_mass": 0.02,
                  "thrust": 0.03,
                  "motor_burn_time": 100
                }
                """, RVP_ProjectileData.class);

        // 发射瞬间 = 总质量；燃烧一半 = 干质量 + 燃料/2；燃烧结束 = 干质量；之后保持干质量。
        assertEquals(0.05f, data.resolveMassAt(0, 100f), 1.0E-6f);
        assertEquals(0.04f, data.resolveMassAt(50, 100f), 1.0E-6f);
        assertEquals(0.03f, data.resolveMassAt(100, 100f), 1.0E-6f);
        assertEquals(0.03f, data.resolveMassAt(200, 100f), 1.0E-6f);
        // 负 tick 按 0（未开始燃烧）处理。
        assertEquals(0.05f, data.resolveMassAt(-5, 100f), 1.0E-6f);
    }

    @Test
    void massStaysConstantWithoutFuelMass() {
        RVP_ProjectileData data = gson.fromJson("""
                {"has_rocket_engine": true, "mass": 0.05, "thrust": 0.03, "motor_burn_time": 100}
                """, RVP_ProjectileData.class);

        assertEquals(0.05f, data.resolveMassAt(0, 100f), 1.0E-6f);
        assertEquals(0.05f, data.resolveMassAt(50, 100f), 1.0E-6f);
        assertEquals(0.05f, data.resolveMassAt(200, 100f), 1.0E-6f);
    }

    @Test
    void propulsionEnabledWithThrustCurveWithoutScalarThrust() {
        RVP_ProjectileData data = gson.fromJson("""
                {
                  "has_rocket_engine": true,
                  "mass": 0.05,
                  "motor_burn_time": 100,
                  "thrust_curve": {"[[0,100]]": 0.02}
                }
                """, RVP_ProjectileData.class);

        assertTrue(data.hasEffectiveThrust());
        assertTrue(data.usesPropulsion());
        assertFalse(data.isRocketEngineMisconfigured());
    }

    @Test
    void thrustAccelerationPerTickUsesRvpGameUnitsWithoutDividingByTicksSquared() {
        // 调用本项目纯数学换算：加速度 = thrust / mass（不除 400）。
        assertEquals(1.25d, RVP_BallisticTrajectoryMath.thrustAccelerationPerTick(0.015, 0.012), 1.0E-9);
        assertEquals(0.0d, RVP_BallisticTrajectoryMath.thrustAccelerationPerTick(0.0, 0.012), 1.0E-9);
        // 除零保护：质量 0 时按最小 1e-6 处理，结果为有限数。
        assertTrue(Double.isFinite(RVP_BallisticTrajectoryMath.thrustAccelerationPerTick(0.015, 0.0)));
    }
}
