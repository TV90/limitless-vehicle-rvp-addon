package org.ywzj.rvp.client.visual.thermobaric;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RVP_ThermobaricPresetManagerTest {
    @Test
    void sparseResourcePatchUsesBuiltInDefaultsForMissingFields() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("rvp", "custom");
        Map<ResourceLocation, RVP_ThermobaricPreset> loaded =
                RVP_ThermobaricPresetManager.buildPresetMap(Map.of(
                        id, JsonParser.parseString("{\"max_clouds\":42}")));

        assertEquals(42, loaded.get(id).maxClouds());
        assertEquals(RVP_ThermobaricPreset.DEFAULT.maxFireballClouds(),
                loaded.get(id).maxFireballClouds());
    }

    @Test
    void invalidFieldFallsBackIndividuallyAndInvalidDocumentIsIgnored() {
        ResourceLocation partial = ResourceLocation.fromNamespaceAndPath("rvp", "partial");
        ResourceLocation invalid = ResourceLocation.fromNamespaceAndPath("rvp", "invalid");
        Map<ResourceLocation, RVP_ThermobaricPreset> loaded =
                RVP_ThermobaricPresetManager.buildPresetMap(Map.of(
                        partial, JsonParser.parseString(
                                "{\"max_clouds\":\"bad\",\"show_cloud\":false}"),
                        invalid, JsonParser.parseString("[]")));

        assertEquals(RVP_ThermobaricPreset.DEFAULT.maxClouds(), loaded.get(partial).maxClouds());
        assertFalse(loaded.get(partial).showCloud());
        assertFalse(loaded.containsKey(invalid));
    }
}
