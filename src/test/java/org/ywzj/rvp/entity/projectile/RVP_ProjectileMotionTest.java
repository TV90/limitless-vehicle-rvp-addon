package org.ywzj.rvp.entity.projectile;

import com.google.gson.Gson;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.data.RVP_ProjectileData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ProjectileMotionTest {

    /** PIP 试算不能提前点火，且须与运动阶段采用相同主燃烧/二脉冲闭区间。 */
    @Test
    void pipMotorSnapshotMatchesIgnitionFuelAndSecondPulseBoundaries() {
        var data = new Gson().fromJson("""
                {"has_rocket_engine":true,"mass":100,"fuel_mass":20,
                  "thrust":10,"motor_burn_time":10,"ignition_delay_tick":5,
                  "second_pulse":true,"second_pulse_thrust":4,"second_pulse_burn_time":5,
                  "second_pulse_trigger_speed":3}
                """, RVP_ProjectileData.class);
        // 调用本项目纯时序快照，冷发射 8 Tick 覆盖配置点火延迟 5 Tick。
        assertFalse(RVP_ProjectileMotion.resolvePIPMotorState(data, 7, 8, -1, true).burning());
        var ignition = RVP_ProjectileMotion.resolvePIPMotorState(data, 8, 8, -1, true);
        assertTrue(ignition.burning());
        assertEquals(100.0, ignition.mass());
        // 调用同一入口验证燃料消耗及燃烧结束端点仍有主推力。
        var lastPrimary = RVP_ProjectileMotion.resolvePIPMotorState(data, 18, 8, -1, true);
        assertEquals(10.0, lastPrimary.thrust());
        assertEquals(80.0, lastPrimary.mass());
        assertFalse(RVP_ProjectileMotion.resolvePIPMotorState(data, 19, 8, -1, true).burning());
        // 调用同一入口验证尚未开始、正在燃烧与刚结束的二脉冲都不会被试算篡改。
        assertFalse(RVP_ProjectileMotion.resolvePIPMotorState(data, 24, 8, 25, true).burning());
        assertEquals(4.0, RVP_ProjectileMotion.resolvePIPMotorState(data, 25, 8, 25, true).thrust());
        assertEquals(4.0, RVP_ProjectileMotion.resolvePIPMotorState(data, 30, 8, 25, true).thrust());
        assertFalse(RVP_ProjectileMotion.resolvePIPMotorState(data, 31, 8, 25, true).burning());
    }

    /** 冷发射覆盖点火延迟时，当前 Tick 的 PRESET 制导速度必须完成接管。 */
    @Test
    void overlappingColdLaunchAndIgnitionKeepsPresetGuidance() {
        assertTrue(RVP_ProjectileMotion.shouldPreserveGuidanceDuringColdLaunch(
                15, 20, 20, true));
    }

    /** 冷发射已早于点火结束时，继续沿用原有冷发射运动，保持伊斯坎德尔行为不变。 */
    @Test
    void shorterColdLaunchKeepsOriginalPreIgnitionMotion() {
        assertFalse(RVP_ProjectileMotion.shouldPreserveGuidanceDuringColdLaunch(
                5, 10, 20, true));
    }

    /** 没有成功写入 PRESET 制导速度时，不得误触发冷发射接管。 */
    @Test
    void missingGuidanceDoesNotTriggerHandoff() {
        assertFalse(RVP_ProjectileMotion.shouldPreserveGuidanceDuringColdLaunch(
                15, 20, 20, false));
    }

    /** 冷发射结束后的 Tick 不再走冷发射制导接管分支。 */
    @Test
    void completedColdLaunchDoesNotTriggerHandoff() {
        assertFalse(RVP_ProjectileMotion.shouldPreserveGuidanceDuringColdLaunch(
                20, 20, 20, true));
    }

    /** 冷发射局部轴必须转换为世界单位轴，供攻角机头初始化而不是继承初始瞄准方向。 */
    @Test
    void coldLaunchDirectionUsesVehicleAxes() {
        var axes = new Vector3f[]{
                new Vector3f(1, 0, 0),
                new Vector3f(0, 0, 1),
                new Vector3f(0, 1, 0)
        };
        Vec3 direction = RVP_ProjectileMotion.resolveColdLaunchDirection(
                new Vec3(0, 2, 0), axes);

        assertEquals(0.0, direction.x, 1.0E-9);
        assertEquals(0.0, direction.y, 1.0E-9);
        assertEquals(1.0, direction.z, 1.0E-9);
    }

    /** 空射冷发射的有效速度必须同时包含发射瞬间载机速度和本地冷发射分离速度。 */
    @Test
    void effectiveColdLaunchVelocityIncludesLaunchVehicleSnapshot() {
        var axes = new Vector3f[]{
                new Vector3f(1, 0, 0),
                new Vector3f(0, 0, 1),
                new Vector3f(0, 1, 0)
        };
        Vec3 velocity = RVP_ProjectileMotion.resolveEffectiveColdLaunchVelocity(
                new Vec3(4, 0, 0), new Vec3(0, 2, 0), axes);

        assertEquals(4.0, velocity.x, 1.0E-9);
        assertEquals(0.0, velocity.y, 1.0E-9);
        assertEquals(2.0, velocity.z, 1.0E-9);
    }

    /** 冷发射弹在点火前锁定姿态，点火边界 Tick 必须立即解除锁定。 */
    @Test
    void preIgnitionAttitudeLockUsesColdLaunchAndIgnitionBoundary() {
        assertTrue(RVP_ProjectileMotion.isPreIgnitionAttitudeLocked(0, 10, 5));
        assertTrue(RVP_ProjectileMotion.isPreIgnitionAttitudeLocked(9, 10, 5));
        assertFalse(RVP_ProjectileMotion.isPreIgnitionAttitudeLocked(10, 10, 5));
    }

    /** 配置点火延迟长于冷发射时，姿态锁定应覆盖到实际点火 Tick。 */
    @Test
    void preIgnitionAttitudeLockUsesLaterIgnitionBoundary() {
        assertTrue(RVP_ProjectileMotion.isPreIgnitionAttitudeLocked(19, 10, 20));
        assertFalse(RVP_ProjectileMotion.isPreIgnitionAttitudeLocked(20, 10, 20));
    }

    /** 没有冷发射窗口的普通延迟点火弹不应被新增的姿态锁定逻辑影响。 */
    @Test
    void delayedIgnitionWithoutColdLaunchDoesNotLockAttitude() {
        assertFalse(RVP_ProjectileMotion.isPreIgnitionAttitudeLocked(0, 0, 20));
    }
}
