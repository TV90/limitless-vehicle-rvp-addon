package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings;
import org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings.Parameter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.client.visual.cookoff.RVP_WreckCookoffSettings.Parameter.*;

/** 验证F8真实业务绑定、命令共用、单位、配对区间和磁盘预设，不触碰玩家配置。 */
class RVP_WreckCookoffTuningModuleTest {
    /** 测试专用预设目录。 */
    @TempDir Path temporaryDirectory;

    @BeforeEach @AfterEach
    void resetSettings() { RVP_WreckCookoffSettings.reset(); }

    @Test
    void registersEightGroupsAndAllParametersWithoutCreatingOverrides() {
        RVP_TuningRegistry registry = registry();
        assertEquals(8, registry.modules().get(0).groups().size());
        assertEquals(73, registry.options().size());
        assertEquals(Parameter.values().length, registry.options().size());
        assertTrue(registry.snapshot().isEmpty());
        assertFalse(registry.search("screen_space", "殉燃 冷却").isEmpty());
        assertEquals(2, registry.modules().get(0).groups().get(0).actions().size());
        assertTrue(registry.modules().get(0).groups().get(0).actions().get(0).showWorldAfter());
        assertEquals(RVP_TuningOption.Control.TOGGLE, option(registry, NATURAL_TAIL).control());
    }

    @Test
    void panelAndCommandUseSameValuesWithVelocityConvertedAtPanelBoundary() {
        RVP_TuningRegistry registry = registry();
        RVP_TuningOption speed = option(registry, SPARK_SPEED_MIN);
        assertEquals("格/秒", speed.unit());
        assertEquals(9, speed.value(), 1.0E-10);
        speed.set(30);
        assertEquals(1.5, SPARK_SPEED_MIN.value(), 1.0E-10);
        assertEquals(1.5, SPARK_SPEED_MAX.value(), 1.0E-10);
        // 调用生产指令的同一写入入口，面板立即反映新值。
        RVP_WreckCookoffSettings.set(SPARK_SPEED_MIN, 0.5);
        assertEquals(10, speed.value(), 1.0E-10);
        option(registry, FLAME_LIFETIME_MAX).set(29.7);
        assertEquals(30, FLAME_LIFETIME_MAX.value());
        option(registry, FLAME_LIFETIME_MIN).set(35);
        assertEquals(35, FLAME_LIFETIME_MAX.value());
    }

    @Test
    void realPresetRestoresAllPairOrdersAndSavesOnlyExplicitOverrides() throws IOException {
        RVP_TuningRegistry registry = registry();
        for (boolean reverse : new boolean[] {false, true}) {
            for (double[] pair : new double[][] {{100, 110}, {4, 5}}) {
                Map<String, Double> values = new LinkedHashMap<>();
                values.put(key(reverse ? SPARK_LIFETIME_MAX : SPARK_LIFETIME_MIN), pair[reverse ? 1 : 0]);
                values.put(key(reverse ? SPARK_LIFETIME_MIN : SPARK_LIFETIME_MAX), pair[reverse ? 0 : 1]);
                values.put(key(WIND_X), 1.2);
                values.put(key(NATURAL_TAIL), 1.0);
                values.put(key(SPARK_COOLING), SPARK_COOLING.defaultValue);
                // 调用正式目录恢复；范围在默认区间两侧，不能依赖JSON键顺序。
                registry.restore(values);
                assertEquals(pair[0], SPARK_LIFETIME_MIN.value());
                assertEquals(pair[1], SPARK_LIFETIME_MAX.value());
                assertEquals(values, registry.snapshot());
                RVP_TuningPresetStore store = new RVP_TuningPresetStore(temporaryDirectory.resolve("cookoff.json"));
                store.save(registry);
                registry.restore(Map.of());
                assertEquals(0, store.load(registry));
                assertEquals(values, registry.snapshot());
                assertEquals(0.06, WIND_X.value(), 1.0E-10);
                assertTrue(option(registry, SPARK_BRIGHTNESS).overrideValue().isEmpty());
            }
        }
    }

    @Test
    void asymmetricConeLimitsAndSingleResetNeverProduceInvalidPeerValues() {
        RVP_TuningRegistry registry = registry();
        option(registry, ROOF_CONE_TIP).set(2.5);
        option(registry, ROOF_CONE_BASE).set(1.5);
        assertEquals(1.5, ROOF_CONE_BASE.value());
        option(registry, ROOF_CONE_TIP).reset();
        assertEquals(ROOF_CONE_BASE.defaultValue, ROOF_CONE_BASE.value());
        assertEquals(ROOF_CONE_TIP.defaultValue, ROOF_CONE_TIP.value());
        option(registry, ROOF_CONE_TIP).set(0.02);
        assertEquals(0.02, ROOF_CONE_BASE.value());
        option(registry, ROOF_CONE_BASE).reset();
        assertTrue(option(registry, ROOF_CONE_TIP).overrideValue().isEmpty());
    }

    @Test
    void rejectsInvalidInputAtomicallyAndKeepsExistingParticleSnapshot() {
        RVP_TuningRegistry registry = registry();
        option(registry, SPARK_COOLING).set(0.7);
        var particle = RVP_WreckCookoffSettings.snapshot();
        Map<String, Double> previous = registry.snapshot();
        assertThrows(IllegalArgumentException.class, () -> option(registry, NATURAL_TAIL).set(0.5));
        assertThrows(IllegalArgumentException.class, () -> option(registry, SPARK_GRAVITY).set(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> registry.restore(Map.of(key(SPARK_COOLING), 2.0)));
        assertEquals(previous, registry.snapshot());
        option(registry, SPARK_COOLING).set(1);
        assertEquals(0.7, particle.value(SPARK_COOLING));
        registry.restore(previous);
        assertEquals(previous, registry.snapshot());
    }

    private static RVP_TuningRegistry registry() {
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        // 调用生产模块的真实扩展回调，不用测试专用参数代替。
        RVP_WreckCookoffTuningModule.register(new RVP_RegisterTuningModulesEvent(registry));
        registry.seal();
        return registry;
    }

    private static String key(Parameter parameter) {
        return "wreck_cookoff/" + parameter.group.id + "/" + parameter.commandName;
    }

    private static RVP_TuningOption option(RVP_TuningRegistry registry, Parameter parameter) {
        return registry.options().get(key(parameter));
    }
}
