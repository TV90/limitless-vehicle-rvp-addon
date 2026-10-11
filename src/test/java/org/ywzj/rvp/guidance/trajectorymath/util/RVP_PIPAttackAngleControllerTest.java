package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 直接使用 Java PIP 与攻角工具的连续交会回归，避免只验证单 Tick 公式。 */
class RVP_PIPAttackAngleControllerTest {
    /** 诊断场景弹速，单位格/Tick。 */
    private static final double SPEED = 5.5;
    /** 比较向量和速率的浮点容差。 */
    private static final double EPSILON = 1.0E-6;
    /** 隔离对照的三种控制链。 */
    private enum Mode {
        /** 攻角前的航迹插值加气动裁决。 */
        BASELINE,
        /** 把 PIP 航向直接当机头的回归路径。 */
        RAW,
        /** 本次航迹到攻角适配。 */
        CONTROLLED
    }

    /** 冻结当前常见参数，仅按试验高度改变密度。 */
    private static RVP_AeroSteeringLimits limits(double density) {
        return new RVP_AeroSteeringLimits(null, 0.1f, SPEED, density, 0.003, 0.0, true, 60.0);
    }

    @Test
    void reproducesRawPIPToBodyRegressionWithActualJavaModels() {
        // 调用本测试连续交会工具，验证审阅方案中的退化可以由真实 Java 数学层复现。
        assertEquals(0.2319, crossingMiss(Mode.BASELINE, 0.5, 300), 0.01);
        assertEquals(14.2993, crossingMiss(Mode.RAW, 0.5, 300), 0.02);
    }

    @Test
    void restoresReachableCrossingCasesWithoutIncreasingGBudget() {
        for (double[] scene : new double[][]{{1.0, 300}, {0.5, 300}, {0.25, 600}, {0.25, 1000}}) {
            // 调用连续交会工具对比同 G 预算，修复后应恢复到旧组脱靶量级。
            double baseline = crossingMiss(Mode.BASELINE, scene[0], scene[1]);
            double fixed = crossingMiss(Mode.CONTROLLED, scene[0], scene[1]);
            System.out.printf("PIP density=%.2f distance=%.0f baseline=%.6f controlled=%.6f%n",
                    scene[0], scene[1], baseline, fixed);
            assertTrue(fixed <= baseline + 0.05, "首次交会脱靶量=" + fixed);
        }
    }

    @Test
    void compensatesThrustInsteadOfAddingNormalCorrectionTwice() {
        Vec3 velocity = new Vec3(SPEED, 0, 0);
        Vec3 desired = new Vec3(Math.cos(Math.toRadians(10)), 0, Math.sin(Math.toRadians(10)));
        for (double thrust : new double[]{0.0, 0.1, 0.4}) {
            var motor = new RVP_PropulsionMath.MotorState(thrust > 0, thrust, 1.0);
            // 调用控制器和真实推进工具，验证含推力的最终方向而非只验证气动半步。
            var result = RVP_PIPAttackAngleController.solve(velocity, velocity, desired, limits(0.5), motor);
            Vec3 actual = RVP_PropulsionMath.applyThrust(
                    result.steering().velocity(), result.steering().bodyDirection(), motor, 1.0);
            assertFalse(result.responseLimited());
            assertTrue(RVP_BallisticTrajectoryMath.angleBetween(result.requestedVelocity(), actual) < 1.0E-3);
            assertEquals(SPEED, result.steering().velocity().length(), EPSILON);
        }
    }

    @Test
    void respectsGTurnRateAndBodyReachabilityInThreeDimensions() {
        Vec3 velocity = new Vec3(1, 2, 3).normalize().scale(SPEED);
        Vec3 body = new Vec3(-2, 1, 0).normalize();
        var limits = new RVP_AeroSteeringLimits(8.0, 0.1f, SPEED, 0.25, 0.003, 0.2, true, 60.0);
        for (int tick = 0; tick < 80; tick++) {
            Vec3 desired = tick < 40 ? velocity.reverse() : new Vec3(3, -2, 1);
            // 调用本项目控制器，以离面残余姿态和反向指令覆盖三维饱和及反转。
            var result = RVP_PIPAttackAngleController.solve(velocity, body, desired, limits, null);
            var steering = result.steering();
            assertTrue(RVP_BallisticTrajectoryMath.isFinite(steering.bodyDirection()));
            assertTrue(steering.attackAngleRadians() <= Math.toRadians(60.0) + EPSILON);
            assertTrue(steering.turnAngleRadians() <= Math.toRadians(0.2) + EPSILON);
            assertTrue(steering.velocity().subtract(velocity).length() <= 2.0 * 0.0245 + EPSILON);
            assertEquals(SPEED, steering.velocity().length(), EPSILON);
            velocity = steering.velocity();
            body = steering.bodyDirection();
        }
    }

