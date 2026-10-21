package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PIP 正根、闭速和不可解边界；不依赖世界实例。 */
class RVP_PredictiveInterceptMathTest {
    /** 比较相遇距离的浮点容差，单位格。 */
    private static final double EPSILON = 1.0E-7;

    @Test
    void crossingSolutionActuallyMeetsTarget() {
        Vec3 velocity = new Vec3(5.5, 0, 0);
        // 调用本项目 PIP 求解器，以相遇方程验证输出而非重复求根实现。
        var solution = RVP_PredictiveInterceptMath.solve(Vec3.ZERO, velocity,
                new Vec3(300, 0, 0), new Vec3(0, 0, 2));
        assertEquals(RVP_PredictiveInterceptMath.Status.QUADRATIC, solution.status());
        assertEquals(velocity.length() * solution.timeTicks(), solution.point().length(), EPSILON);
    }

    @Test
    void equalSpeedHeadOnUsesLinearRoot() {
        // 调用本项目 PIP 解等速迎头退化，100 格以 10 格/Tick 闭合应为 10 Tick。
        var solution = RVP_PredictiveInterceptMath.solve(Vec3.ZERO, new Vec3(5, 0, 0),
                new Vec3(100, 0, 0), new Vec3(-5, 0, 0));
        assertEquals(RVP_PredictiveInterceptMath.Status.LINEAR, solution.status());
        assertEquals(10.0, solution.timeTicks(), EPSILON);
    }

    @Test
    void fasterRecedingTargetDoesNotInventPositiveClosingSpeed() {
        // 调用本项目 PIP 覆盖旧公式把 -1 闭速误算成 +4 的案例。
        var solution = RVP_PredictiveInterceptMath.solve(Vec3.ZERO, new Vec3(5, 0, 0),
                new Vec3(100, 0, 0), new Vec3(6, 0, 0));
        assertFalse(solution.usable());
        assertEquals(RVP_PredictiveInterceptMath.Status.UNREACHABLE, solution.status());
    }

    @Test
    void noRootButApproachingUsesRelativeClosingOnly() {
        // 调用本项目 PIP 覆盖横向过快、无正根但径向仍以 5 格/Tick 接近的情况。
        var solution = RVP_PredictiveInterceptMath.solve(Vec3.ZERO, new Vec3(5, 0, 0),
                new Vec3(100, 0, 0), new Vec3(0, 0, 10));
        assertEquals(RVP_PredictiveInterceptMath.Status.CLOSING_APPROXIMATION, solution.status());
        assertEquals(20.0, solution.timeTicks(), EPSILON);
        assertEquals(new Vec3(100, 0, 200), solution.point());
        // 调用同一求解器验证微小正闭速不会产生超过 60 秒的回退预测。
        var bounded = RVP_PredictiveInterceptMath.solve(Vec3.ZERO, new Vec3(0.001, 0, 5),
                new Vec3(100, 0, 0), new Vec3(0, 0, 10));
        assertEquals(1200.0, bounded.timeTicks(), EPSILON);
    }

    @Test
    void acceleratedSolutionTracksParabolicTargetWithinFiniteHorizon() {
        Vec3 missileVelocity = new Vec3(5.5, 0, 0);
        Vec3 targetAcceleration = new Vec3(0, -0.08, 0);
        // 调用本项目恒加速度 PIP 解算器，模拟抛物线目标的局部竖直加速度。
        var solution = RVP_PredictiveInterceptMath.solve(Vec3.ZERO, missileVelocity,
                new Vec3(300, 40, 0), new Vec3(0, 0, 2), targetAcceleration);
        assertEquals(RVP_PredictiveInterceptMath.Status.ACCELERATED, solution.status());
        assertTrue(solution.usable());
        Vec3 relative = solution.point();
        assertEquals(missileVelocity.length() * solution.timeTicks(), relative.length(), 1.0E-4);
        assertTrue(solution.point().y < 40.0, "加速度预测点应低于当前抛物线顶端参考点");
        assertTrue(solution.timeTicks() > 0.0 && solution.timeTicks() <= 1200.0);
    }

    @Test
    void rejectsBadInputsAndKeepsCoincidentTargetFinite() {
        // 调用本项目 PIP 覆盖零速、坏数值与重合目标，不允许 NaN 方向进入实体。
        assertFalse(RVP_PredictiveInterceptMath.solve(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO).usable());
        assertFalse(RVP_PredictiveInterceptMath.solve(Vec3.ZERO, new Vec3(1, 0, 0),
                new Vec3(Double.NaN, 0, 0), Vec3.ZERO).usable());
        var solution = RVP_PredictiveInterceptMath.solve(Vec3.ZERO, new Vec3(1, 0, 0), Vec3.ZERO, Vec3.ZERO);
        assertEquals(RVP_PredictiveInterceptMath.Status.COINCIDENT, solution.status());
        assertEquals(0.0, solution.timeTicks());
    }
}
