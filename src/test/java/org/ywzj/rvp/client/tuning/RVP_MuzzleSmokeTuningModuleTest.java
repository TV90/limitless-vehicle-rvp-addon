package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings;
import org.ywzj.rvp.client.visual.vehicle.RVP_DelayedMuzzleSmokeSettings.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证F8的真实业务绑定、速度单位、区间联动、撤销和JSON保存，不触碰玩家配置。 */
class RVP_MuzzleSmokeTuningModuleTest {
    /** JUnit独立目录，避免修改真实游戏配置。 */
    @TempDir Path temporaryDirectory;

    @BeforeEach
    @AfterEach
    void clearSettings() { RVP_DelayedMuzzleSmokeSettings.resetAll(); }

    @Test
    void moduleRegistersAllFourGroupsWithoutCreatingOverrides() {
        RVP_TuningRegistry registry = registry();
        assertEquals(4, registry.modules().get(0).groups().size());
        assertEquals(38, registry.options().size());
        assertEquals(Parameter.values().length, registry.options().size());
        assertTrue(registry.snapshot().isEmpty());
        assertFalse(registry.search("screen_space", "炮口 旋涡").isEmpty());
        assertEquals(2, registry.modules().get(0).groups().get(0).actions().size());
    }

    @Test
    void panelAndCommandShareValuesWithSecondsConvertedOnlyAtPanelBoundary() {
        RVP_TuningRegistry registry = registry();
        RVP_TuningOption speed = option(registry, Parameter.AXIAL_SPEED_MAX);
        assertEquals("格/秒", speed.unit());
        assertEquals(3, speed.value(), 1.0E-12);
        speed.set(1.5);
        assertEquals(0.075, Parameter.AXIAL_SPEED_MAX.doubleValue(), 1.0E-12);
        assertEquals(0.075, Parameter.AXIAL_SPEED_MIN.doubleValue(), 1.0E-12);
        // 调用指令实际使用的严格写入入口，面板必须立即读到同一份值。
        RVP_DelayedMuzzleSmokeSettings.set(Parameter.AXIAL_SPEED_MAX, 0.1);
        assertEquals(2, speed.value(), 1.0E-12);
        speed.reset();
        assertEquals(3, speed.value(), 1.0E-12);
        assertTrue(speed.overrideValue().isEmpty());
    }

    @Test
    void sliderRoundsWholeTicksAndSingleResetPreservesUnrelatedOverrides() {
        RVP_TuningRegistry registry = registry();
        option(registry, Parameter.DELAY_MIN).set(27.6);
        assertEquals(28, Parameter.DELAY_MIN.intValue());
        assertEquals(28, Parameter.DELAY_MAX.intValue());
        option(registry, Parameter.CURL_STRENGTH).set(0.002);
        option(registry, Parameter.DELAY_MIN).reset();
        assertEquals(10, Parameter.DELAY_MIN.intValue());
        assertEquals(0.002, Parameter.CURL_STRENGTH.doubleValue(), 1.0E-12);
        registry.restore(Map.of());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void restoreHandlesBothKeyOrdersAboveAndBelowDefaultRanges() {
        RVP_TuningRegistry registry = registry();
        for (double[] bounds : List.of(new double[] {100, 110}, new double[] {0, 5})) {
            for (boolean reverse : List.of(false, true)) {
                Map<String, Double> saved = new LinkedHashMap<>();
                saved.put(key(reverse ? Parameter.DELAY_MAX : Parameter.DELAY_MIN), bounds[reverse ? 1 : 0]);
                saved.put(key(reverse ? Parameter.DELAY_MIN : Parameter.DELAY_MAX), bounds[reverse ? 0 : 1]);
                registry.restore(saved);
                assertEquals(bounds[0], Parameter.DELAY_MIN.doubleValue());
                assertEquals(bounds[1], Parameter.DELAY_MAX.doubleValue());
                assertEquals(saved, registry.snapshot());
            }
        }
    }

    @Test
    void realPresetRoundTripRestoresOnlyOverridesAndKeepsDisplayUnits() throws IOException {
        RVP_TuningRegistry registry = registry();
        option(registry, Parameter.WIND_X).set(0.6);
        option(registry, Parameter.PARTICLE_LIFETIME_MIN).set(80);
        option(registry, Parameter.PARTICLE_LIFETIME_MAX).set(90);
        option(registry, Parameter.ALPHA).set(Parameter.ALPHA.defaultValue);
        Map<String, Double> saved = registry.snapshot();
        RVP_TuningPresetStore store = new RVP_TuningPresetStore(temporaryDirectory.resolve("white-smoke.json"));
        store.save(registry);
        assertTrue(Files.readString(store.path()).contains("muzzle_smoke/motion/windX"));
        registry.restore(Map.of());
        assertEquals(0, store.load(registry));
        assertEquals(saved, registry.snapshot());
        assertEquals(0.03, Parameter.WIND_X.doubleValue(), 1.0E-12);
        assertEquals(80, Parameter.PARTICLE_LIFETIME_MIN.intValue());
        assertEquals(90, Parameter.PARTICLE_LIFETIME_MAX.intValue());
        assertTrue(RVP_DelayedMuzzleSmokeSettings.override(Parameter.ALPHA).isPresent());
        assertTrue(RVP_DelayedMuzzleSmokeSettings.override(Parameter.GREY).isEmpty());
    }

    @Test
    void invalidInputDoesNotDestroyCurrentSessionOrAnExistingShotSnapshot() {
        RVP_TuningRegistry registry = registry();
        option(registry, Parameter.ALPHA).set(0.25);
        var shot = RVP_DelayedMuzzleSmokeSettings.snapshot();
        Map<String, Double> saved = registry.snapshot();
        assertThrows(IllegalArgumentException.class, () -> option(registry, Parameter.ALPHA).set(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> registry.restore(Map.of(key(Parameter.CURL_SCALE), 0.01)));
        assertEquals(saved, registry.snapshot());
        option(registry, Parameter.ALPHA).set(0.6);
        assertEquals(0.25, shot.value(Parameter.ALPHA));
        assertThrows(UnsupportedOperationException.class, () -> shot.values().put(Parameter.ALPHA, 1.0));
    }

    private static RVP_TuningRegistry registry() {
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        // 调用正式模块扩展回调，不用测试专用描述代替生产参数。
        RVP_MuzzleSmokeTuningModule.register(new RVP_RegisterTuningModulesEvent(registry));
        registry.seal();
        return registry;
    }

    private static String key(Parameter parameter) {
        return "muzzle_smoke/" + parameter.group.id + "/" + parameter.commandName;
    }

    private static RVP_TuningOption option(RVP_TuningRegistry registry, Parameter parameter) {
        return registry.options().get(key(parameter));
    }
}
