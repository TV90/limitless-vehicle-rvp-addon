package org.ywzj.rvp.weapon.damage;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureConfigManager;
import org.ywzj.rvp.vehicle.BoneModuleType;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 无配置不授予装备，显式列装、同骨能力合并和配置重载均使用生产解析入口。 */
class RVP_ExplicitEquipmentConfigTest {
    private static final ResourceLocation ID = new ResourceLocation("ywzj_vehicle", "test_config");
    private static JsonElement json(String text) { return JsonParser.parseString(text); }

    @Test void unconfiguredNativeVehicleHasNoRepairOrModules() {
        var manager = new RVP_VehicleHitboxFactorManager();
        manager.applyFromJsonMap(Map.of(ID, json("{}")));
        assertNull(manager.resolveMaintenanceModule(ID));
        assertTrue(manager.modulesForVehicleId(ID).isEmpty());
        manager.applyFromJsonMap(Map.of(ID, json("{\"bone_modules\":{\"body\":{}}}")));
        assertTrue(manager.modulesForVehicleId(ID).isEmpty());
    }

    @Test void explicitMaintenanceDeclarationKeepsNumericDefaultsAndCoexistsWithEcm() {
        var manager = new RVP_VehicleHitboxFactorManager();
        manager.applyFromJsonMap(Map.of(ID, json("""
            {"bone_modules":{"__vehicle__":{"modules":["MAINTENANCE"]}},"ecm_active":{}}
            """)));
        var binding = manager.resolveMaintenanceModule(ID);
        assertNotNull(binding);
        assertEquals(80, binding.config().useTimeTicks());
        assertEquals(300, binding.config().waitTimeTicks());
        assertEquals(0.25f, binding.config().healPerTickPercent());
        var modules = manager.modulesForVehicleId(ID).get("__vehicle__");
        assertTrue(modules.contains(BoneModuleType.MAINTENANCE));
        assertTrue(modules.contains(BoneModuleType.ECM_ACTIVE));
    }

    @Test void undeclaredChildObjectDoesNotEquipAndReloadRemovesOldCapabilities() {
        var manager = new RVP_VehicleHitboxFactorManager();
        manager.applyFromJsonMap(Map.of(ID, json("""
            {"bone_modules":{"body":{"maintenance":{}}}}
            """)));
        assertNull(manager.resolveMaintenanceModule(ID));
        manager.applyFromJsonMap(Map.of(ID, json("{\"maintenance\":{\"wait_time_ticks\":180}}")));
        assertEquals(180, manager.resolveMaintenanceModule(ID).config().waitTimeTicks());
        var snapshot = manager.modulesForVehicleId(ID);
        assertSame(snapshot, manager.modulesForVehicleId(ID));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        manager.applyFromJsonMap(Map.of(ID, json("{}")));
        assertNull(manager.resolveMaintenanceModule(ID));
        assertTrue(manager.modulesForVehicleId(ID).isEmpty());
    }

    @Test void countermeasureLoaderRequiresExplicitParentAndPositiveCapacity() {
        var manager = new RVP_CountermeasureConfigManager();
        manager.applyFromJsonMap(Map.of(ID, json("{\"flare\":{\"total\":32,\"launcher_parts\":[\"cm\"]}}")));
        assertNull(manager.resolve(ID));
        manager.applyFromJsonMap(Map.of(ID, json("{\"countermeasure\":{\"flare\":{}}}")));
        assertNull(manager.resolve(ID));
        manager.applyFromJsonMap(Map.of(ID, json("{\"countermeasure\":{\"flare\":{\"total\":4,\"launcher_parts\":[\"cm\"]}}}")));
        assertEquals(4, manager.resolve(ID).getFlare().getTotal());
    }
}
