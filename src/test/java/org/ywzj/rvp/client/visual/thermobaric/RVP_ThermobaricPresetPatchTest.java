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
                  "pressure_wave_fade_duration_ticks": 6,
                  "cloud_full_tick": 110,
                  "cloud_color_change_start_tick": 24,
                  "cloud_color_change_end_tick": 72,
                  "cloud_rise_speed_factor": 0.65,
                  "cloud_roll_speed_factor": 2.25,
                  "show_condensation_cloud": true,
                  "show_condensation_cloud_particles": false,
                  "condensation_cloud_particle_max_count": 5000,
                  "condensation_cloud_particle_scale": 17.5,
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
        assertEquals(6, patched.pressureWaveFadeDurationTicks());
        assertEquals(110, patched.cloudFullTick());
        assertEquals(24, patched.cloudColorChangeStartTick());
        assertEquals(72, patched.cloudColorChangeEndTick());
        assertEquals(0.65F, patched.cloudRiseSpeedFactor());
        assertEquals(2.25F, patched.cloudRollSpeedFactor());
        assertTrue(patched.showCondensationCloud());
        assertFalse(patched.showCondensationCloudParticles());
        assertEquals(5000, patched.condensationCloudParticleMaxCount());
        assertEquals(17.5F, patched.condensationCloudParticleScale());
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
        assertEquals(12, RVP_ThermobaricPreset.DEFAULT.pressureWaveFadeDurationTicks());
        assertFalse(RVP_ThermobaricPreset.DEFAULT.showCondensationCloud());
        assertFalse(RVP_ThermobaricPreset.DEFAULT.showPressureWave());
        assertTrue(RVP_ThermobaricPreset.DEFAULT.showCondensationCloudParticles());
        assertEquals(1024, RVP_ThermobaricPreset.DEFAULT.condensationCloudParticleMaxCount());
        assertEquals(8.0F, RVP_ThermobaricPreset.DEFAULT.condensationCloudParticleScale());
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
                  "pressure_wave_fade_duration_ticks": 0,
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
