package org.ywzj.rvp.client.tuning;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ywzj.rvp.client.light.RVP_RocketPlumeManager;
import org.ywzj.rvp.light.RVP_RocketPlumeParameters;
import org.ywzj.rvp.light.RVP_RocketPlumeRuntimeTuning;
import org.ywzj.rvp.light.RVP_RocketPlumeRuntimeTuning.Parameter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖真实F8注册、预设写盘、逐帧几何和燃尽边界，不接触玩家配置或启动游戏。 */
class RVP_RocketPlumeTuningModuleTest {
    /** 本测试专用本地预设目录。 */
    @TempDir Path temporaryDirectory;

    @BeforeEach @AfterEach
    void reset() {
        // 调用正式清理入口，确保其它喷焰回归继续使用原有起点。
        RVP_RocketPlumeRuntimeTuning.reset();
    }

    @Test
    void registrationAddsSixSearchableGroupsWithoutFillingOverrides() {
        RVP_TuningRegistry registry = registry();
        assertEquals(6, registry.modules().get(0).groups().size());
        assertEquals(35, registry.options().size());
        assertTrue(registry.snapshot().isEmpty(), "登记和打开面板不能预填推荐值");
        assertFalse(registry.search("screen_space", "程序尾焰 亮节").isEmpty());
        assertFalse(registry.search("screen_space", "程序尾焰 燃尽").isEmpty());
        assertTrue(registry.modules().get(0).groups().stream().allMatch(group -> group.actions().size() == 2));
    }

    @Test
    void localPresetRoundTripsOnlyEditedValuesAndBadImportsLeaveStateIntact() throws IOException {
        RVP_TuningRegistry registry = registry();
        option(registry, Parameter.LENGTH_SCALE).set(1.75);
        option(registry, Parameter.CORE_BLUE).set(0.42);
        option(registry, Parameter.IGNITION_TICKS).set(4.5);
        var expected = registry.snapshot();
        RVP_TuningPresetStore store = new RVP_TuningPresetStore(temporaryDirectory.resolve("tail-flame.json"));
        // 调用正式本地保存和加载，确认数值和未覆盖状态都能往返。
        store.save(registry);
        RVP_RocketPlumeRuntimeTuning.reset();
        assertEquals(0, store.load(registry));
        assertEquals(expected, registry.snapshot());
        assertThrows(IllegalArgumentException.class, () -> registry.restore(Map.of(key(Parameter.TIP_POWER), Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> option(registry, Parameter.WIDTH_SCALE).set(0));
        assertThrows(IllegalArgumentException.class, () -> RVP_RocketPlumeRuntimeTuning.set(Parameter.ROOT_FADE, Double.POSITIVE_INFINITY));
        assertEquals(expected, registry.snapshot());
        option(registry, Parameter.CORE_BLUE).reset();
        assertEquals(0.07, option(registry, Parameter.CORE_BLUE).value());
        assertEquals(1.75, option(registry, Parameter.LENGTH_SCALE).value());
    }

    @Test
    void geometryEditsApplyBeforeVisibilityWithoutAnotherHeartbeatAndResetDoesNotAccumulate() {
        RVP_TuningRegistry registry = registry();
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(new Object(), 10);
        manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(0, 0, -1), 0.3F, 300, 1, false);
        var original = manager.collect(Vec3.ZERO, 0, plume -> true).get(0);
        option(registry, Parameter.LENGTH_SCALE).set(2);
        option(registry, Parameter.WIDTH_SCALE).set(0.5);
        option(registry, Parameter.AXIAL_OFFSET).set(1.25);
        // 调用真实逐帧采样，可见性判断应当收到修改后的尺寸和位置。
        var edited = manager.collect(Vec3.ZERO, 0, plume -> {
            assertEquals(6, plume.length(), 0.00001);
            assertEquals(0.225, plume.radius(), 0.00001);
            assertEquals(new Vec3(0, 0, -1.25), plume.position());
            return true;
        }).get(0);
        assertEquals(original.strength(), edited.strength());
        assertEquals(original.haloRadius(), edited.haloRadius());
        assertEquals(edited, manager.collect(Vec3.ZERO, 0, plume -> true).get(0));
        registry.restore(Map.of());
        assertEquals(original, manager.collect(Vec3.ZERO, 0, plume -> true).get(0));
    }

    @Test
    void previewUsesEditedTransitionCurvesAndStopsAtRealDeadlineWithoutHeartbeat() {
        RVP_TuningRegistry registry = registry();
        option(registry, Parameter.IGNITION_TICKS).set(10);
        option(registry, Parameter.BURNOUT_TICKS).set(10);
        option(registry, Parameter.LENGTH_RESPONSE).set(2);
        option(registry, Parameter.WIDTH_RESPONSE).set(1);
        option(registry, Parameter.BRIGHTNESS_RESPONSE).set(2);
        Object world = new Object();
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(world, 100);
        manager.upsertEnginePreview(UUID.randomUUID(), Vec3.ZERO, new Vec3(1, 0, 0), 0.3F, 300);
        assertTrue(manager.collect(Vec3.ZERO, 0, plume -> true).isEmpty());
        manager.beginWorld(world, 105);
        var halfway = manager.collect(Vec3.ZERO, 0, plume -> true).get(0);
        assertEquals(0.75, halfway.length(), 0.00001);
        assertEquals(0.225, halfway.radius(), 0.00001);
        assertEquals(0.25, halfway.transition(), 0.00001);
        assertEquals(1, halfway.strength(), "启停预览不能混入旧预览的线性降亮");
        assertEquals(halfway, manager.collect(Vec3.ZERO, 0, plume -> true).get(0), "暂停不推进过渡");
        manager.beginWorld(world, 135);
        assertEquals(halfway, manager.collect(Vec3.ZERO, 0, plume -> true).get(0), "两端对称的十刻曲线应得到同样画面");
        manager.beginWorld(world, 140);
        assertFalse(manager.hasActive());
        assertTrue(manager.collect(Vec3.ZERO, 0, plume -> true).isEmpty());
    }

    @Test
    void zeroTransitionsNeverDivideByZeroOrExtendAuthoritativeBurning() {
        RVP_TuningRegistry registry = registry();
        option(registry, Parameter.IGNITION_TICKS).set(0);
        option(registry, Parameter.BURNOUT_TICKS).set(0);
        assertEquals(1, RVP_RocketPlumeParameters.transition(0, 40));
        assertEquals(1, RVP_RocketPlumeParameters.transition(10, 0.01));
        assertEquals(0, RVP_RocketPlumeParameters.transition(10, 0));
        assertEquals(0, RVP_RocketPlumeParameters.transition(Double.NaN, 1));
        Object world = new Object();
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(world, 10);
        manager.upsertEngine(UUID.randomUUID(), Vec3.ZERO, new Vec3(1, 0, 0), 0.3F, 0, 1, 7, 1);
        assertEquals(1, manager.collect(Vec3.ZERO, 0, plume -> true).get(0).transition());
        manager.beginWorld(world, 11);
        assertFalse(manager.hasActive());
    }

    private static RVP_TuningRegistry registry() {
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        // 调用真实扩展模块，测试不复制另一份面板描述或参数绑定。
        registry.register(new RVP_RocketPlumeTuningModule());
        registry.seal();
        return registry;
    }

    private static String key(Parameter parameter) { return "rocket_plume/" + parameter.group.id + "/" + parameter.id; }

    private static RVP_TuningOption option(RVP_TuningRegistry registry, Parameter parameter) {
        // 调用正式目录查找，确保界面路径、预设键和业务字段保持对应。
        return registry.options().get(key(parameter));
    }
}
