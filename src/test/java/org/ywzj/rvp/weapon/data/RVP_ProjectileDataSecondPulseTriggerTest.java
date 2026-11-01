package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RVP_ProjectileData} 第二脉冲新增触发条件（极速比例、弹道顶端）单元测试。
 *
 * <p>覆盖：两个新条件可单独启用二脉冲（与旧定速/距离条件并集）、顶端容差默认值与启用门控，
 * 以及武器根节点回退解析能读到三个新字段。</p>
 */
class RVP_ProjectileDataSecondPulseTriggerTest {

    private final Gson gson = new Gson();

    @Test
    void speedRatioAloneEnablesSecondPulse() {
        RVP_ProjectileData data = gson.fromJson("""
                {
                  "has_rocket_engine": true,
                  "mass": 0.05,
                  "thrust": 0.03,
                  "motor_burn_time": 100,
                  "second_pulse": true,
                  "second_pulse_thrust": 0.02,
                  "second_pulse_burn_time": 40,
                  "second_pulse_trigger_speed_ratio": 0.6
                }
                """, RVP_ProjectileData.class);

        assertEquals(0.6f, data.getResolvedSecondPulseTriggerSpeedRatio(), 1.0E-6f);
        assertTrue(data.usesSecondPulse());
    }

    @Test
    void apexAloneEnablesSecondPulseWithTolerance() {
        RVP_ProjectileData data = gson.fromJson("""
                {
                  "has_rocket_engine": true,
                  "mass": 0.05,
                  "thrust": 0.03,
                  "motor_burn_time": 100,
                  "second_pulse": true,
                  "second_pulse_thrust": 0.02,
                  "second_pulse_burn_time": 40,
                  "second_pulse_trigger_apex": true,
                  "second_pulse_trigger_apex_tolerance": 0.15
                }
                """, RVP_ProjectileData.class);

        assertTrue(data.isSecondPulseTriggerApex());
        assertEquals(0.15f, data.getResolvedSecondPulseTriggerApexTolerance(), 1.0E-6f);
        assertTrue(data.usesSecondPulse());
    }

    @Test
    void apexToleranceDefaultsToHalfTenthAndIsZeroWhenDisabled() {
        RVP_ProjectileData enabled = gson.fromJson("""
                {
                  "has_rocket_engine": true,
                  "second_pulse": true,
                  "second_pulse_thrust": 0.02,
                  "second_pulse_burn_time": 40,
                  "second_pulse_trigger_apex": true
                }
                """, RVP_ProjectileData.class);
        assertEquals(0.05f, enabled.getResolvedSecondPulseTriggerApexTolerance(), 1.0E-6f);

        RVP_ProjectileData disabled = gson.fromJson("""
                {
                  "has_rocket_engine": true,
                  "second_pulse": true,
                  "second_pulse_thrust": 0.02,
                  "second_pulse_burn_time": 40,
                  "second_pulse_trigger_apex_tolerance": 0.2
                }
                """, RVP_ProjectileData.class);
        assertFalse(disabled.isSecondPulseTriggerApex());
        assertEquals(0f, disabled.getResolvedSecondPulseTriggerApexTolerance(), 1.0E-6f);
    }

    @Test
    void newTriggersAreInertWithoutSecondPulseSwitch() {
        RVP_ProjectileData data = gson.fromJson("""
                {
                  "has_rocket_engine": true,
                  "mass": 0.05,
                  "thrust": 0.03,
                  "motor_burn_time": 100,
                  "second_pulse_thrust": 0.02,
                  "second_pulse_burn_time": 40,
                  "second_pulse_trigger_speed_ratio": 0.6,
                  "second_pulse_trigger_apex": true,
                  "second_pulse_trigger_apex_tolerance": 0.2
                }
                """, RVP_ProjectileData.class);

        assertEquals(0f, data.getResolvedSecondPulseTriggerSpeedRatio(), 1.0E-6f);
        assertFalse(data.isSecondPulseTriggerApex());
        assertEquals(0f, data.getResolvedSecondPulseTriggerApexTolerance(), 1.0E-6f);
        assertFalse(data.usesSecondPulse());
    }

    @Test
    void rootFallbackReadsNewTriggerFields() {
        JsonObject projectile = JsonParser.parseString("""
                {
                  "has_rocket_engine": true,
                  "mass": 0.05,
                  "thrust": 0.03,
                  "motor_burn_time": 100,
                  "second_pulse": true,
                  "second_pulse_thrust": 0.02,
                  "second_pulse_burn_time": 40
                }
                """).getAsJsonObject();
        JsonObject weaponRoot = JsonParser.parseString("""
                {
                  "second_pulse_trigger_speed_ratio": 0.6,
                  "second_pulse_trigger_apex": true,
                  "second_pulse_trigger_apex_tolerance": 0.12
                }
                """).getAsJsonObject();
        RVP_ProjectileData data = gson.fromJson(projectile, RVP_ProjectileData.class);

        data.resolvePropulsionFallback(weaponRoot, projectile);

        assertEquals(0.6f, data.getResolvedSecondPulseTriggerSpeedRatio(), 1.0E-6f);
        assertTrue(data.isSecondPulseTriggerApex());
        assertEquals(0.12f, data.getResolvedSecondPulseTriggerApexTolerance(), 1.0E-6f);
        assertTrue(data.usesSecondPulse());
    }
}
