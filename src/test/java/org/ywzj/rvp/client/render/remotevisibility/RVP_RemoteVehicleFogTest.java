package org.ywzj.rvp.client.render.remotevisibility;

import com.mojang.blaze3d.shaders.FogShape;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.render.remotevisibility.RVP_RemoteVehicleFog.FogParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RVP_RemoteVehicleFogTest {
    @Test
    void normalTerrainFogOnlyExtendsEnd() {
        FogParameters original = new FogParameters(64.0F, 256.0F,
                0.1F, 0.2F, 0.3F, 0.4F, FogShape.CYLINDER);
        FogParameters remote = RVP_RemoteVehicleFog.forRemotePass(original, 2_048.0D, false);

        assertEquals(original.start(), remote.start());
        assertEquals(2_048.0F, remote.end());
        assertEquals(original.red(), remote.red());
        assertEquals(original.green(), remote.green());
        assertEquals(original.blue(), remote.blue());
        assertEquals(original.alpha(), remote.alpha());
        assertEquals(original.shape(), remote.shape());
    }

    @Test
    void existingLongerFogEndIsNotShortened() {
        FogParameters original = new FogParameters(32.0F, 4_096.0F,
                0.1F, 0.2F, 0.3F, 1.0F, FogShape.SPHERE);

        assertEquals(original, RVP_RemoteVehicleFog.forRemotePass(original, 2_048.0D, false));
    }

    @Test
    void restrictedVisibilityKeepsOriginalParameters() {
        FogParameters original = new FogParameters(0.0F, 5.0F,
                0.5F, 0.6F, 0.7F, 1.0F, FogShape.SPHERE);

        assertSame(original, RVP_RemoteVehicleFog.forRemotePass(original, 4_096.0D, true));
    }

    @Test
    void invalidFarPlaneKeepsOriginalParameters() {
        FogParameters original = new FogParameters(0.0F, 128.0F,
                0.5F, 0.6F, 0.7F, 1.0F, FogShape.SPHERE);

        assertSame(original, RVP_RemoteVehicleFog.forRemotePass(original, Double.NaN, false));
    }
}
