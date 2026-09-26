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
