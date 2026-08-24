package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_SubmunitionPayloadDataTest {

    /** 使用项目当前 Gson 行为解析武器 JSON 数据类。 */
    private final Gson gson = new Gson();

    @Test
    void payloadVelocityParsesAndUsesSafeDefaults() {
        RVP_SubmunitionPayloadData defaults = gson.fromJson("{}", RVP_SubmunitionPayloadData.class);
        RVP_SubmunitionPayloadData configured = gson.fromJson("""
                {
                  "payloads_velocity": [1.5, -3.0, 2.25],
                  "payloads_velocity_factor": 0.4
                }
                """, RVP_SubmunitionPayloadData.class);
        RVP_SubmunitionPayloadData wrongLength = gson.fromJson("""
                {"payloads_velocity": [1.0, 2.0]}
                """, RVP_SubmunitionPayloadData.class);
        RVP_SubmunitionPayloadData nonFinite = gson.fromJson("""
                {"payloads_velocity": ["NaN", 0.0, 0.0]}
                """, RVP_SubmunitionPayloadData.class);

        assertEquals(Vec3.ZERO, defaults.getPayloadsVelocity());
        assertEquals(0f, defaults.getPayloadsVelocityFactor());
        assertEquals(new Vec3(1.5, -3.0, 2.25), configured.getPayloadsVelocity());
        assertEquals(0.4f, configured.getPayloadsVelocityFactor());
        assertEquals(Vec3.ZERO, wrongLength.getPayloadsVelocity());
        assertEquals(Vec3.ZERO, nonFinite.getPayloadsVelocity());
    }

    @Test
    void payloadVelocityFactorIsLimitedToSupportedRange() {
        RVP_SubmunitionPayloadData negative = gson.fromJson("""
                {"payloads_velocity_factor": -0.5}
                """, RVP_SubmunitionPayloadData.class);
        RVP_SubmunitionPayloadData excessive = gson.fromJson("""
                {"payloads_velocity_factor": 4.0}
                """, RVP_SubmunitionPayloadData.class);
        RVP_SubmunitionPayloadData nonFinite = gson.fromJson("""
                {"payloads_velocity_factor": "NaN"}
                """, RVP_SubmunitionPayloadData.class);

        assertEquals(0f, negative.getPayloadsVelocityFactor());
        assertEquals(1f, excessive.getPayloadsVelocityFactor());
        assertEquals(0f, nonFinite.getPayloadsVelocityFactor());
    }

    @Test
    void parentHorizontalInheritanceIsExplicitAndDefaultsOff() {
        RVP_SubmunitionPayloadData defaults = gson.fromJson("{}", RVP_SubmunitionPayloadData.class);
        RVP_SubmunitionPayloadData configured = gson.fromJson("""
                {
                  "inherit_parent_velocity": true,
                  "inherit_parent_horizontal_velocity": true
                }
                """, RVP_SubmunitionPayloadData.class);

        assertFalse(defaults.isInheritParentHorizontalVelocity());
        assertTrue(configured.isInheritParentVelocity());
        assertTrue(configured.isInheritParentHorizontalVelocity());
    }
}
