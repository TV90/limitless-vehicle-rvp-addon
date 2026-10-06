package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RVP_ProjectileData} 气动转向新增字段与默认解析规则测试。 */
class RVP_ProjectileDataAeroSteeringTest {
    /** JSON 反序列化器，仅用于验证当前 schema 字段。 */
    private final Gson gson = new Gson();

    /** 攻角字段缺省、null、非正值、90 度及非有限值均不应自动启用。 */
    @Test
    void attackAngleDefaultsAndInvalidValuesRemainOff() {
        assertEquals(0.0, new RVP_ProjectileData().getRvpAttackAngleLimitDeg());
        for (String value : new String[]{"null", "0", "-1", "90", "120", "1e309", "\"NaN\""}) {
            var data = gson.fromJson("{\"rvp_aero_steering\":true,\"rvp_attack_angle_limit_deg\":"
                    + value + "}", RVP_ProjectileData.class);
            // 调用本项目解析器验证安全关闭，不能用非法值生成满舵。
            assertEquals(0.0, data.getRvpAttackAngleLimitDeg());
            assertFalse(data.resolveAeroSteeringLimits(3.0, 0.0, 0.5f).attackAngleEnabled());
        }
    }

    /** 攻角需气动总开关；瞬转豁免与显式 G 的优先级也从同一数据快照解析。 */
    @Test
    void attackAngleRequiresAeroAndPreservesInstantTurnExemption() {
        var off = gson.fromJson("{\"rvp_attack_angle_limit_deg\":20}", RVP_ProjectileData.class);
        assertFalse(off.resolveAeroSteeringLimits(3.0, 0.0, 0.5f).attackAngleEnabled());
        var on = gson.fromJson("{\"rvp_aero_steering\":true,\"rvp_attack_angle_limit_deg\":20}", RVP_ProjectileData.class);
        // 调用本项目参数投影，验证仅显式配置的有效值生效。
        assertTrue(on.resolveAeroSteeringLimits(3.0, 0.0, 0.5f).attackAngleEnabled());
        assertEquals(20.0, on.getRvpAttackAngleLimitDeg());
        assertFalse(on.resolveAeroSteeringLimits(3.0, 0.0, 1.0f).attackAngleEnabled());
        var withG = gson.fromJson("{\"rvp_aero_steering\":true,\"rvp_attack_angle_limit_deg\":20,\"rvp_maxg\":0}", RVP_ProjectileData.class);
        assertTrue(withG.resolveAeroSteeringLimits(3.0, 0.0, 1.0f).attackAngleEnabled());
    }

    /** 阶段 S2 未配置新键时必须保持全局静默，并复用最高速率与基础阻力作为推导值。 */
    @Test
    void defaultsRemainDisabledAndResolveExistingPhysicsFields() {
        RVP_ProjectileData data = gson.fromJson("""
                {
                  "max_speed": 6.0,
                  "has_rocket_engine": true,
                  "drag_coefficient": 0.002
                }
                """, RVP_ProjectileData.class);

        RVP_AeroSteeringLimits limits = data.resolveAeroSteeringLimits(0.3, 100.0, 0.15F);

        assertFalse(data.isRvpAeroSteering());
        assertNull(data.getRvpInducedDrag());
        assertNull(data.getRvpRefSpeed());
        assertFalse(limits.enabled());
        assertEquals(6.0, limits.referenceSpeed());
        assertEquals(0.002, limits.inducedDrag(), 1.0E-9);
        assertEquals(0.15F, limits.turningFactor());
    }

    /** 显式新键应完整进入不可变限制快照，且 0 参考速度与 0 阻力保留关闭语义。 */
    @Test
    void explicitFieldsOverrideDerivedValues() {
        RVP_ProjectileData data = gson.fromJson("""
                {
                  "rvp_aero_steering": true,
                  "rvp_ref_speed": 0.0,
                  "rvp_induced_drag": 0.0,
                  "rvp_turn_rate_limit": 12.5,
                  "max_speed": 8.0,
                  "has_rocket_engine": true,
                  "drag_coefficient": 0.02
                }
                """, RVP_ProjectileData.class);

        RVP_AeroSteeringLimits limits = data.resolveAeroSteeringLimits(3.0, 0.0, 0.2F);

        assertTrue(limits.enabled());
        assertEquals(0.0, limits.referenceSpeed());
        assertEquals(0.0, limits.inducedDrag());
        assertEquals(12.5, limits.turnRateLimitDegPerTick());
        assertEquals(1.0, limits.densityFactor());
    }

    /** 缺少显式参考速度与最高速率时，应先采用武器初速，最终才回退到 3 格/Tick。 */
    @Test
    void referenceSpeedFallsBackToWeaponSpeedThenConstant() {
        RVP_ProjectileData data = new RVP_ProjectileData();

        assertEquals(2.5,
                data.resolveAeroSteeringLimits(2.5, 0.0).referenceSpeed());
        assertEquals(3.0,
                data.resolveAeroSteeringLimits(0.0, 0.0).referenceSpeed());
    }
}
