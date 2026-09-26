package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.vehicle.PhysicsEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RVP_AeroSteeringModel} 的纯数学单元测试。 */
class RVP_AeroSteeringModelTest {
    /** 浮点断言统一使用的绝对误差。 */
    private static final double EPSILON = 1.0E-9;

    /** 动压因子应同时响应速度平方与密度倍率，并钳制在约定范围。 */
    @Test
    void dynamicPressureFactorUsesSpeedSquaredDensityAndBounds() {
        RVP_AeroSteeringLimits fullDensity = limits(null, 0.15F, 6.0, 1.0,
                0.002, 0.0, true);
        RVP_AeroSteeringLimits halfDensity = limits(null, 0.15F, 6.0, 0.5,
                0.002, 0.0, true);

        assertEquals(0.25,
                RVP_AeroSteeringModel.resolveDynamicPressureFactor(3.0, fullDensity), EPSILON);
        assertEquals(1.0,
                RVP_AeroSteeringModel.resolveDynamicPressureFactor(12.0, fullDensity), EPSILON);
        assertEquals(0.125,
                RVP_AeroSteeringModel.resolveDynamicPressureFactor(3.0, halfDensity), EPSILON);
        assertEquals(RVP_AeroSteeringModel.MIN_DYNAMIC_PRESSURE_FACTOR,
                RVP_AeroSteeringModel.resolveDynamicPressureFactor(Double.NaN, fullDensity),
                EPSILON);
    }

    /** turningFactor 折算应匹配设计表，并保留 0 与瞬转两个边界。 */
    @Test
    void equivalentGsMatchesTurningFactorDesignPointAndExemptions() {
        double equivalentGs = RVP_AeroSteeringModel.equivalentGsFromTurningFactor(0.15F, 6.0);

        assertEquals(42.7, equivalentGs, 0.1);
        assertEquals(0.0,
                RVP_AeroSteeringModel.equivalentGsFromTurningFactor(0.0F, 6.0), EPSILON);
        assertEquals(Double.POSITIVE_INFINITY,
                RVP_AeroSteeringModel.equivalentGsFromTurningFactor(1.0F, 6.0));
    }

    /** 显式设计 G 值应随动压减载，且优先于 turningFactor 的瞬转配置。 */
    @Test
    void availableGsUsesDynamicPressureAndExplicitValueFirst() {
        RVP_AeroSteeringLimits explicitGs = limits(20.0, 1.0F, 10.0, 0.5,
                0.0, 0.0, true);

        assertEquals(2.5, RVP_AeroSteeringModel.availableGs(5.0, explicitGs), EPSILON);
    }

    /** 可用转角应严格使用弦长法，并在指令过大时报告气动 G 限制。 */
    @Test
    void solveUsesChordTurnLimitAndReportsAeroConstraint() {
        Vec3 current = new Vec3(6.0, 0.0, 0.0);
        RVP_AeroSteeringLimits limits = limits(18.0, 0.15F, 6.0, 1.0,
                0.0, 0.0, true);
        RVP_AeroSteeringSolution solution = RVP_AeroSteeringModel.solve(
                current, new Vec3(0.0, 0.0, 1.0), limits);
        double expectedTurn = 2.0 * Math.asin(18.0 * PhysicsEngine.G / (2.0 * 6.0));

        assertEquals(expectedTurn, solution.availableTurnAngleRadians(), EPSILON);
        assertEquals(expectedTurn, solution.turnAngleRadians(), EPSILON);
        assertEquals(1.0, solution.loadFactor(), EPSILON);
        assertEquals(18.0, solution.availableGs(), EPSILON);
        assertEquals(RVP_AeroSteeringSolution.LimitReason.AERO_G, solution.limitedBy());
        assertEquals(current.length(), solution.velocity().length(), EPSILON);
    }

