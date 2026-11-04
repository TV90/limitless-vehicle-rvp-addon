package org.ywzj.rvp.client.render.remotevisibility;

import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.config.RVP_CommonConfig.TerrainFogMode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_RemoteVehicleTerrainFogHandlerTest {
    @Test
    void enabledSettingRemovesOnlyNormalTerrainFog() {
        assertTrue(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, TerrainFogMode.ALWAYS, false, FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, TerrainFogMode.ALWAYS, false, FogRenderer.FogMode.FOG_SKY, FogType.NONE, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, TerrainFogMode.ALWAYS, false, FogRenderer.FogMode.FOG_TERRAIN, FogType.WATER, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, TerrainFogMode.ALWAYS, false, FogRenderer.FogMode.FOG_TERRAIN, FogType.LAVA, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, TerrainFogMode.ALWAYS, false, FogRenderer.FogMode.FOG_TERRAIN, FogType.POWDER_SNOW, false));
    }

    @Test
    void totalSwitchOrTerrainFogSwitchCanDisableRemoval() {
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                false, TerrainFogMode.ALWAYS, false, FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, TerrainFogMode.NEVER, true, FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, false));
    }

    @Test
    void blindnessAndDarknessPolicyKeepsRestrictedFog() {
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, TerrainFogMode.ALWAYS, false, FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, true));
    }
    /** DH 模式逐次读取实际地形开关，安装但关闭渲染时仍恢复雾，RVP 兼容开关不代替 DH。 */
    @Test
    void dhOnlyModeRestoresFogWhenDistantRenderingStops() {
        assertTrue(RVP_CommonConfig.getRemoteVehicleTerrainFogMode() == TerrainFogMode.DH_ENABLED);
        for (boolean dhEnabled : new boolean[]{false, true, false, true}) {
            org.junit.jupiter.api.Assertions.assertEquals(dhEnabled,
                    RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                            true, TerrainFogMode.DH_ENABLED, dhEnabled,
                            FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, false));
        }
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, TerrainFogMode.DH_ENABLED, true,
                FogRenderer.FogMode.FOG_TERRAIN, FogType.WATER, false));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                true, TerrainFogMode.DH_ENABLED, true,
                FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, true));
        assertFalse(RVP_RemoteVehicleTerrainFogHandler.shouldRemoveTerrainFog(
                false, TerrainFogMode.DH_ENABLED, true,
                FogRenderer.FogMode.FOG_TERRAIN, FogType.NONE, false));
    }

}
