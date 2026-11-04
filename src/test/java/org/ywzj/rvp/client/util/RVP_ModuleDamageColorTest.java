package org.ywzj.rvp.client.util;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.vehicle.BoneModuleType;
import java.util.Set;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 实测需求回归：模块档位接管颜色、维修恢复、复合模块和未列装载具隔离。 */
class RVP_ModuleDamageColorTest {
    @Test void nativeBloodColorOnlyAppliesToVehiclesWithoutModules() {
        int nativeYellow = 0x80FFF000;
        assertEquals(nativeYellow, RVP_DamagedPartWireframeHelper.chooseFill(false, 2, nativeYellow));
        assertEquals(0, RVP_DamagedPartWireframeHelper.chooseFill(true, 0, nativeYellow));
        assertEquals(0x80FF9900, RVP_DamagedPartWireframeHelper.chooseFill(true, 1, nativeYellow));
        assertEquals(0x80FF0000, RVP_DamagedPartWireframeHelper.chooseFill(true, 2, nativeYellow));
    }

    @Test void unlistedLightMarkersStillProduceFirstStageAndRepairReturnsToNormal() {
        var bases = List.of(BoneModuleType.ENGINE, BoneModuleType.BARREL, BoneModuleType.TURRET_TRAVERSE);
        var lights = List.of(BoneModuleType.ENGINE_DAMAGED, BoneModuleType.BARREL_DAMAGED, BoneModuleType.TURRET_TRAVERSE_DAMAGED);
        for (int i = 0; i < bases.size(); i++) {
            var base = bases.get(i); var light = lights.get(i);
            assertEquals(1, RVP_DamagedPartWireframeHelper.stageFor(Set.of(base), type -> type != light));
            assertEquals(2, RVP_DamagedPartWireframeHelper.stageFor(Set.of(base), type -> type != base && type != light));
            assertEquals(0, RVP_DamagedPartWireframeHelper.stageFor(Set.of(base), type -> true));
        }
    }

    @Test void destroyedSingleStageAndMixedModulesUseWorstStage() {
        assertEquals(2, RVP_DamagedPartWireframeHelper.stageFor(Set.of(BoneModuleType.ERA), type -> false));
        assertEquals(2, RVP_DamagedPartWireframeHelper.stageFor(Set.of(BoneModuleType.BARREL, BoneModuleType.APS),
                type -> type != BoneModuleType.BARREL_DAMAGED && type != BoneModuleType.APS));
        assertEquals(0, RVP_DamagedPartWireframeHelper.stageFor(Set.of(), type -> false));
    }
}
