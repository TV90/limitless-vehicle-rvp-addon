package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证首批面板绑定与已有命令、配置和冲击波生命周期使用同一份业务状态。 */
class RVP_ScreenSpaceTuningModuleTest {
    @BeforeEach @AfterEach
    void reset() {
        // 调用真实会话清理，隔离测试对其它光照/冲击波测试的影响。
        RVP_LightRuntimeTuning.reset();
        RVP_ShockwaveRuntimeTuning.reset();
    }

    private RVP_TuningRegistry registry() {
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        // 调用首批真实模块适配器，而不是在测试里复制一份参数定义。
        registry.register(new RVP_ScreenSpaceTuningModule());
        return registry;
    }

    @Test
    void everyUsableLightAndSphereParameterIsBoundAndDisabledComponentsStayAbsent() {
        RVP_TuningRegistry registry = registry();
        int expected = 0;
        for (var group : RVP_LightRuntimeTuning.Group.values()) {
            for (var parameter : RVP_LightRuntimeTuning.Parameter.values()) {
                if (parameter.supports(group)) {
                    expected++;
                    assertNotNull(registry.options().get("screen_space/" + group + "/" + parameter));
                }
            }
        }
        for (var parameter : RVP_ShockwaveRuntimeTuning.Parameter.values()) {
            if (parameter.supports(RVP_ShockwaveRuntimeTuning.Group.sphere)) {
                expected++;
                assertNotNull(registry.options().get("screen_space/shockwave_sphere/" + parameter));
            }
        }
        assertEquals(expected, registry.options().size());
        assertTrue(registry.options().keySet().stream().noneMatch(key -> key.contains("/ring/") || key.contains("/mach/")));
        assertTrue(registry.snapshot().isEmpty(), "仅仅打开面板不应制造覆盖或改变默认效果");
    }

    @Test
    void lightBindingSeesCommandChangesAndCanWriteBackWithoutSecondState() {
        RVP_TuningRegistry registry = registry();
        RVP_TuningOption option = registry.options().get("screen_space/explosion/brightness");
        assertEquals(20, option.value());
        // 调用指令使用的同一业务入口，面板应直接看到新值。
        RVP_LightRuntimeTuning.set(RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.brightness, 35);
        assertEquals(35, option.value());
        option.set(7);
        assertEquals(7, RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.explosion, RVP_LightRuntimeTuning.Parameter.brightness));
        option.reset();
        assertEquals(20, option.value());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void allBuiltInNumericSlidersUseDeclaredRangeLinearlyAndReachBothEndpoints() {
        RVP_TuningRegistry registry = registry();
        for (var entry : registry.options().entrySet()) {
            RVP_TuningOption option = entry.getValue();
            if (option.control() != RVP_TuningOption.Control.NUMBER) { continue; }
            assertEquals(option.min(), option.fromSlider(0), entry.getKey());
            assertEquals(option.max(), option.fromSlider(1), entry.getKey());
            assertEquals((option.min() + option.max()) / 2, option.fromSlider(0.5), 1.0E-10, entry.getKey());
            assertEquals(option.min(), option.fromSlider(-0.25), entry.getKey());
            assertEquals(option.max(), option.fromSlider(1.25), entry.getKey());
        }
        // 调用真实业务写入，验证0～256亮度的中点就是128，而不是之前的对数低值。
        RVP_TuningOption brightness = registry.options().get("screen_space/explosion/brightness");
        brightness.set(brightness.fromSlider(0.5));
        assertEquals(128, RVP_LightRuntimeTuning.get(RVP_LightRuntimeTuning.Group.explosion,
                RVP_LightRuntimeTuning.Parameter.brightness));
    }

    @Test
    void engineSwitchDefaultsToExistingConfigAndOnlyExplicitOverrideEnablesIt() {
        RVP_TuningRegistry registry = registry();
        RVP_TuningOption option = registry.options().get("screen_space/engine/enabled");
        assertEquals(RVP_LightingConfig.configuredMissileHaloEnabled() ? 1 : 0, option.value());
        option.set(1);
        assertTrue(RVP_LightingConfig.missileHaloEnabled());
        option.reset();
        assertEquals(RVP_LightingConfig.configuredMissileHaloEnabled(), RVP_LightingConfig.missileHaloEnabled());
    }

    @Test
    void shockwaveResetIsPerParameterAndExistingAnimationKeepsItsOriginalSnapshot() {
        RVP_TuningRegistry registry = registry();
        RVP_TuningOption speed = registry.options().get("screen_space/shockwave_sphere/speed");
        RVP_TuningOption refraction = registry.options().get("screen_space/shockwave_sphere/refraction");
        var before = RVP_ShockwaveRuntimeTuning.snapshot();
        speed.set(2);
        refraction.set(0.4);
        assertEquals(2, RVP_ShockwaveRuntimeTuning.snapshot().get(RVP_ShockwaveRuntimeTuning.Group.sphere,
                RVP_ShockwaveRuntimeTuning.Parameter.speed));
        assertEquals(1.2F, before.get(RVP_ShockwaveRuntimeTuning.Group.sphere, RVP_ShockwaveRuntimeTuning.Parameter.speed));
        speed.reset();
        assertEquals(1.2, speed.value());
        assertEquals(0.4, refraction.value());
        registry.restore(Map.of());
        assertTrue(registry.snapshot().isEmpty());
        assertEquals(1, refraction.value());
        assertFalse(RVP_ShockwaveRuntimeTuning.enabled(RVP_ShockwaveRuntimeTuning.Group.ring));
        assertFalse(RVP_ShockwaveRuntimeTuning.enabled(RVP_ShockwaveRuntimeTuning.Group.mach));
    }
}
