package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.all.RVP_ParticleIds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ParticleProjectileDataTest {

    /** 测试 JSON 解析使用的 Gson 实例。 */
    private static final Gson GSON = new Gson();

    @Test
    void parsesParticleProjectileConfigurationAndColors() {
        RVP_EffectsData effects = GSON.fromJson("""
                {
                  "particle_projectile_data": {
                    "enabled": true,
                    "particle_type": "rvp:white_phosphorus",
                    "body_scale": 0.42,
                    "body_color": "#FFC247",
                    "trail_spacing": 0.18,
                    "trail_start_color": "#FFB52E",
                    "trail_end_color": "#7A3512"
                  }
                }
                """, RVP_EffectsData.class);

        RVP_ParticleProjectileData data = effects.getParticleProjectileData();
        assertTrue(data.isEnabled());
        assertEquals("rvp:white_phosphorus", data.getParticleType());
        assertEquals(0.42f, data.getBodyScale(), 1.0E-6f);
        assertEquals(0xFFC247, data.getBodyColorRgb());
        assertEquals(0.18f, data.getTrailSpacing(), 1.0E-6f);
        assertEquals(0xFFB52E, data.getTrailStartColorRgb());
        assertEquals(0x7A3512, data.getTrailEndColorRgb());
    }

    @Test
    void whitePhosphorusRuntimeIdRemainsCompatibleWithVehiclePackSchema() {
        assertEquals("rvp:white_phosphorus", RVP_ParticleIds.WHITE_PHOSPHORUS.toString());
    }

    @Test
    void invalidValuesUseSafeFallbacks() {
        RVP_ParticleProjectileData data = GSON.fromJson("""
                {
                  "body_scale": "NaN",
                  "body_lifetime_ticks": 0,
                  "body_color": "invalid",
                  "trail_spacing": 0,
                  "trail_start_alpha": 2,
                  "trail_end_alpha": -1
                }
                """, RVP_ParticleProjectileData.class);

        assertEquals(0.4f, data.getBodyScale(), 1.0E-6f);
        assertEquals(1, data.getBodyLifetimeTicks());
        assertEquals(0xFFC247, data.getBodyColorRgb());
        assertEquals(0.02f, data.getTrailSpacing(), 1.0E-6f);
        assertEquals(1.0f, data.getTrailStartAlpha(), 1.0E-6f);
        assertEquals(0.0f, data.getTrailEndAlpha(), 1.0E-6f);
    }
}
