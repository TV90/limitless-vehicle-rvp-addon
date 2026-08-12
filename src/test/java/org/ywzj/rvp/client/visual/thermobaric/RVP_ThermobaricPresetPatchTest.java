package org.ywzj.rvp.client.visual.thermobaric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_ThermobaricPresetPatchTest {
    @Test
    void sparsePatchOverridesOnlyExplicitFields() {
        RVP_ThermobaricPreset patched = RVP_ThermobaricPresetPatch.parse("""
                {
                  "flame_color": "#123456",
                  "pressure_radius_factor": 38.0,
                  "condensation_cloud_cut_speed_factor": 2.5,
                  "pressure_wave_fade_speed_factor": 1.75,
                  "cloud_full_tick": 110,
                  "cloud_color_change_start_tick": 24,
                  "cloud_color_change_end_tick": 72,
                  "cloud_rise_speed_factor": 0.65,
                  "cloud_roll_speed_factor": 2.25,
                  "show_condensation_cloud": true,
                  "show_condensation_cloud_particles": false,
                  "condensation_cloud_particle_max_count": 5000,
                  "condensation_cloud_particle_scale": 17.5,
                  "condensation_cloud_particle_spawn_thickness_factor": 2.75,
                  "thermobaric_lod": {
                    "medium": {
                      "particle_ratio": 0.4
                    },
                    "beyond": {
                      "particle_ratio": 0.1
                    }
                  },
                  "max_clouds": 1200,
                  "max_fireball_clouds": 900,
                  "max_dust_segments": 333,
                  "dust_ground_radial_samples": 33,
                  "pressure_rings": 64,
                  "pressure_segments": 128
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(0x123456, patched.flameColor());
        assertEquals(38.0F, patched.pressureRadiusFactor());
        assertEquals(2.5F, patched.condensationCloudCutSpeedFactor());
        assertEquals(1.75F, patched.pressureWaveFadeSpeedFactor());
        assertEquals(110, patched.cloudFullTick());
        assertEquals(24, patched.cloudColorChangeStartTick());
        assertEquals(72, patched.cloudColorChangeEndTick());
        assertEquals(0.65F, patched.cloudRiseSpeedFactor());
        assertEquals(2.25F, patched.cloudRollSpeedFactor());
        assertTrue(patched.showCondensationCloud());
        assertFalse(patched.showCondensationCloudParticles());
        assertEquals(5000, patched.condensationCloudParticleMaxCount());
        assertEquals(17.5F, patched.condensationCloudParticleScale());
        assertEquals(2.75F, patched.condensationCloudParticleSpawnThicknessFactor());
        assertEquals(128.0F, patched.thermobaricLod().nearMaxDistance());
        assertEquals(0.4F, patched.thermobaricLod().mediumParticleRatio());
        assertEquals(0.1F, patched.thermobaricLod().beyondParticleRatio());
        assertEquals(1200, patched.maxClouds());
        assertEquals(900, patched.maxFireballClouds());
        assertEquals(333, patched.maxDustSegments());
        assertEquals(33, patched.dustGroundRadialSamples());
        assertEquals(64, patched.pressureRings());
        assertEquals(128, patched.pressureSegments());
        assertFalse(patched.showPressureWave());
        assertEquals(65, patched.cloudFadeDurationTicks());
        assertEquals(RVP_ThermobaricPreset.DEFAULT.coreColor(), patched.coreColor());
        assertEquals(RVP_ThermobaricPreset.DEFAULT.dustRadiusFactor(), patched.dustRadiusFactor());
    }

    @Test
    void invalidAndUnknownFieldsFallBackIndependently() {
        RVP_ThermobaricPreset patched = RVP_ThermobaricPresetPatch.parse("""
                {
                  "core_color": "orange",
                  "dust_ring_start_tick": 12.5,
                  "dust_ring_fade_duration_ticks": 0,
                  "max_dust_fade_spread": 2.5,
                  "pressure_wave_fade_duration_ticks": 6,
                  "cloud_radius_factor": "wide",
                  "cloud_rise_speed_factor": -0.5,
                  "cloud_roll_speed_factor": "fast",
                  "show_dust_ring": "yes",
                  "unknown_field": true,
                  "smoke_color": "#010203"
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(RVP_ThermobaricPreset.DEFAULT.coreColor(), patched.coreColor());
        assertEquals(RVP_ThermobaricPreset.DEFAULT.dustRingStartTick(), patched.dustRingStartTick());
        assertEquals(RVP_ThermobaricPreset.DEFAULT.cloudRadiusFactor(), patched.cloudRadiusFactor());
        assertEquals(RVP_ThermobaricPreset.DEFAULT.cloudRiseSpeedFactor(),
                patched.cloudRiseSpeedFactor());
        assertEquals(RVP_ThermobaricPreset.DEFAULT.cloudRollSpeedFactor(),
                patched.cloudRollSpeedFactor());
        assertEquals(RVP_ThermobaricPreset.DEFAULT.showDustRing(), patched.showDustRing());
        assertEquals(0x010203, patched.smokeColor());
    }

    @Test
    void lifecycleUsesStartFullAndFadeDurationSemantics() {
        RVP_ThermobaricPreset patched = RVP_ThermobaricPresetPatch.parse("""
                {
                  "core_start_tick": 6,
                  "core_full_tick": 30,
                  "core_fade_duration_ticks": 8
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(6, patched.coreStartTick());
        assertEquals(30, patched.coreFullTick());
        assertEquals(8, patched.coreFadeDurationTicks());
        assertEquals(100, RVP_ThermobaricPreset.DEFAULT.effectEndTick());
        assertEquals(1.0F, RVP_ThermobaricPreset.DEFAULT.condensationCloudCutSpeedFactor());
        assertEquals(0.0F, RVP_ThermobaricPreset.DEFAULT.pressureWaveFadeSpeedFactor());
        assertEquals(14, RVP_ThermobaricPreset.DEFAULT.pressureWaveEndTick());
        assertFalse(RVP_ThermobaricPreset.DEFAULT.showCondensationCloud());
        assertFalse(RVP_ThermobaricPreset.DEFAULT.showPressureWave());
        assertTrue(RVP_ThermobaricPreset.DEFAULT.showCondensationCloudParticles());
        assertEquals(1024, RVP_ThermobaricPreset.DEFAULT.condensationCloudParticleMaxCount());
        assertEquals(8.0F, RVP_ThermobaricPreset.DEFAULT.condensationCloudParticleScale());
        assertEquals(1.0F,
                RVP_ThermobaricPreset.DEFAULT.condensationCloudParticleSpawnThicknessFactor());
        assertEquals(RVP_ThermobaricLod.DEFAULT,
                RVP_ThermobaricPreset.DEFAULT.thermobaricLod());
        assertEquals(200, RVP_ThermobaricPreset.DEFAULT.maxClouds());
        assertEquals(240, RVP_ThermobaricPreset.DEFAULT.maxFireballClouds());
        assertEquals(128, RVP_ThermobaricPreset.DEFAULT.maxDustSegments());
        assertEquals(9, RVP_ThermobaricPreset.DEFAULT.dustGroundRadialSamples());
        assertEquals(16, RVP_ThermobaricPreset.DEFAULT.pressureRings());
        assertEquals(32, RVP_ThermobaricPreset.DEFAULT.pressureSegments());
        assertEquals(1.0F, RVP_ThermobaricPreset.DEFAULT.cloudRiseSpeedFactor());
        assertEquals(1.0F, RVP_ThermobaricPreset.DEFAULT.cloudRollSpeedFactor());
        assertEquals(69, RVP_ThermobaricPreset.DEFAULT.dustRingEndTick());
    }

    @Test
    void dustRingUsesDerivedEndTickAndSaturatesOverflow() {
        RVP_ThermobaricPreset dustLongest = RVP_ThermobaricPresetPatch.parse("""
                {
                  "core_start_tick": 0,
                  "core_full_tick": 0,
                  "core_fade_duration_ticks": 0,
                  "pressure_wave_start_tick": 0,
                  "pressure_wave_full_tick": 0,
                  "dust_ring_start_tick": 10,
                  "dust_ring_full_tick": 30,
                  "cloud_start_tick": 0,
                  "cloud_full_tick": 0,
                  "cloud_fade_duration_ticks": 0
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);
        RVP_ThermobaricPreset saturated = RVP_ThermobaricPresetPatch.parse("""
                {
                  "dust_ring_start_tick": 0,
                  "dust_ring_full_tick": 2147483647
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(50, dustLongest.dustRingEndTick());
        assertEquals(50, dustLongest.effectEndTick());
        assertEquals(Integer.MAX_VALUE, saturated.dustRingEndTick());
    }

    @Test
    void dustRingWithoutMovementWindowHasNoDerivedLifetime() {
        RVP_ThermobaricPreset patched = RVP_ThermobaricPresetPatch.parse("""
                {
                  "dust_ring_start_tick": 40,
                  "dust_ring_full_tick": 20
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(40, patched.dustRingFullTick());
        assertEquals(40, patched.dustRingEndTick());
    }

    @Test
    void pressureWaveUsesDerivedEndTickAndSaturatesOverflow() {
        RVP_ThermobaricPreset pressureLongest = RVP_ThermobaricPresetPatch.parse("""
                {
                  "core_start_tick": 0,
                  "core_full_tick": 0,
                  "core_fade_duration_ticks": 0,
                  "pressure_wave_start_tick": 10,
                  "pressure_wave_full_tick": 30,
                  "dust_ring_start_tick": 0,
                  "dust_ring_full_tick": 0,
                  "cloud_start_tick": 0,
                  "cloud_full_tick": 0,
                  "cloud_fade_duration_ticks": 0
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);
        RVP_ThermobaricPreset saturated = RVP_ThermobaricPresetPatch.parse("""
                {
                  "pressure_wave_start_tick": 0,
                  "pressure_wave_full_tick": 2147483647
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(50, pressureLongest.pressureWaveEndTick());
        assertEquals(50, pressureLongest.effectEndTick());
        assertEquals(Integer.MAX_VALUE, saturated.pressureWaveEndTick());
    }

    @Test
    void pressureWaveWithoutMovementWindowHasNoDerivedLifetime() {
        RVP_ThermobaricPreset patched = RVP_ThermobaricPresetPatch.parse("""
                {
                  "pressure_wave_start_tick": 40,
                  "pressure_wave_full_tick": 20
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(40, patched.pressureWaveFullTick());
        assertEquals(40, patched.pressureWaveEndTick());
    }

    @Test
    void invalidCutSpeedFallsBackIndependently() {
        RVP_ThermobaricPreset negative = RVP_ThermobaricPresetPatch.parse("""
                {"condensation_cloud_cut_speed_factor": -1.0}
                """).apply(RVP_ThermobaricPreset.DEFAULT);
        RVP_ThermobaricPreset text = RVP_ThermobaricPresetPatch.parse("""
                {"condensation_cloud_cut_speed_factor": "fast"}
                """).apply(RVP_ThermobaricPreset.DEFAULT);
        RVP_ThermobaricPreset nonFinite = RVP_ThermobaricPresetPatch.parse("""
                {"condensation_cloud_cut_speed_factor": 1e999}
                """).apply(RVP_ThermobaricPreset.DEFAULT);
        RVP_ThermobaricPreset zero = RVP_ThermobaricPresetPatch.parse("""
                {"condensation_cloud_cut_speed_factor": 0.0}
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(1.0F, negative.condensationCloudCutSpeedFactor());
        assertEquals(1.0F, text.condensationCloudCutSpeedFactor());
        assertEquals(1.0F, nonFinite.condensationCloudCutSpeedFactor());
        assertEquals(0.0F, zero.condensationCloudCutSpeedFactor());
    }

    @Test
    void invalidFadeSpeedFallsBackToDisabledDefault() {
        RVP_ThermobaricPreset negative = RVP_ThermobaricPresetPatch.parse("""
                {"pressure_wave_fade_speed_factor": -1.0}
                """).apply(RVP_ThermobaricPreset.DEFAULT);
        RVP_ThermobaricPreset text = RVP_ThermobaricPresetPatch.parse("""
                {"pressure_wave_fade_speed_factor": "fast"}
                """).apply(RVP_ThermobaricPreset.DEFAULT);
        RVP_ThermobaricPreset nonFinite = RVP_ThermobaricPresetPatch.parse("""
                {"pressure_wave_fade_speed_factor": 1e999}
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(0.0F, negative.pressureWaveFadeSpeedFactor());
        assertEquals(0.0F, text.pressureWaveFadeSpeedFactor());
        assertEquals(0.0F, nonFinite.pressureWaveFadeSpeedFactor());
    }

    @Test
    void nestedLodSupportsSparseTierAndChildOverrides() {
        RVP_ThermobaricPreset patched = RVP_ThermobaricPresetPatch.parse("""
                {
                  "thermobaric_lod": {
                    "near": {
                      "max_distance": 96.0
                    },
                    "far": {
                      "max_distance": 640.0,
                      "particle_ratio": 0.2
                    },
                    "beyond": {
                      "particle_ratio": 0.05
                    }
                  }
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(96.0F, patched.thermobaricLod().nearMaxDistance());
        assertEquals(1.0F, patched.thermobaricLod().nearParticleRatio());
        assertEquals(256.0F, patched.thermobaricLod().mediumMaxDistance());
        assertEquals(0.5F, patched.thermobaricLod().mediumParticleRatio());
        assertEquals(640.0F, patched.thermobaricLod().farMaxDistance());
        assertEquals(0.2F, patched.thermobaricLod().farParticleRatio());
        assertEquals(0.05F, patched.thermobaricLod().beyondParticleRatio());
    }

    @Test
    void invalidNestedLodChildrenFallBackIndependently() {
        RVP_ThermobaricPreset patched = RVP_ThermobaricPresetPatch.parse("""
                {
                  "condensation_cloud_particle_spawn_thickness_factor": -2.0,
                  "thermobaric_lod": {
                    "near": {
                      "max_distance": -1.0,
                      "particle_ratio": 1.5
                    },
                    "medium": "medium",
                    "far": {
                      "max_distance": 600.0,
                      "particle_ratio": 0.1
                    },
                    "beyond": {
                      "max_distance": 900.0,
                      "particle_ratio": 0.05
                    },
                    "unknown_tier": {}
                  }
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(1.0F, patched.condensationCloudParticleSpawnThicknessFactor());
        assertEquals(128.0F, patched.thermobaricLod().nearMaxDistance());
        assertEquals(1.0F, patched.thermobaricLod().nearParticleRatio());
        assertEquals(256.0F, patched.thermobaricLod().mediumMaxDistance());
        assertEquals(0.5F, patched.thermobaricLod().mediumParticleRatio());
        assertEquals(600.0F, patched.thermobaricLod().farMaxDistance());
        assertEquals(0.1F, patched.thermobaricLod().farParticleRatio());
        assertEquals(0.05F, patched.thermobaricLod().beyondParticleRatio());
    }

    @Test
    void nestedLodNormalizesDistanceAndRatioOrderAfterMerge() {
        RVP_ThermobaricPreset patched = RVP_ThermobaricPresetPatch.parse("""
                {
                  "thermobaric_lod": {
                    "near": {"max_distance": 300.0, "particle_ratio": 0.4},
                    "medium": {"max_distance": 100.0, "particle_ratio": 0.9},
                    "far": {"max_distance": 200.0, "particle_ratio": 0.8},
                    "beyond": {"particle_ratio": 0.7}
                  }
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(300.0F, patched.thermobaricLod().nearMaxDistance());
        assertEquals(300.0F, patched.thermobaricLod().mediumMaxDistance());
        assertEquals(300.0F, patched.thermobaricLod().farMaxDistance());
        assertEquals(0.4F, patched.thermobaricLod().nearParticleRatio());
        assertEquals(0.4F, patched.thermobaricLod().mediumParticleRatio());
        assertEquals(0.4F, patched.thermobaricLod().farParticleRatio());
        assertEquals(0.4F, patched.thermobaricLod().beyondParticleRatio());
    }

    @Test
    void fullTickCannotPrecedeStartTick() {
        RVP_ThermobaricPreset patched = RVP_ThermobaricPresetPatch.parse("""
                {
                  "cloud_start_tick": 40,
                  "cloud_full_tick": 20
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(40, patched.cloudStartTick());
        assertEquals(40, patched.cloudFullTick());
    }

    @Test
    void cloudColorChangeEndCannotPrecedeStart() {
        RVP_ThermobaricPreset patched = RVP_ThermobaricPresetPatch.parse("""
                {
                  "cloud_color_change_start_tick": 50,
                  "cloud_color_change_end_tick": 20
                }
                """).apply(RVP_ThermobaricPreset.DEFAULT);

        assertEquals(50, patched.cloudColorChangeStartTick());
        assertEquals(50, patched.cloudColorChangeEndTick());
    }
}
