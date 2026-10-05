package org.ywzj.rvp.uav;

import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定翼布尔杆量闭环制导与可持续盘旋解算的单元测试。
 * 符号约定：航向误差正值 = 需向左转（MC yaw 增方向）；zRot 负值 = 左坡度。
 */
class RVP_UavLoiterGuidanceTest {

    @Test
    void largeLeftHeadingErrorPressesLeftYawAndLeftRoll() {
        // 误差 +60°（需大幅左转）：偏航杆 leftYaw 置位；目标坡度 -40°，当前平飞 0° → rollLeft
        RVP_UavLoiterGuidance.FixedWingControls c = RVP_UavLoiterGuidance.computeFixedWingControls(
                60f, -40f, 0f, 0.0, 0.0, 0);
        assertTrue(c.leftYaw());
        assertFalse(c.rightYaw());
        assertTrue(c.rollLeft());
        assertFalse(c.rollRight());
    }

    @Test
    void largeRightHeadingErrorMirrorsControls() {
        RVP_UavLoiterGuidance.FixedWingControls c = RVP_UavLoiterGuidance.computeFixedWingControls(
                -60f, 40f, 0f, 0.0, 0.0, 0);
        assertTrue(c.rightYaw());
        assertFalse(c.leftYaw());
        assertTrue(c.rollRight());
        assertFalse(c.rollLeft());
    }

    @Test
    void bankClosedLoopReleasesRollAtTargetBank() {
        // 目标 -24°，当前 -25° → 坡度误差 1° < 死区 3° → 滚转杆释放（航向 40° 偏航杆仍置位）
        RVP_UavLoiterGuidance.FixedWingControls c = RVP_UavLoiterGuidance.computeFixedWingControls(
                40f, -24f, -25f, 0.0, 0.0, 0);
        assertTrue(c.leftYaw());
        assertFalse(c.rollLeft());
        assertFalse(c.rollRight());
    }

    @Test
    void bankOvershootCorrectsOppositely() {
        // 目标 -12°，当前 -20°（压过头）→ 坡度误差 +8° → rollRight
        RVP_UavLoiterGuidance.FixedWingControls c = RVP_UavLoiterGuidance.computeFixedWingControls(
                20f, -12f, -20f, 0.0, 0.0, 0);
        assertTrue(c.rollRight());
        assertFalse(c.rollLeft());
    }

    @Test
    void altitudeLoopTracksTargetClimbRateWithDamping() {
        // 高度误差 30 格 → 目标爬升率 +2.4 m/s；垂直速度 0 → 误差 +2.4 → up 脉冲
        RVP_UavLoiterGuidance.FixedWingControls climb = RVP_UavLoiterGuidance.computeFixedWingControls(
                0f, 0f, 0f, 30.0, 0.0, 0);
        assertTrue(climb.up());
        assertFalse(climb.down());
        // 已在 +2.4 m/s（垂直速度误差归零死区内）→ 双杆释放（阻尼，不再过冲飞天）
        RVP_UavLoiterGuidance.FixedWingControls damped = RVP_UavLoiterGuidance.computeFixedWingControls(
                0f, 0f, 0f, 30.0, 2.4, 0);
        assertFalse(damped.up());
        assertFalse(damped.down());
        // 爬升过冲到 +4 m/s → 误差 -1.6 超死区 → down 修正（旧版纯高度误差会继续 up）
        RVP_UavLoiterGuidance.FixedWingControls overshoot = RVP_UavLoiterGuidance.computeFixedWingControls(
                0f, 0f, 0f, 30.0, 4.0, 0);
        assertTrue(overshoot.down());
        assertFalse(overshoot.up());
    }

    @Test
    void loiterSolverKeepsConfiguredRadiusWhenLiftAllows() {
        // 大升力余量气动（v_min=20 m/s）：100 格半径临界坡度 asin(400/980)=24.1° < 40° 上限
        // → 配置半径可行，坡度取临界值，目标速度 = sqrt(100×9.8×tan24.1°) ≈ 43.8 且 ≥ v_min
        RVP_UavLoiterGuidance.FixedWingLoiterSolution s =
                RVP_UavLoiterGuidance.resolveFixedWingLoiterSolution(100.0, 20.0, 3.0);
        assertEquals(100.0, s.actualRadius(), 0.5);
        assertEquals(24.1, s.targetBankDeg(), 0.4);
        assertTrue(s.targetSpeedMps() >= 20.0);
    }

    @Test
    void loiterSolverFallsBackToPhysicalRadiusWhenLiftInsufficient() {
        // 高 v_min 气动（v_min=28.6 m/s，用户实测调参近似）：临界坡度 asin(818/980)=56.5° > 40° 上限
        // → 坡度封顶 40°，半径放大到 v_min²/(g·sin40°) ≈ 130 格（物理不可能就不再硬撑配置值）
        RVP_UavLoiterGuidance.FixedWingLoiterSolution s =
                RVP_UavLoiterGuidance.resolveFixedWingLoiterSolution(100.0, 28.6, 3.0);
        assertEquals(40.0, s.targetBankDeg(), 0.1);
        assertEquals(129.7, s.actualRadius(), 1.0);
        // 目标速度 = sqrt(129.7×9.8×tan40°) ≈ 32.7 m/s ≥ v_min——此速度下 40° 坡度恰好守得住高度
        assertTrue(s.targetSpeedMps() >= 28.6);
    }

    @Test
    void loiterSolverGeometricFallbackWithoutAeroData() {
        // v_min 不可得（≤0）：按当前空速几何反算；低速时配置半径可行
        RVP_UavLoiterGuidance.FixedWingLoiterSolution s =
                RVP_UavLoiterGuidance.resolveFixedWingLoiterSolution(100.0, 0, 1.2);
        assertEquals(100.0, s.actualRadius(), 0.5);
        assertTrue(s.targetBankDeg() >= 0);
    }

    @Test
    void tangentYawMatchesLoiterDirection() {
        // 圆东侧（dx=+100, dz=0）：右盘旋（顺时针）应朝南（yaw≈0），左盘旋朝北（yaw≈180）
        double right = RVP_UavLoiterGuidance.tangentYaw(100.0, 0.0, 1);
        double left = RVP_UavLoiterGuidance.tangentYaw(100.0, 0.0, -1);
        assertEquals(0.0, Mth.wrapDegrees((float) right), 0.5);
        assertEquals(180.0, Math.abs(Mth.wrapDegrees((float) left)), 0.5);
        // 圆南侧（dx=0, dz=+100）：右盘旋应朝西（yaw=90）
        double south = RVP_UavLoiterGuidance.tangentYaw(0.0, 100.0, 1);
        assertEquals(90.0, Mth.wrapDegrees((float) south), 0.5);
        // 切线必须与位置向量垂直（点积为 0）——防止再出"朝心/朝外"的假切线
        double t = Math.toRadians(RVP_UavLoiterGuidance.tangentYaw(60.0, 80.0, 1));
        double dot = (60.0 * -Math.sin(t)) + (80.0 * Math.cos(t));
        assertEquals(0.0, dot, 1.0E-6);
    }
}
