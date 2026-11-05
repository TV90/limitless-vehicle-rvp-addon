package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings;
import org.ywzj.rvp.client.visual.RVP_WreckSmokeDebugSettings.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实F8绑定、指令共用值、出生隔离和实际预设文件往返，不接触玩家配置。 */
class RVP_WreckSmokeTuningModuleTest {
    /** 测试专用预设目录。 */
    @TempDir Path temporaryDirectory;

    @BeforeEach
    @AfterEach
    void clearSettings() { RVP_WreckSmokeDebugSettings.resetAll(); }

    @Test
    void allEightGroupsRegisterWithoutInventingOverrides() {
        RVP_TuningRegistry registry = registry();
        assertEquals(8, registry.modules().get(0).groups().size());
        assertEquals(69, registry.options().size());
        assertEquals(Parameter.values().length, registry.options().size());
        assertTrue(registry.snapshot().isEmpty());
        assertFalse(registry.search("screen_space", "长烟 翻卷").isEmpty());
        assertFalse(registry.search("screen_space", "短烟 浓度").isEmpty());
        assertEquals(2, registry.modules().get(0).groups().get(0).actions().size());
        assertEquals(2, registry.modules().get(0).groups().get(4).actions().size());
    }

    @Test
    void commandAndPanelUseSameValueAndConvertOnlySpeeds() {
        RVP_TuningRegistry registry = registry();
        RVP_TuningOption speed = option(registry, Parameter.UPDRAFT);
        assertEquals("格/秒", speed.unit());
        assertEquals(5, speed.value(), 1.0E-12);
        speed.set(6);
        assertEquals(0.3, Parameter.UPDRAFT.value(), 1.0E-12);
        // 调用指令使用的真实写入方法，面板应立即显示同一份业务值。
        Parameter.UPDRAFT.set(0.2);
        assertEquals(4, speed.value(), 1.0E-12);
        assertEquals("格/刻²", option(registry, Parameter.SWAY).unit());
        speed.reset();
        assertTrue(speed.overrideValue().isEmpty());
        assertEquals(5, speed.value(), 1.0E-12);
    }

    @Test
    void panelRoundsTicksAndLinksRangesWhileCommandRejectsInversion() {
        RVP_TuningRegistry registry = registry();
        option(registry, Parameter.NEAR_LIFETIME_MIN).set(100.6);
        assertEquals(101, Parameter.NEAR_LIFETIME_MIN.intValue());
        assertEquals(101, Parameter.NEAR_LIFETIME_MAX.intValue());
        assertThrows(IllegalArgumentException.class, () -> Parameter.NEAR_LIFETIME_MAX.set(100));
        assertThrows(IllegalArgumentException.class, () -> Parameter.EMIT_PERIOD.set(10.5));
        option(registry, Parameter.NEAR_LIFETIME_MIN).reset();
        assertEquals(42, Parameter.NEAR_LIFETIME_MIN.intValue());
        assertEquals(101, Parameter.NEAR_LIFETIME_MAX.intValue());
        option(registry, Parameter.NEAR_INITIAL_UPDRAFT_MIN).set(4);
        assertEquals(0.2, Parameter.NEAR_INITIAL_UPDRAFT_MAX.value(), 1.0E-12);
        option(registry, Parameter.NEAR_INITIAL_UPDRAFT_MAX).reset();
        assertTrue(Parameter.NEAR_INITIAL_UPDRAFT_MIN.override().isEmpty());
    }

    @Test
    void restoresRangesInEitherOrderAndRejectsBadInputWithoutLosingCurrentState() {
        RVP_TuningRegistry registry = registry();
        for (double[] bounds : List.of(new double[] {100, 110}, new double[] {16, 20})) {
            for (boolean reverse : List.of(false, true)) {
                Map<String, Double> values = new LinkedHashMap<>();
                values.put(key(reverse ? Parameter.NEAR_LIFETIME_MAX : Parameter.NEAR_LIFETIME_MIN), bounds[reverse ? 1 : 0]);
                values.put(key(reverse ? Parameter.NEAR_LIFETIME_MIN : Parameter.NEAR_LIFETIME_MAX), bounds[reverse ? 0 : 1]);
                registry.restore(values);
                assertEquals(values, registry.snapshot());
            }
        }
        Map<String, Double> before = registry.snapshot();
        assertThrows(IllegalArgumentException.class, () -> option(registry, Parameter.NEAR_ALPHA_SCALE).set(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> registry.restore(Map.of(key(Parameter.LONG_CURL_SCALE), 0.01)));
        assertEquals(before, registry.snapshot());
        registry.restore(Map.of());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void shortBirthSnapshotsAndOtherNearSmokeDefaultsStayIndependent() {
        var defaults = RVP_WreckSmokeDebugSettings.defaults();
        Parameter.NEAR_EAST_WIND.set(-0.2);
        Parameter.NEAR_GREY.set(0.7);
        var firstBirth = RVP_WreckSmokeDebugSettings.snapshot();
        Parameter.NEAR_EAST_WIND.set(0.3);
        Parameter.NEAR_GREY.set(0.4);
        assertEquals(-0.2, firstBirth.value(Parameter.NEAR_EAST_WIND));
        assertEquals(0.7, firstBirth.value(Parameter.NEAR_GREY));
        assertEquals(0.057, defaults.value(Parameter.NEAR_EAST_WIND));
        assertEquals(0.125, defaults.value(Parameter.NEAR_GREY));
        assertThrows(UnsupportedOperationException.class, () -> firstBirth.values().put(Parameter.NEAR_GREY, 1.0));
        // 长烟实时视图沿用原有指令语义，不会被短烟出生快照锁定。
        Parameter.UPDRAFT.set(0.4);
        assertEquals(0.4, RVP_WreckSmokeDebugSettings.LIVE.value(Parameter.UPDRAFT));
    }

    @Test
    void realPresetRoundTripStoresOnlyExplicitOverridesAndDisplayUnits() throws IOException {
        RVP_TuningRegistry registry = registry();
        option(registry, Parameter.NEAR_EAST_WIND).set(-2);
        option(registry, Parameter.NEAR_LIFETIME_MIN).set(90);
        option(registry, Parameter.NEAR_LIFETIME_MAX).set(120);
        option(registry, Parameter.CORE_ALPHA_SCALE).set(Parameter.CORE_ALPHA_SCALE.defaultValue);
        Map<String, Double> saved = registry.snapshot();
        RVP_TuningPresetStore store = new RVP_TuningPresetStore(temporaryDirectory.resolve("wreck-smoke.json"));
        store.save(registry);
        assertTrue(Files.readString(store.path()).contains("wreck_smoke/near_flow/nearEastWind"));
        registry.restore(Map.of());
        assertEquals(0, store.load(registry));
        assertEquals(saved, registry.snapshot());
        assertEquals(-0.1, Parameter.NEAR_EAST_WIND.value(), 1.0E-12);
        assertTrue(Parameter.CORE_ALPHA_SCALE.override().isPresent());
        assertTrue(Parameter.LONG_FADE_IN_TICKS.override().isEmpty());
    }

    private static RVP_TuningRegistry registry() {
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        // 调用正式MOD扩展回调，不使用测试专用参数目录。
        RVP_WreckSmokeTuningModule.register(new RVP_RegisterTuningModulesEvent(registry));
        registry.seal();
        return registry;
    }

    private static String key(Parameter parameter) { return "wreck_smoke/" + parameter.group.id + "/" + parameter.commandName; }
    private static RVP_TuningOption option(RVP_TuningRegistry registry, Parameter parameter) { return registry.options().get(key(parameter)); }
}
