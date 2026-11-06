package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionSettings;
import org.ywzj.rvp.client.visual.explosion.RVP_VehicleExplosionSettings.Parameter;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真正的F8目录、业务绑定及本地预设；不使用玩家配置或启动图形界面。 */
class RVP_VehicleExplosionTuningModuleTest {
    /** 本次测试独立的配置文件目录。 */
    @TempDir Path directory;

    @BeforeEach @AfterEach
    void reset() {
        // 调用业务重置，避免静态覆盖影响其它画面回归。
        RVP_VehicleExplosionSettings.reset();
    }

    @Test
    void allFiveGroupsAndTwentyEightControlsBindWithoutStartingOrSavingAnything() {
        var registry = registry();
        assertEquals(5, registry.modules().get(0).groups().size());
        assertEquals(28, registry.options().size());
        assertTrue(registry.snapshot().isEmpty());
        assertFalse(registry.search("screen_space", "程序大爆炸 热浪").isEmpty());
        assertTrue(registry.modules().get(0).groups().stream().allMatch(group -> group.actions().size() == 2));
        for (Parameter parameter : Parameter.values()) {
            var option = registry.options().get(key(parameter));
            assertNotNull(option);
            option.set(parameter.defaultValue);
            assertEquals(parameter.defaultValue, RVP_VehicleExplosionSettings.value(parameter));
            option.reset();
            assertTrue(option.overrideValue().isEmpty());
        }
    }

    @Test
    void presetRoundTripAndUndoPreserveOnlyExplicitEditsAndRejectBadValues() throws Exception {
        var registry = registry();
        registry.options().get(key(Parameter.RADIUS)).set(7.5);
        registry.options().get(key(Parameter.RAY_STEPS)).set(47.5);
        registry.options().get(key(Parameter.LOOP)).set(1);
        var edited = registry.snapshot();
        assertEquals(48, RVP_VehicleExplosionSettings.value(Parameter.RAY_STEPS));
        var store = new RVP_TuningPresetStore(directory.resolve("explosion.json"));
        store.save(registry);
        registry.restore(Map.of());
        assertEquals(0, store.load(registry));
        assertEquals(edited, registry.snapshot());
        assertThrows(IllegalArgumentException.class, () -> registry.restore(Map.of(key(Parameter.RADIUS), Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> RVP_VehicleExplosionSettings.set(Parameter.LOOP, 0.5));
        assertThrows(IllegalArgumentException.class, () -> RVP_VehicleExplosionSettings.set(Parameter.RAY_STEPS, 65));
        assertEquals(edited, registry.snapshot());
        registry.restore(Map.of());
        assertTrue(registry.snapshot().isEmpty());
        assertEquals(4.5, RVP_VehicleExplosionSettings.value(Parameter.RADIUS));
    }

    private static RVP_TuningRegistry registry() {
        var registry = new RVP_TuningRegistry();
        // 调用正式模块登记，不能让测试维护另一套参数和路径。
        registry.register(new RVP_VehicleExplosionTuningModule());
        registry.seal();
        return registry;
    }

    private static String key(Parameter parameter) { return "vehicle_explosion/" + parameter.group.id + "/" + parameter.id; }
}
