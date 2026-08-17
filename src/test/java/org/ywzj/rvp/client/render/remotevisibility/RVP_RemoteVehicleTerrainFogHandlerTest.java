package org.ywzj.rvp.client.render.remotevisibility;

import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_RemoteVehicleTerrainFogHandlerTest {
    @Test
    void enabledSettingRemovesOnlyNormalTerrainFog() {
        assertTrue(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, true, FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, true, FogRenderer.FogMode.FOG_SKY, FogType.NONE, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, true, FogRenderer.FogMode.FOG_TERRAIN, FogType.WATER, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, true, FogRenderer.FogMode.FOG_TERRAIN, FogType.LAVA, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, true, FogRenderer.FogMode.FOG_TERRAIN, FogType.POWDER_SNOW, false));
    }

    @Test
    void totalSwitchOrTerrainFogSwitchCanDisableRemoval() {
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                false, true, FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, false, FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, false));
    }

    @Test
    void blindnessAndDarknessPolicyKeepsRestrictedFog() {
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, true, FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, true));
    }
}
