package org.ywzj.rvp.guidance.trajectorymath.virtualguidance;

import com.google.gson.Gson;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_PropulsionMath;
import org.ywzj.rvp.weapon.data.RVP_ProjectileData;
import static org.junit.jupiter.api.Assertions.*;

/** 从垂直冷发射交接开始连续推进；含推力、变质量、第二脉冲、重力、空气和转弯阻力。 */
class RVP_PresetAccuracyFlightTest {
    /** 无世界依赖的生产积分器，不模拟地形与雷达捕获。 */
    private final RVP_RvpTrajectoryIntegrator integrator = new RVP_RvpTrajectoryIntegrator();
    /** 当前 YJ20 推进配置的固定回归样本，不依赖工作区载具包存在，也不写资源。 */
    private final RVP_ProjectileData propulsion = new Gson().fromJson("""
            {"has_rocket_engine":true,"mass":1500,"fuel_mass":450,"thrust":331.1,
             "motor_burn_time":60,"second_pulse":true,"second_pulse_thrust":188.125,
             "second_pulse_burn_time":100}
            """, RVP_ProjectileData.class);

    /** 多射程、多方位和中段蛇形开关下，首次到达目标高度时必须已消除明显水平偏差。 */
    @Test void coldLaunchToStationaryGroundTargetConverges() {
        var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        double worstMiss = 0.0;
        for (double range : new double[]{100, 200, 500, 1000, 2000, 4000}) {
            for (double heading : new double[]{0, 45, 90, 135}) {
                for (double amplitude : new double[]{0, 30}) {
                    double miss = fly(range, heading, amplitude, 64);
                    worstMiss = Math.max(worstMiss, miss);
                    checks.add(() -> assertTrue(miss < 3.0, "射程=" + range + " 方位=" + heading
                            + " 蛇形=" + amplitude + " 落点误差=" + miss));
                }
            }
        }
        System.out.println("PRESET 完整轨迹：48 组，最大首次落点误差=" + worstMiss + " 格");
        assertAll(checks);
    }

    /** 目标高于/低于点火点时也按真实目标高度交会。 */
    @Test void elevatedAndLowerTargetsConverge() {
        for (double targetY : new double[]{20, 140}) {
            assertTrue(fly(1000, 45, 30, targetY) < 3.0, "目标高度=" + targetY);
        }
    }

    /** 返回下降首次穿过目标高度平面的水平误差；没有到达即失败，不接受绕圈后的命中。 */
    private double fly(double range, double heading, double amplitude, double targetY) {
        Vec3 launch = new Vec3(0, 104, 0);
        Vec3 target = new Vec3(Math.sin(Math.toRadians(heading)) * range, targetY,
                Math.cos(Math.toRadians(heading)) * range);
        var preset = new RVP_VirtualPresetGuidance(launch, 750, 60, 24, 24, 0.3, 1.5, 0.002, 0.01, 0.5, amplitude);
        var state = new RVP_VirtualTrajectoryState(launch, new Vec3(0, 2, 0), -90, 0, 2, 0, 20, 6000, -1);
        for (int tick = 21; tick < 6000; tick++) {
            // 调用真实推进质量/推力公式；本样本二脉冲速度门 20 高于极速 11，主燃尽后立即触发。
            var motor = RVP_PropulsionMath.resolveMotorState(propulsion, tick - 20, tick > 80 && tick <= 181);
            double density = state.position().y < 80 ? 1.5 : state.position().y < 200 ? 1.2 : 1.1;
            var parameters = new RVP_VirtualTrajectoryParameters(null, 0.1f, true, 11, 0.0155, 0,
                    density, null, true, false, true, motor.mass(), motor.thrust(), 6000, 20,
                    0.1085, density, -0.005, 0.01f, 11, 60, 0);
            // 调用生产积分器，不在测试内重写转向算法；参数快照已解析本刻二脉冲状态。
            var result = integrator.step(state, new RVP_VirtualGuidanceInput(target, preset), parameters);
            assertFalse(result.invalid());
            var next = result.state();
            if (state.position().y >= targetY && next.position().y <= targetY) {
                double fraction = (state.position().y - targetY) / (state.position().y - next.position().y);
                Vec3 hit = state.position().add(next.position().subtract(state.position()).scale(fraction));
                return hit.subtract(target).multiply(1, 0, 1).length();
            }
            state = next;
        }
        return Double.POSITIVE_INFINITY;
    }
}