    @Test
    void zeroGZeroSpeedAndInvalidCommandsStayFinite() {
        Vec3 velocity = new Vec3(SPEED, 0, 0);
        var zeroG = new RVP_AeroSteeringLimits(0.0, 0.1f, SPEED, 1, 0, 0, true, 60);
        // 调用本项目控制器验证显式 0 G 不凭空产生气动转向。
        assertEquals(velocity, RVP_PIPAttackAngleController.solve(
                velocity, velocity, new Vec3(0, 1, 0), zeroG, null).steering().velocity());
        // 调用同一入口验证坏输入走原模型的安全退化。
        assertEquals(Vec3.ZERO, RVP_PIPAttackAngleController.solve(
                Vec3.ZERO, velocity, velocity, limits(1), null).steering().velocity());
        assertEquals(velocity, RVP_PIPAttackAngleController.solve(
                velocity, velocity, new Vec3(Double.NaN, 0, 0), limits(1), null).steering().velocity());
    }

    @Test
    void tracksMovingTargetsAcrossIgnitionCoastAndSecondPulseWithoutBreakingLimits() {
        // 四档高度密度、四档距离及静止/横穿/迎头/尾追/转弯/反转，验证动力切换后的有界性。
        for (double density : new double[]{1.5, 0.5, 0.25, 0.014}) {
            for (double distance : new double[]{100, 300, 600, 1000}) {
                for (int behavior = 0; behavior < 6; behavior++) {
                    Vec3 position = Vec3.ZERO;
                    Vec3 velocity = new Vec3(1.5, 0, 0);
                    Vec3 body = velocity.normalize();
                    Vec3 target = new Vec3(distance, 20, 0);
                    var limits = limits(density);
                    for (int tick = 0; tick < 200; tick++) {
                        Vec3 targetVelocity = switch (behavior) {
                            case 0 -> Vec3.ZERO;
                            case 1 -> new Vec3(0, 0, 2);
                            case 2 -> new Vec3(-2, 0, 0);
                            case 3 -> new Vec3(2, 0, 0);
                            case 4 -> new Vec3(2 * Math.sin(tick * 0.015), 0, 2 * Math.cos(tick * 0.015));
                            default -> new Vec3(0, 0, tick < 80 ? 2 : -2);
                        };
                        // 调用真实 PIP 解算；目标不可达时与运行时一样退回普通攻角追踪。
                        var intercept = RVP_PredictiveInterceptMath.solve(position, velocity, target, targetVelocity);
                        Vec3 desired = (intercept.usable() ? intercept.point() : target).subtract(position);
                        double thrust = tick < 10 ? 0 : tick <= 70 ? 0.1 : tick < 120 ? 0 : tick <= 150 ? 0.05 : 0;
                        var motor = new RVP_PropulsionMath.MotorState(thrust > 0, thrust, 1.0);
                        // 调用控制器或原回退执行器；各动力阶段都必须遵守同一 G 和攻角约束。
                        var steering = intercept.usable()
                                ? RVP_PIPAttackAngleController.solve(velocity, body, desired, limits, motor).steering()
                                : RVP_AttackAngleModel.solve(velocity, body, desired, limits);
                        assertEquals(velocity.length(), steering.velocity().length(), EPSILON);
                        assertTrue(steering.velocity().subtract(velocity).length()
                                <= RVP_AeroSteeringModel.availableGs(velocity.length(), limits) * 0.0245 + EPSILON);
                        assertTrue(steering.attackAngleRadians() <= Math.toRadians(60) + EPSILON);
                        body = steering.bodyDirection();
                        // 调用真实推进、速率钳制和诱导阻力公式，每 Tick 仅结算一次。
                        velocity = RVP_PropulsionMath.applyThrust(steering.velocity(), body, motor, 1.0);
                        velocity = RVP_BallisticTrajectoryMath.clampSpeed(velocity, 0, (float) SPEED);
                        double loss = RVP_AeroSteeringModel.inducedDragLoss(limits.inducedDrag(), steering.loadFactor(), velocity.length());
                        velocity = velocity.normalize().scale(Math.max(velocity.length() - loss, 0.01));
                        assertTrue(RVP_BallisticTrajectoryMath.isFinite(velocity));
                        position = position.add(velocity);
                        target = target.add(targetVelocity);
                    }
                }
            }
        }
    }

