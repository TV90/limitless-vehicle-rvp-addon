package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 独立核对视线角速度的方向、单位、增益，以及真实机头执行器下的连续闭环。 */
class RVP_ProportionalNavigationMathTest {
    /** 向量、速率与解析角度的比较容差。 */
    private static final double EPSILON = 1.0E-9;

    @Test
    void crossingCommandMatchesAnalyticWorldRateAndNormalAcceleration() {
        // 此几何的视线绕 -Y 轴以 0.01 弧度/刻转动，3 倍 PPN 要求 +Z 方向 0.15 格/刻²。
        var result = RVP_ProportionalNavigationMath.solve(Vec3.ZERO, new Vec3(5, 0, 0),
                new Vec3(100, 0, 0), new Vec3(0, 0, 1), 3);
        assertVector(new Vec3(0, -0.01, 0), result.lineOfSightRate());
        assertVector(new Vec3(0, 0, 0.15), result.acceleration());
        assertEquals(5, result.closingSpeed(), EPSILON);
        assertVector(new Vec3(5 * Math.cos(0.03), 0, 5 * Math.sin(0.03)), result.requestedVelocity());
        assertEquals(0, result.acceleration().dot(new Vec3(5, 0, 0)), EPSILON);
        assertEquals(RVP_ProportionalNavigationMath.Status.ACTIVE, result.status());
    }

    @Test
    void constantBearingCollisionCourseDoesNotChaseTheCurrentTargetPosition() {
        Vec3 velocity = new Vec3(5, 0, 0);
        // 目标位于侧前 45 度，但相对速度已经沿视线接近；PPN 应保持相撞航迹。
        var result = RVP_ProportionalNavigationMath.solve(Vec3.ZERO, velocity,
                new Vec3(100, 0, 100), new Vec3(0, 0, -5), 3);
        assertVector(Vec3.ZERO, result.lineOfSightRate());
        assertVector(Vec3.ZERO, result.acceleration());
        assertEquals(velocity, result.requestedVelocity());
        assertTrue(result.closingSpeed() > 0);
    }

    @Test
    void gainChangesTheTurnRequestWithoutChangingSpeed() {
        for (double gain : new double[]{0, 1, 3, 5}) {
            var result = RVP_ProportionalNavigationMath.solve(Vec3.ZERO, new Vec3(5, 0, 0),
                    new Vec3(100, 0, 0), new Vec3(0, 0, 1), gain);
            assertEquals(0.05 * gain, result.acceleration().z, EPSILON);
            assertEquals(0.01 * gain, Math.atan2(result.requestedVelocity().z, result.requestedVelocity().x), EPSILON);
            assertEquals(5, result.requestedVelocity().length(), EPSILON);
        }
    }

    @Test
    void mirrorsAndVerticalGeometryKeepTheCorrectWorldTurnDirection() {
        for (double sign : new double[]{-1, 1}) {
            var horizontal = RVP_ProportionalNavigationMath.solve(Vec3.ZERO, new Vec3(0, 0, 5),
                    new Vec3(0, 0, 100), new Vec3(sign, 0, 0), 3);
            var vertical = RVP_ProportionalNavigationMath.solve(Vec3.ZERO, new Vec3(0, 5, 0),
                    new Vec3(0, 100, 0), new Vec3(sign, 0, 0), 3);
            assertEquals(sign * 0.15, horizontal.acceleration().x, EPSILON);
            assertEquals(horizontal.requestedVelocity().x, vertical.requestedVelocity().x, EPSILON);
            assertEquals(horizontal.requestedVelocity().z, vertical.requestedVelocity().y, EPSILON);
            assertEquals(0, vertical.requestedVelocity().z, EPSILON);
        }
    }

