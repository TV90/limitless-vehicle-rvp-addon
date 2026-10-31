package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真正的参数目录、快照恢复、搜索与滑条换算，不启动 Minecraft 窗口。 */
class RVP_TuningRegistryTest {
    /** 测试业务存储，区分默认和显式覆盖，模拟扩展自己的写入校验。 */
    static final class Binding {
        /** 随时可以变化的底层配置默认值。 */
        double defaultValue = 1;
        /** 当前业务覆盖；空表示沿用底层配置。 */
        OptionalDouble override = OptionalDouble.empty();
        /** 模拟实际业务层拒绝某个合法范围内的值。 */
        double rejected = Double.NaN;

        RVP_TuningOption option(String id) {
            return new RVP_TuningOption(id, "亮度", "画面照亮强度", "倍率", RVP_TuningOption.Control.NUMBER,
                    0, 60000, 1, () -> defaultValue, () -> override, value -> {
                if (value == rejected) { throw new IllegalArgumentException("业务拒绝此值"); }
                override = OptionalDouble.of(value);
            }, () -> override = OptionalDouble.empty());
        }
    }

    static RVP_TuningModule module(String id, String title, RVP_TuningOption... options) {
        return new RVP_TuningModule() {
            @Override public String id() { return id; }
            @Override public String title() { return title; }
            @Override public String description() { return "测试分类"; }
            @Override public List<Group> groups() {
                return List.of(new Group("global", "总设置", "本机画面", List.of(options), List.of()));
            }
        };
    }

    @Test
    void bindingsFollowExternalChangesAndResetReturnsToDynamicConfigDefault() {
        Binding binding = new Binding();
        RVP_TuningOption option = binding.option("brightness");
        assertEquals(1, option.value());
        binding.defaultValue = 0.5;
        assertEquals(0.5, option.value());
        binding.override = OptionalDouble.of(12);
        assertEquals(12, option.value());
        option.set(8);
        assertEquals(8, binding.override.orElseThrow());
        option.reset();
        assertTrue(option.overrideValue().isEmpty());
        assertEquals(0.5, option.value());
    }

    @Test
    void invalidNumericAndFractionalToggleInputsNeverReachBusinessStorage() {
        Binding binding = new Binding();
        RVP_TuningOption option = binding.option("brightness");
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 60001}) {
            assertThrows(IllegalArgumentException.class, () -> option.set(invalid));
            assertTrue(binding.override.isEmpty());
        }
        RVP_TuningOption toggle = new RVP_TuningOption("enabled", "总开关", "本机开关", "开关",
                RVP_TuningOption.Control.TOGGLE, 0, 1, 0, () -> 0, () -> binding.override,
                value -> binding.override = OptionalDouble.of(value), () -> binding.override = OptionalDouble.empty());
        assertThrows(IllegalArgumentException.class, () -> toggle.set(0.5));
        toggle.set(1);
        assertEquals(1, toggle.value());
        assertThrows(IllegalArgumentException.class, () -> toggle.set(-1));
        assertEquals(1, toggle.value());
    }

    @Test
    void wideSliderRetainsPrecisionMonotonicityAndExactEndpoints() {
        RVP_TuningOption option = new Binding().option("brightness");
        assertEquals(0, option.fromSlider(0));
        assertEquals(60000, option.fromSlider(1));
        double previous = -1;
        for (int index = 0; index <= 1000; index++) {
            double position = index / 1000.0;
            double value = option.fromSlider(position);
            assertTrue(value > previous);
            assertEquals(position, option.toSlider(value), 1.0E-12);
            previous = value;
        }
        assertTrue(option.fromSlider(0.1) < 3, "滑条前段应该能精调0到3，而不是一下跳到6000");
        option.set(0.00001);
        assertEquals(0.00001, option.value());
    }

    @Test
    void globalSearchCombinesChineseNamesKeysAndMultipleWordsAcrossCategories() {
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        registry.register(module("lighting", "屏幕空间特效", new Binding().option("brightness")));
        registry.register(module("future", "未来扩展", new Binding().option("futureBrightness")));
        assertEquals("lighting", registry.search("lighting", "").get(0).module().id());
        assertEquals(1, registry.search("lighting", "").size());
        assertEquals(2, registry.search("lighting", "亮度").size());
        assertEquals("future", registry.search("lighting", "未来 BRIGHTNESS").get(0).module().id());
        assertTrue(registry.search("lighting", "未来 不存在").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> registry.modules().clear());
    }

    @Test
    void invalidRegistrationIsAtomicAndDirectoryRejectsLateExtensions() {
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        registry.register(module("first", "首个模块", new Binding().option("one")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(module("first", "重复模块")));
        Binding binding = new Binding();
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(module("second", "重复参数", binding.option("same"), binding.option("same"))));
        assertEquals(1, registry.modules().size());
        assertEquals(1, registry.options().size());
        registry.seal();
        assertThrows(IllegalStateException.class, () -> registry.register(module("late", "过晚注册")));
    }

    @Test
    void snapshotsKeepAbsentOverridesAbsentAndRejectInvalidValuesBeforeResetting() {
        Binding first = new Binding();
        Binding second = new Binding();
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        registry.register(module("lighting", "光照", first.option("first"), second.option("second")));
        first.override = OptionalDouble.of(1);
        Map<String, Double> original = registry.snapshot();
        assertEquals(Map.of("lighting/global/first", 1D), original);
        first.override = OptionalDouble.of(20);
        second.override = OptionalDouble.of(30);
        registry.restore(original);
        assertEquals(1, first.override.orElseThrow());
        assertTrue(second.override.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.restore(Map.of("lighting/global/second", -2D)));
        assertThrows(IllegalArgumentException.class, () -> registry.restore(Map.of("missing/key/entry", 2D)));
        assertEquals(original, registry.snapshot());
    }

    @Test
    void businessWriteFailureRollsBackEarlierWritesAndDoesNotLeavePartialPreset() {
        Binding first = new Binding();
        Binding second = new Binding();
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        registry.register(module("lighting", "光照", first.option("first"), second.option("second")));
        first.override = OptionalDouble.of(3);
        second.rejected = 99;
        Map<String, Double> previous = registry.snapshot();
        var changes = new java.util.LinkedHashMap<String, Double>();
        changes.put("lighting/global/first", 42D);
        changes.put("lighting/global/second", 99D);
        assertThrows(IllegalArgumentException.class, () -> registry.restore(changes));
        assertEquals(previous, registry.snapshot());
        assertTrue(second.override.isEmpty());
    }
}
