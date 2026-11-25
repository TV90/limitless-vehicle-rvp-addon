package org.ywzj.rvp.maintenance;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.vehicle.BoneMaintenanceConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 回归完整队列、黄色受损、确定性预览与小样本配额，使用生产计划器。 */
class RVP_RepairPlannerTest {
    private final BoneMaintenanceConfig.ModuleRepair config = BoneMaintenanceConfig.ModuleRepair.defaults();

    @Test void thirtyPercentRoundsUpAndUsesTwoDeviceMinimumOnlyAboveTwoDamaged() {
        int[] expected = {0,1,1,2,2,2,2,3,3,3,3,4,4,4,5,5,5,6,6,6,6};
        for (int count = 0; count < expected.length; count++) {
            assertEquals(expected[count], config.deviceQuota(count), "damaged=" + count);
        }
        assertEquals(300, config.deviceQuota(1000));
    }

    @Test void defaultsKeepAllHealthyPartsInStableTypeThenPartOrder() {
        Map<String, Set<BoneModuleType>> modules = new LinkedHashMap<>();
        modules.put("aps", Set.of(BoneModuleType.APS));
        modules.put("engine", Set.of(BoneModuleType.ENGINE));
        modules.put("era-b", Set.of(BoneModuleType.ERA, BoneModuleType.JAMMER));
        modules.put("barrel-b", Set.of(BoneModuleType.BARREL));
        modules.put("barrel-a", Set.of(BoneModuleType.BARREL));
        modules.put("era-a", Set.of(BoneModuleType.ERA));
        modules.put("track", Set.of(BoneModuleType.TRACK));
        modules.put("repair", Set.of(BoneModuleType.MAINTENANCE));
        var order = RVP_RepairPlanner.defaults(modules,
                List.of("era-a", "era-b", "barrel-a", "barrel-b", "engine", "aps"), config);
        assertEquals(List.of("era-a", "era-b"), order.eraBones());
        assertEquals(List.of("barrel-a", "barrel-b", "engine", "aps"), order.deviceBones());
        assertEquals(new RVP_RepairPlanner.Order(List.of(), List.of()),
                RVP_RepairPlanner.select(order, Map.of(), config));
    }

    @Test void completingOldOrChangedQueuesPreservesOrderAndAppendsEveryMissingPart() {
        List<String> era = new ArrayList<>();
        for (int i = 0; i < 110; i++) era.add("era-" + i);
        var defaults = new RVP_RepairPlanner.Order(era, List.of("barrel", "engine", "aps"));
        var saved = new RVP_RepairPlanner.Order(List.of("era-91", "deleted", "era-91"),
                List.of("aps", "aps", "gone"));
        var result = RVP_RepairPlanner.complete(saved, defaults);
        assertEquals(110, result.eraBones().size());
        assertEquals("era-91", result.eraBones().get(0));
        assertEquals(List.of("aps", "barrel", "engine"), result.deviceBones());
        assertEquals(result, RVP_RepairPlanner.complete(result, defaults));
    }

    @Test void movingToFrontAndDraggingToEndNeverDropsAnotherEntry() {
        List<String> original = List.of("a", "b", "c", "d");
        assertEquals(List.of("c", "a", "b", "d"), RVP_RepairPlanner.move(original, "c", 0));
        assertEquals(List.of("a", "c", "d", "b"), RVP_RepairPlanner.move(original, "b", 3));
        assertEquals(original, RVP_RepairPlanner.move(original, "missing", 0));
        assertEquals(List.of("a", "b", "c", "d"), original);
    }

    @Test void healthyPrioritiesAreSkippedAndLightDamageMarkersConsumeRepairSlots() {
        var order = new RVP_RepairPlanner.Order(List.of(), List.of("healthy", "traverse", "engine", "barrel", "aps"));
        Map<String, Set<BoneModuleType>> damage = Map.of(
                "traverse", Set.of(BoneModuleType.TURRET_TRAVERSE_DAMAGED),
                "engine", Set.of(BoneModuleType.ENGINE_DAMAGED),
                "barrel", Set.of(BoneModuleType.BARREL_DAMAGED),
                "aps", Set.of(BoneModuleType.APS));
        var selected = RVP_RepairPlanner.select(order, damage, config);
        assertEquals(List.of("traverse", "engine"), selected.deviceBones());
        assertEquals(5, order.deviceBones().size());
    }

    @Test void eraAndDevicesHaveIndependentBudgetsAndPreviewIsDeterministic() {
        var order = new RVP_RepairPlanner.Order(List.of("e3", "e2", "e1", "e4", "healthy-era"),
                List.of("a", "b", "c", "d", "healthy-device"));
        Map<String, Set<BoneModuleType>> inactive = new LinkedHashMap<>();
        for (String bone : List.of("e1", "e2", "e3", "e4")) inactive.put(bone, Set.of(BoneModuleType.ERA));
        for (String bone : List.of("a", "b", "c", "d")) inactive.put(bone, Set.of(BoneModuleType.RADAR));
        var selected = RVP_RepairPlanner.select(order, inactive, config);
        assertEquals(List.of("e3"), selected.eraBones());
        assertEquals(List.of("a", "b"), selected.deviceBones());
        assertEquals(selected, RVP_RepairPlanner.select(order, inactive, config));
    }

    @Test void customRepairableTypesFilterBothPresetAndDamagedSelection() {
        var restricted = BoneMaintenanceConfig.ModuleRepair.parse(
                com.google.gson.JsonParser.parseString("{\"repairable_types\":[\"barrel\"],\"device_recover_chance\":0.3}"));
        var order = RVP_RepairPlanner.defaults(Map.of("a", Set.of(BoneModuleType.BARREL),
                "b", Set.of(BoneModuleType.APS), "e", Set.of(BoneModuleType.ERA)), List.of(), restricted);
        assertEquals(List.of("a"), order.deviceBones());
        assertTrue(order.eraBones().isEmpty());
        assertTrue(restricted.isRepairable(BoneModuleType.BARREL_DAMAGED));
        assertEquals(List.of("a"), RVP_RepairPlanner.select(order,
                Map.of("a", Set.of(BoneModuleType.BARREL_DAMAGED)), restricted).deviceBones());
    }
}
