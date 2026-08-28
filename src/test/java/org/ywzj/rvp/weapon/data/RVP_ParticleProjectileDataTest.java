package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.all.RVP_ParticleIds;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                    "body_start_scale": 0.08,
                    "body_color": "#FF8A1F",
                    "body_end_color": "#FF2400",
                    "body_flicker": 0.25,
                    "body_horizontal_flicker": 0.15,
                    "body_flicker_interval_ticks": 4,
                    "body_sample_interval_ticks": 2,
                    "trail_initial_extra_count": 4,
                    "trail_initial_extra_ticks": 10,
                    "trail_initial_spread": 0.8,
                    "trail_lifetime_start_on_landing": true,
                    "trail_hot_phase_ticks": 4,
                    "trail_hot_color": "#FFC247",
                    "trail_start_color": "#FFB52E",
                    "trail_end_color": "#7A3512"
                  }
                }
                """, RVP_EffectsData.class);

        RVP_ParticleProjectileData data = effects.getParticleProjectileData();
        assertTrue(data.isEnabled());
        assertEquals("rvp:white_phosphorus", data.getParticleType());
        assertEquals(0.42f, data.getBodyScale(), 1.0E-6f);
        assertEquals(0.08f, data.getBodyStartScale(), 1.0E-6f);
        assertEquals(0xFF8A1F, data.getBodyColorRgb());
        assertEquals(0xFF2400, data.getBodyEndColorRgb());
        assertEquals(0.25f, data.getBodyFlicker(), 1.0E-6f);
        assertEquals(0.15f, data.getBodyHorizontalFlicker(), 1.0E-6f);
        assertEquals(4, data.getBodyFlickerIntervalTicks());
        assertEquals(2, data.getBodySampleIntervalTicks());
        assertEquals(4, data.getTrailInitialExtraCount());
        assertEquals(10, data.getTrailInitialExtraTicks());
        assertEquals(0.8f, data.getTrailInitialSpread(), 1.0E-6f);
        assertTrue(data.isTrailLifetimeStartOnLanding());
        assertEquals(4, data.getTrailHotPhaseTicks());
        assertEquals(0xFFC247, data.getTrailHotColorRgb());
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
                  "body_start_scale": -0.2,
                  "body_lifetime_ticks": 0,
                  "body_color": "invalid",
                  "body_horizontal_flicker": -0.5,
                  "body_flicker_interval_ticks": 0,
                  "body_sample_interval_ticks": -3,
                  "trail_hot_phase_ticks": -4,
                  "trail_hot_color": "invalid",
                  "trail_start_alpha": 2,
                  "trail_end_alpha": -1
                }
                """, RVP_ParticleProjectileData.class);

        assertEquals(0.4f, data.getBodyScale(), 1.0E-6f);
        assertEquals(0.0f, data.getBodyStartScale(), 1.0E-6f);
        assertEquals(1, data.getBodyLifetimeTicks());
        assertEquals(0xFFC247, data.getBodyColorRgb());
        assertEquals(0xFFC247, data.getBodyEndColorRgb());
        assertEquals(0.0f, data.getBodyHorizontalFlicker(), 1.0E-6f);
        assertEquals(1, data.getBodyFlickerIntervalTicks());
        assertEquals(1, data.getBodySampleIntervalTicks());
        assertEquals(0, data.getTrailHotPhaseTicks());
        assertEquals(0xFFC247, data.getTrailHotColorRgb());
        assertEquals(1.0f, data.getTrailStartAlpha(), 1.0E-6f);
        assertEquals(0.0f, data.getTrailEndAlpha(), 1.0E-6f);
    }

    @Test
    void horizontalFlickerDefaultsToZeroAndRejectsNonFiniteValues() {
        RVP_ParticleProjectileData defaults = new RVP_ParticleProjectileData();
        RVP_ParticleProjectileData nan = GSON.fromJson(
                "{\"body_horizontal_flicker\":\"NaN\"}", RVP_ParticleProjectileData.class);
        RVP_ParticleProjectileData infinity = GSON.fromJson(
                "{\"body_horizontal_flicker\":\"Infinity\"}", RVP_ParticleProjectileData.class);

        assertEquals(0.0f, defaults.getBodyHorizontalFlicker(), 1.0E-6f);
        assertEquals(0.0f, defaults.getBodyStartScale(), 1.0E-6f);
        assertEquals(1, defaults.getBodyFlickerIntervalTicks());
        assertEquals(1, defaults.getBodySampleIntervalTicks());
        assertFalse(defaults.isTrailLifetimeStartOnLanding());
        assertEquals(0, defaults.getTrailHotPhaseTicks());
        assertEquals(0xFFC247, defaults.getTrailHotColorRgb());
        assertEquals(0.0f, nan.getBodyHorizontalFlicker(), 1.0E-6f);
        assertEquals(0.0f, infinity.getBodyHorizontalFlicker(), 1.0E-6f);
    }

    @Test
    void bodyStartScaleRejectsNonFiniteValues() {
        RVP_ParticleProjectileData nan = GSON.fromJson(
                "{\"body_start_scale\":\"NaN\"}", RVP_ParticleProjectileData.class);
        RVP_ParticleProjectileData infinity = GSON.fromJson(
                "{\"body_start_scale\":\"Infinity\"}", RVP_ParticleProjectileData.class);

        assertEquals(0.0f, nan.getBodyStartScale(), 1.0E-6f);
        assertEquals(0.0f, infinity.getBodyStartScale(), 1.0E-6f);
    }

    @Test
    void trailInitialDensityDefaultsClampAndRejectsNonFiniteSpread() {
        RVP_ParticleProjectileData defaults = new RVP_ParticleProjectileData();
        RVP_ParticleProjectileData negativeAndHigh = GSON.fromJson("""
                {
                  "trail_initial_extra_count": -1,
                  "trail_initial_extra_ticks": 101,
                  "trail_initial_spread": 99
                }
                """, RVP_ParticleProjectileData.class);
        RVP_ParticleProjectileData highAndNegative = GSON.fromJson("""
                {
                  "trail_initial_extra_count": 99,
                  "trail_initial_extra_ticks": -1,
                  "trail_initial_spread": -0.5
                }
                """, RVP_ParticleProjectileData.class);
        RVP_ParticleProjectileData nan = GSON.fromJson(
                "{\"trail_initial_spread\":\"NaN\"}", RVP_ParticleProjectileData.class);
        RVP_ParticleProjectileData infinity = GSON.fromJson(
                "{\"trail_initial_spread\":\"Infinity\"}", RVP_ParticleProjectileData.class);

        assertEquals(0, defaults.getTrailInitialExtraCount());
        assertEquals(0, defaults.getTrailInitialExtraTicks());
        assertEquals(0.0f, defaults.getTrailInitialSpread(), 1.0E-6f);
        assertEquals(0, negativeAndHigh.getTrailInitialExtraCount());
        assertEquals(100, negativeAndHigh.getTrailInitialExtraTicks());
        assertEquals(16.0f, negativeAndHigh.getTrailInitialSpread(), 1.0E-6f);
        assertEquals(16, highAndNegative.getTrailInitialExtraCount());
        assertEquals(0, highAndNegative.getTrailInitialExtraTicks());
        assertEquals(0.0f, highAndNegative.getTrailInitialSpread(), 1.0E-6f);
        assertEquals(0.0f, nan.getTrailInitialSpread(), 1.0E-6f);
        assertEquals(0.0f, infinity.getTrailInitialSpread(), 1.0E-6f);
    }

    @Test
    void missingOrInvalidBodyEndColorFallsBackToBodyColor() {
        RVP_ParticleProjectileData missing = GSON.fromJson(
                "{\"body_color\":\"#D94A10\"}", RVP_ParticleProjectileData.class);
        RVP_ParticleProjectileData invalid = GSON.fromJson(
                "{\"body_color\":\"#D94A10\",\"body_end_color\":\"invalid\"}",
                RVP_ParticleProjectileData.class);

        assertEquals(0xD94A10, missing.getBodyEndColorRgb());
        assertEquals(0xD94A10, invalid.getBodyEndColorRgb());
    }

    @Test
    void removedIndependentTrailSamplingFieldsAreNotPartOfCurrentSchema() {
        assertFalse(Arrays.stream(RVP_ParticleProjectileData.class.getDeclaredFields())
                .anyMatch(field -> "trailSpacing".equals(field.getName())));
        assertFalse(Arrays.stream(RVP_ParticleProjectileData.class.getDeclaredFields())
                .anyMatch(field -> "trailStartScale".equals(field.getName())));
    }
}