    @Test
    void explicitGOwnsRequestRegardlessOfTurningFactor() {
        Vec3 velocity = new Vec3(SPEED, 0, 0);
        Vec3 desired = new Vec3(1, 0.2, 0.3);
        var zeroFactor = new RVP_AeroSteeringLimits(20.0, 0, SPEED, 0.5, 0, 1, true, 60);
        var fullFactor = new RVP_AeroSteeringLimits(20.0, 1, SPEED, 0.5, 0, 1, true, 60);
        // 调用控制器验证显式 G 的响应仍完全独立于 factor；0 和 1 两端不能改变结果。
        assertEquals(RVP_PIPAttackAngleController.solve(velocity, velocity, desired, zeroFactor, null),
                RVP_PIPAttackAngleController.solve(velocity, velocity, desired, fullFactor, null));
    }

    /** 恒速、共面、质点目标；以首次相对运动线段交会为准，不允许绕圈重逢掩盖脱靶。 */
    private static double crossingMiss(Mode mode, double density, double distance) {
        Vec3 position = Vec3.ZERO;
        Vec3 velocity = new Vec3(SPEED, 0, 0);
        Vec3 body = velocity.normalize();
        Vec3 target = new Vec3(distance, 0, 0);
        Vec3 targetVelocity = new Vec3(0, 0, 2);
        var limits = limits(density);
        double best = Double.POSITIVE_INFINITY;
        for (int tick = 0; tick < 500; tick++) {
            // 调用本项目 PIP 求解当前预测点，两组共享完全相同的解算。
            var intercept = RVP_PredictiveInterceptMath.solve(position, velocity, target, targetVelocity);
            assertTrue(intercept.usable());
            Vec3 desired = intercept.point().subtract(position);
            if (mode == Mode.CONTROLLED) {
                // 调用本次控制器，提交其唯一实际攻角结果；不再次执行方向插值。
                var result = RVP_PIPAttackAngleController.solve(velocity, body, desired, limits, null);
                assertTrue(result.steering().velocity().subtract(velocity).length()
                        <= RVP_AeroSteeringModel.availableGs(SPEED, limits) * 0.0245 + EPSILON);
                velocity = result.steering().velocity();
                body = result.steering().bodyDirection();
            } else if (mode == Mode.RAW) {
                // 调用现有攻角模型，复现把期望速度方向直接当机头方向的路径。
                var next = RVP_AttackAngleModel.solve(velocity, body, desired, limits);
                velocity = next.velocity();
                body = next.bodyDirection();
            } else {
                // 调用本项目旧航迹插值和气动裁决，预算与攻角组一致。
                Vec3 request = RVP_TrajectorySteeringMath.applyTurningFactor(
                        velocity, desired, velocity.length(), limits.turningFactor());
                velocity = RVP_AeroSteeringModel.solve(velocity, request, limits).velocity();
                body = velocity.normalize();
            }
            Vec3 relative = target.subtract(position);
            Vec3 relativeStep = targetVelocity.subtract(velocity);
            double fraction = Math.max(0.0, Math.min(1.0,
                    -relative.dot(relativeStep) / relativeStep.lengthSqr()));
            double miss = relative.add(relativeStep.scale(fraction)).length();
            if (miss >= best && tick > 1) return best;
            best = Math.min(best, miss);
            position = position.add(velocity);
            target = target.add(targetVelocity);
            assertEquals(SPEED, velocity.length(), EPSILON);
        }
        return best;
    }
}
