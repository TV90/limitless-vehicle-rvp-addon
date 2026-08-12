package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_VisualEffectDataTest {
    /** 测试使用的 JSON 解析器。 */
    private static final Gson GSON = new Gson();

    @Test
    void omittedVisualEffectDataKeepsLegacyBehavior() {
        RVP_DetonateData detonateData = GSON.fromJson("{}", RVP_DetonateData.class);

        assertTrue(detonateData.getVisualEffectData().isEmpty());
    }

    @Test
    void defaultsAreDisabledAndSafe() {
        RVP_VisualEffectData data = GSON.fromJson("{}", RVP_VisualEffectData.class);

        assertFalse(data.isEnabled());
        assertTrue(data.getEffectType().isEmpty());
        assertEquals(ResourceLocation.fromNamespaceAndPath("rvp", "default"), data.getPreset());
        assertEquals(1.0F, data.getScale());
        assertEquals(1.0F, data.getDensity());
        assertEquals(-1, data.getDurationTicks());
        assertEquals(768.0D, data.getBroadcastRange());
        assertFalse(data.isExperimentalDynamicParticleBudget());
        assertEquals("{}", data.copyPresetData().toString());
    }

    @Test
    void experimentalDynamicParticleBudgetDefaultsOffAndOnlyExplicitTrueEnablesIt() {
        RVP_VisualEffectData missing = GSON.fromJson("{}", RVP_VisualEffectData.class);
        RVP_VisualEffectData empty = GSON.fromJson(
                "{\"experimental\":{}}", RVP_VisualEffectData.class);
        RVP_VisualEffectData nullObject = GSON.fromJson(
                "{\"experimental\":null}", RVP_VisualEffectData.class);
        RVP_VisualEffectData disabled = GSON.fromJson(
                "{\"experimental\":{\"dynamic_particle_budget\":false}}",
                RVP_VisualEffectData.class);
        RVP_VisualEffectData enabled = GSON.fromJson(
                "{\"experimental\":{\"dynamic_particle_budget\":true}}",
                RVP_VisualEffectData.class);
        RVP_VisualEffectData unknown = GSON.fromJson(
                "{\"experimental\":{\"unknown_experiment\":true}}",
                RVP_VisualEffectData.class);

        assertFalse(missing.isExperimentalDynamicParticleBudget());
        assertFalse(empty.isExperimentalDynamicParticleBudget());
        assertFalse(nullObject.isExperimentalDynamicParticleBudget());
        assertFalse(disabled.isExperimentalDynamicParticleBudget());
        assertTrue(enabled.isExperimentalDynamicParticleBudget());
        assertFalse(unknown.isExperimentalDynamicParticleBudget());
    }

    @Test
    void nonNegativeValuesHaveNoArtificialUpperClampAndInvalidLocationsAreRejected() {
        RVP_VisualEffectData data = GSON.fromJson("""
                {
                  "enabled": true,
                  "effect_type": "invalid id",
                  "preset": "also invalid",
                  "scale": 99.0,
                  "density": -4.0,
                  "duration_ticks": 9999,
                  "broadcast_range": 1.0
                }
                """, RVP_VisualEffectData.class);

        assertTrue(data.isEnabled());
        assertTrue(data.getEffectType().isEmpty());
        assertEquals(ResourceLocation.fromNamespaceAndPath("rvp", "default"), data.getPreset());
        assertEquals(99.0F, data.getScale());
        assertEquals(0.0F, data.getDensity());
        assertEquals(9999, data.getDurationTicks());
        assertEquals(1.0D, data.getBroadcastRange());
    }

    @Test
    void configuredListIsExposedAsNonNullSnapshot() {
        RVP_DetonateData detonateData = GSON.fromJson("""
                {"visual_effect_data":[null,{"enabled":true,"effect_type":"rvp:thermobaric"}]}
                """, RVP_DetonateData.class);

        assertEquals(1, detonateData.getVisualEffectData().size());
        assertEquals(ResourceLocation.fromNamespaceAndPath("rvp", "thermobaric"),
                detonateData.getVisualEffectData().get(0).getEffectType().orElseThrow());
    }
}
