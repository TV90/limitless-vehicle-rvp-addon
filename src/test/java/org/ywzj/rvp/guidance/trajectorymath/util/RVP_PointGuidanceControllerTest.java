package org.ywzj.rvp.guidance.trajectorymath.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 静止目标连续交会、过顶消偏和转向能力边界；用运动线段测量误差而非离散位置。 */
class RVP_PointGuidanceControllerTest {
    /** YJ20 当前气动限制，测试不读写载具包资源。 */
    private final RVP_AeroSteeringLimits limits = new RVP_AeroSteeringLimits(
            null, 0.1f, 11, 1.1, 0.0155, 0, true, 60);
    /** 滑翔状态，用于独立验证转向响应。 */
    private final RVP_PropulsionMath.MotorState motor = new RVP_PropulsionMath.MotorState(false, 0, 1500);

    /** 原诊断 200 格/10° 约偏 20 格；改后应在首次交会时收敛至一格内。 */
    @Test void stationaryTargetConvergesAcrossAnglesAndRanges() {
        for (double distance : new double[]{200, 500, 1000}) {
            for (double angle : new double[]{2, 5, 10, 15}) {
                double radians = Math.toRadians(angle);
                Vec3 velocity = new Vec3(Math.sin(radians), 0, Math.cos(radians)).scale(11);
                double miss = firstPassMiss(Vec3.ZERO, velocity, new Vec3(0, 0, distance));
                assertTrue(miss < 1.0, "距离=" + distance + " 偏角=" + angle + " 误差=" + miss);
            }
        }
    }

    /** 高空水平过顶与目标在正上/正下方都不能产生人为朝外速度。 */
    @Test void overheadPointStillCorrectsLateralDrift() {
        Vec3 position = new Vec3(1, 300, 0);
        Vec3 velocity = new Vec3(0.25, -1, 0).normalize().scale(11);
        // 调用共用末段方向，确认真实目标仍在水平纠偏方向上。
        Vec3 direction = RVP_BallisticTrajectoryMath.resolvePresetTerminalDirection(position, velocity, Vec3.ZERO);
        assertTrue(direction.x < 0);
        double miss = firstPassMiss(position, velocity, Vec3.ZERO);
        assertTrue(miss < 1.0, "高空过顶误差=" + miss);
        assertEquals(new Vec3(0, -11, 0), RVP_PointGuidanceController.requestVelocity(
                new Vec3(0, -1, 0), new Vec3(0, -11, 0), Vec3.ZERO));
    }

    /** 零过载不能因精度修复获得气动转向，零速/重合目标返回有限结果。 */
    @Test void physicalLimitsAndDegenerateInputsRemainSafe() {
        var zeroG = new RVP_AeroSteeringLimits(0.0, 0.1f, 11, 1.1, 0, 0, true, 60);
        Vec3 velocity = new Vec3(0, 0, 11);
        // 调用真实控制器确认零 G 时速度不会被直接旋转。
        var result = RVP_PointGuidanceController.solve(Vec3.ZERO, velocity, new Vec3(100, 0, 200),
                RVP_AttackAngleState.fromDirection(velocity, 0), zeroG, motor, 1500);
        assertEquals(velocity, result.velocity());
        assertEquals(Vec3.ZERO, RVP_PointGuidanceController.requestVelocity(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO));
    }

    /** 跨刻保留姿态/角速度，首次远离时结束，防止第二圈偶遇掩盖首次脱靶。 */
    private double firstPassMiss(Vec3 position, Vec3 velocity, Vec3 target) {
        // 调用真实状态构造器，初始机头与速度对齐。
        var state = RVP_AttackAngleState.fromDirection(velocity, 0);
        double best = Double.POSITIVE_INFINITY;
        for (int tick = 0; tick < 1000; tick++) {
            // 调用实际末段控制器，完整推进机头惯量与气动能力。
            var result = RVP_PointGuidanceController.solve(position, velocity, target, state, limits, motor, 1500);
            state = result.state();
            velocity = result.velocity();
            double fraction = Math.max(0, Math.min(1, target.subtract(position).dot(velocity) / velocity.lengthSqr()));
            double distance = position.add(velocity.scale(fraction)).distanceTo(target);
            if (tick > 1 && distance > best) return best;
            best = Math.min(best, distance);
            position = position.add(velocity);
        }
        return best;
    }
}
