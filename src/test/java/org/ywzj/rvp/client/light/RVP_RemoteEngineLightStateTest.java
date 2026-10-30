package org.ywzj.rvp.client.light;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteAmmoVisualState;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 真实燃尽优先于尾烟宽限，远端断流不能留下常亮环境光。 */
class RVP_RemoteEngineLightStateTest {
    /** 测试维度。 */
    private static final ResourceLocation DIMENSION = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @AfterEach
    void clear() { RVP_ClientRemoteAmmoVisualState.clear(); }

    @Test
    void staleSnapshotAndBurnDeadlineBothExtinguishLight() {
        RVP_ClientRemoteAmmoVisualState.replace(DIMENSION, Set.of(7), Map.of(7, 100), 100);
        assertTrue(RVP_ClientRemoteAmmoVisualState.isMotorBurningFresh(DIMENSION, 7, 105));
        assertFalse(RVP_ClientRemoteAmmoVisualState.isMotorBurningFresh(DIMENSION, 7, 111));
        RVP_ClientRemoteAmmoVisualState.replace(DIMENSION, Set.of(7), Map.of(7, 3), 100);
        assertTrue(RVP_ClientRemoteAmmoVisualState.isMotorBurningFresh(DIMENSION, 7, 102));
        assertFalse(RVP_ClientRemoteAmmoVisualState.isMotorBurningFresh(DIMENSION, 7, 103));
    }

    @Test
    void explicitBurnoutAndDimensionSwitchDoNotUseSmokeGrace() {
        RVP_ClientRemoteAmmoVisualState.replace(DIMENSION, Set.of(7), Map.of(7, 100), 100);
        RVP_ClientRemoteAmmoVisualState.replace(DIMENSION, Set.of(7), Map.of(), 101);
        assertFalse(RVP_ClientRemoteAmmoVisualState.isMotorBurningFresh(DIMENSION, 7, 101));
        RVP_ClientRemoteAmmoVisualState.replace(ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"),
                Set.of(7), Map.of(7, 100), 102);
        assertFalse(RVP_ClientRemoteAmmoVisualState.isMotorBurningFresh(DIMENSION, 7, 102));
    }
}