    /** 小转角指令不应标记为受限，载荷与诱导阻力应按 λ² 成比例下降。 */
    @Test
    void solveComputesPartialLoadAndSquaredInducedDrag() {
        Vec3 current = new Vec3(2.0, 0.0, 0.0);
        RVP_AeroSteeringLimits limits = limits(40.0, 0.15F, 2.0, 1.0,
                0.01, 0.0, true);
        double availableTurn = 2.0 * Math.asin(40.0 * PhysicsEngine.G / 4.0);
        double commandedTurn = availableTurn * 0.5;
        Vec3 desired = new Vec3(Math.cos(commandedTurn), 0.0, Math.sin(commandedTurn));

        RVP_AeroSteeringSolution solution = RVP_AeroSteeringModel.solve(
                current, desired, limits);

        assertEquals(commandedTurn, solution.turnAngleRadians(), EPSILON);
        assertEquals(0.5, solution.loadFactor(), EPSILON);
        assertEquals(0.01 * 0.25 * current.length(), solution.inducedDragLoss(), EPSILON);
        assertEquals(RVP_AeroSteeringSolution.LimitReason.NONE, solution.limitedBy());
        assertEquals(current.length(), solution.velocity().length(), EPSILON);
    }

    /** 诱导阻力公式应覆盖满载、零载与非法输入边界。 */
    @Test
    void inducedDragLossUsesSquaredClampedLoadFactor() {
        assertEquals(0.02,
                RVP_AeroSteeringModel.inducedDragLoss(0.002, 1.0, 10.0), EPSILON);
        assertEquals(0.0,
                RVP_AeroSteeringModel.inducedDragLoss(0.002, 0.0, 10.0), EPSILON);
        assertEquals(0.02,
                RVP_AeroSteeringModel.inducedDragLoss(0.002, 2.0, 10.0), EPSILON);
        assertEquals(0.0,
                RVP_AeroSteeringModel.inducedDragLoss(Double.NaN, 1.0, 10.0), EPSILON);
    }

    /** turningFactor=1 必须保持旧版瞬转语义，并豁免载荷与诱导阻力。 */
    @Test
    void fullTurningFactorRemainsInstantTurnExemption() {
        Vec3 current = new Vec3(6.0, 0.0, 0.0);
        RVP_AeroSteeringSolution solution = RVP_AeroSteeringModel.solve(
                current, new Vec3(0.0, 0.0, 1.0),
                limits(null, 1.0F, 6.0, 0.1, 0.5, 1.0, true));

        assertEquals(0.0, solution.velocity().x, EPSILON);
        assertEquals(6.0, solution.velocity().z, EPSILON);
        assertEquals(Double.POSITIVE_INFINITY, solution.availableGs());
        assertEquals(0.0, solution.loadFactor(), EPSILON);
        assertEquals(0.0, solution.inducedDragLoss(), EPSILON);
        assertEquals(RVP_AeroSteeringSolution.LimitReason.NONE, solution.limitedBy());
    }

    /** 关闭气动模型时，显式 G 值路径必须与现有 applySteering 逐分量一致。 */
    @Test
    void disabledModelExactlyMatchesLegacyMaxGSteering() {
        Vec3 current = new Vec3(10.0, 0.0, 0.0);
        Vec3 desired = new Vec3(0.0, 0.0, 1.0);
        Vec3 legacy = RVP_BallisticTrajectoryMath.applySteering(current, desired, 18.0);
        RVP_AeroSteeringSolution solution = RVP_AeroSteeringModel.solve(
                current, desired, limits(18.0, 1.0F, 6.0, 0.1, 0.5, 1.0, false));

        assertEquals(legacy.x, solution.velocity().x, 0.0);
        assertEquals(legacy.y, solution.velocity().y, 0.0);
        assertEquals(legacy.z, solution.velocity().z, 0.0);
        assertEquals(0.0, solution.loadFactor(), EPSILON);
        assertEquals(0.0, solution.inducedDragLoss(), EPSILON);
        assertEquals(RVP_AeroSteeringSolution.LimitReason.DISABLED, solution.limitedBy());
    }