    @Test
    void unsafeAndDegenerateInputsRemainFiniteAndNeverInventSpeed() {
        Vec3 velocity = new Vec3(0, 0, 5);
        for (Vec3 bad : new Vec3[]{null, new Vec3(Double.NaN, 0, 0), new Vec3(Double.POSITIVE_INFINITY, 0, 0)}) {
            var result = RVP_ProportionalNavigationMath.solve(bad, velocity, new Vec3(100, 0, 0), Vec3.ZERO, 3);
            assertEquals(RVP_ProportionalNavigationMath.Status.INVALID, result.status());
            assertEquals(velocity, result.requestedVelocity());
        }
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(velocity, RVP_ProportionalNavigationMath.solve(Vec3.ZERO, velocity,
                    new Vec3(100, 0, 0), Vec3.ZERO, bad).requestedVelocity());
        }
        assertEquals(RVP_ProportionalNavigationMath.Status.COINCIDENT,
                RVP_ProportionalNavigationMath.solve(Vec3.ZERO, velocity, Vec3.ZERO, Vec3.ZERO, 3).status());
        assertEquals(Vec3.ZERO, RVP_ProportionalNavigationMath.solve(Vec3.ZERO, Vec3.ZERO,
                new Vec3(100, 0, 0), Vec3.ZERO, 3).requestedVelocity());
        var large = RVP_ProportionalNavigationMath.solve(Vec3.ZERO, velocity,
                new Vec3(0, 0, 0.001), new Vec3(1000, 0, 0), 1000);
        assertEquals(5, large.requestedVelocity().length(), EPSILON);
        assertTrue(large.requestedVelocity().x > 0, "饱和请求不能绕一圈后反向修正");
    }

    @Test
    void openingMotionCanStillGuideAndOnlyTheExactRearSingularityUsesCapture() {
        Vec3 velocity = new Vec3(0, 0, 1);
        var opening = RVP_ProportionalNavigationMath.solve(Vec3.ZERO, velocity,
                new Vec3(0, 0, 100), new Vec3(1, 0, 2), 3);
        assertTrue(opening.closingSpeed() < 0);
        assertTrue(opening.requestedVelocity().x > 0, "低速发射仍可建立航迹，不能因尚未追上目标就停导");
        assertEquals(RVP_ProportionalNavigationMath.Status.CAPTURE,
                RVP_ProportionalNavigationMath.solve(Vec3.ZERO, velocity, new Vec3(0, 0, -100), Vec3.ZERO, 3).status());
        assertEquals(velocity, RVP_ProportionalNavigationMath.solve(Vec3.ZERO, velocity,
                new Vec3(0, 0, -100), Vec3.ZERO, 0).requestedVelocity());
        assertEquals(RVP_ProportionalNavigationMath.Status.PASSED_TARGET,
                RVP_ProportionalNavigationMath.solve(Vec3.ZERO, velocity, new Vec3(0, 0, -0.1), Vec3.ZERO, 3).status());
    }

    @Test
    void loftHandoffHasExactEndpointsAndContinuousUnitDirectionAtEveryWeight() {
        Vec3 velocity = new Vec3(0, 0, 5);
        Vec3 navigation = new Vec3(1, 0, 5).normalize().scale(5);
        Vec3 route = new Vec3(0, 2, 5).normalize().scale(5);
        assertVector(navigation, RVP_ProportionalNavigationMath.blendRouteRequest(velocity, navigation, route, 0));
        assertVector(route, RVP_ProportionalNavigationMath.blendRouteRequest(velocity, navigation, route, 1));
        Vec3 previous = navigation;
        for (int sample = 1; sample <= 100; sample++) {
            var next = RVP_ProportionalNavigationMath.blendRouteRequest(velocity, navigation, route, sample / 100.0);
            assertEquals(5, next.length(), EPSILON);
            // 调用共用夹角工具；若忘记把速度归一化，球面插值会在这里退化为不动或错误速率。
            assertTrue(RVP_BallisticTrajectoryMath.angleBetween(previous, next) < 0.01);
            assertTrue(next.y > previous.y);
            previous = next;
        }
    }

    @Test
    void continuousPPNWithRealInertiaRespectsBudgetAndFirstEncounter() {
        for (double density : new double[]{1.0, 0.5, 0.25}) {
            Vec3 position = Vec3.ZERO, velocity = new Vec3(5.5, 0, 0);
            Vec3 target = new Vec3(600, 0, 0), targetVelocity = new Vec3(0, 0, 2);
            var state = RVP_AttackAngleState.fromDirection(velocity, 0);
            var limits = new RVP_AeroSteeringLimits(null, 0.1f, 5.5, density, 0.003, 0, true, 60);
            double best = 600;
            int closest = 0;
            for (int tick = 0; tick < 300; tick++) {
                var command = RVP_ProportionalNavigationMath.solve(position, velocity, target, targetVelocity, 3);
                // 调用正式航迹请求反解与攻角模型，保留真实双轴惯量，绝不直接写 PPN 理想速度。
                var controlled = RVP_PIPAttackAngleController.solveVelocityRequest(velocity, state,
                        command.requestedVelocity(), limits, null, 100);
                assertEquals(velocity.length(), controlled.steering().velocity().length(), EPSILON);
                assertTrue(controlled.steering().loadFactor() <= 1.0);
                Vec3 relative = position.subtract(target);
                velocity = controlled.steering().velocity(); state = controlled.steering().state();
                position = position.add(velocity); target = target.add(targetVelocity);
                double miss = segmentMiss(relative, position.subtract(target));
                if (miss > best + 1.0E-6 && closest > 1) break;
                if (miss < best) { best = miss; closest = tick; }
            }
            assertTrue(best < 0.5, "同一 G/惯量预算下首次交会应小于半格，密度=" + density + "，误差=" + best);
        }
    }

    /** 独立计算相对运动段的最近距离，避免只比较每刻端点而漏掉交会。 */
    private static double segmentMiss(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double fraction = delta.lengthSqr() <= 1.0E-16 ? 0
                : Math.max(0, Math.min(1, -from.dot(delta) / delta.lengthSqr()));
        return from.add(delta.scale(fraction)).length();
    }

    /** 向量逐分量比较，容差不得用物理引信半径替代。 */
    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }
}