    /** 关闭气动模型且无显式 G 值时，必须与旧版 turningFactor 插值逐分量一致。 */
    @Test
    void disabledModelExactlyMatchesLegacyTurningFactorSteering() {
        Vec3 current = new Vec3(10.0, 0.0, 0.0);
        Vec3 desired = new Vec3(0.0, 0.0, 1.0);
        Vec3 legacy = RVP_TrajectorySteeringMath.applyTurningFactor(
                current, desired, current.length(), 0.25F);
        RVP_AeroSteeringSolution solution = RVP_AeroSteeringModel.solve(
                current, desired, limits(null, 0.25F, 6.0, 1.0, 0.5, 0.0, false));

        assertEquals(legacy.x, solution.velocity().x, 0.0);
        assertEquals(legacy.y, solution.velocity().y, 0.0);
        assertEquals(legacy.z, solution.velocity().z, 0.0);
    }

    /** 绝对转角上限比 G 值更紧时，应成为报告的限制来源。 */
    @Test
    void solveReportsTurnRateLimitWhenItIsTighter() {
        RVP_AeroSteeringSolution solution = RVP_AeroSteeringModel.solve(
                new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 0.0, 1.0),
                limits(100.0, 0.15F, 1.0, 1.0, 0.0, 10.0, true));

        assertEquals(Math.toRadians(10.0), solution.turnAngleRadians(), EPSILON);
        assertEquals(Math.toRadians(10.0), solution.availableTurnAngleRadians(), EPSILON);
        assertEquals(RVP_AeroSteeringSolution.LimitReason.TURN_RATE, solution.limitedBy());
    }

    /** 零速、零 G 与完全反向三个边界均应返回有限且符合限制的结果。 */
    @Test
    void solveHandlesZeroSpeedZeroGsAndOppositeDirection() {
        RVP_AeroSteeringLimits limits = limits(18.0, 0.15F, 2.0, 1.0,
                0.002, 0.0, true);
        RVP_AeroSteeringSolution zeroSpeed = RVP_AeroSteeringModel.solve(
                Vec3.ZERO, new Vec3(0.0, 0.0, 1.0), limits);
        RVP_AeroSteeringSolution zeroGs = RVP_AeroSteeringModel.solve(
                new Vec3(2.0, 0.0, 0.0), new Vec3(0.0, 0.0, 1.0),
                limits(0.0, 0.15F, 2.0, 1.0, 0.002, 0.0, true));
        RVP_AeroSteeringSolution opposite = RVP_AeroSteeringModel.solve(
                new Vec3(2.0, 0.0, 0.0), new Vec3(-1.0, 0.0, 0.0), limits);

        assertSame(Vec3.ZERO, zeroSpeed.velocity());
        assertEquals(0.0, zeroSpeed.loadFactor(), EPSILON);
        assertEquals(new Vec3(2.0, 0.0, 0.0), zeroGs.velocity());
        assertEquals(RVP_AeroSteeringSolution.LimitReason.AERO_G, zeroGs.limitedBy());
        assertTrue(Double.isFinite(opposite.velocity().x));
        assertTrue(Double.isFinite(opposite.velocity().y));
        assertTrue(Double.isFinite(opposite.velocity().z));
        assertFalse(opposite.velocity().equals(new Vec3(-2.0, 0.0, 0.0)));
    }

    /** 创建测试使用的完整限制快照，避免各用例遗漏与结论无关的字段。 */
    private static RVP_AeroSteeringLimits limits(Double rvpMaxGs, float turningFactor,
                                                  double referenceSpeed, double densityFactor,
                                                  double inducedDrag,
                                                  double turnRateLimitDegPerTick,
                                                  boolean enabled) {
        return new RVP_AeroSteeringLimits(
                rvpMaxGs, turningFactor, referenceSpeed, densityFactor,
                inducedDrag, turnRateLimitDegPerTick, enabled);
    }
}
