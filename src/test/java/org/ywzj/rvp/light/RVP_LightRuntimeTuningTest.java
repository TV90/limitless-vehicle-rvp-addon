package org.ywzj.rvp.light;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.light.RVP_LightSourceType;
import org.ywzj.rvp.client.light.RVP_RocketPlumeManager;
import org.ywzj.rvp.client.light.RVP_ScreenLightManager;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Group;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Parameter;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 验证正式管理器的即时调参、预算、原始年龄和恢复能力，不启动游戏客户端。 */
class RVP_LightRuntimeTuningTest {
    @BeforeEach
    @AfterEach
    void resetTuning() {
        // 调用会话清理，避免本测试的全局覆盖污染其他原始预设测试。
        RVP_LightRuntimeTuning.reset();
    }

    @Test
    void defaultsKeepOriginalPresetsAndUnconfiguredSwitchesRespectUserConfig() {
        // 调用正式预设和调参入口对照默认值，确认覆盖/重置不会丢失原有画质开关的选择。
        for (Group group : new Group[] {Group.explosion, Group.muzzle, Group.engine}) {
            RVP_LightParameters original = switch (group) {
                case explosion -> RVP_LightParameters.explosion(8);
                case muzzle -> RVP_LightParameters.muzzle(125, false);
                default -> RVP_LightParameters.engine(300);
            };
            assertSame(original, RVP_LightRuntimeTuning.apply(group, original));
        }
        assertEquals(0, RVP_LightRuntimeTuning.getOrDefault(Group.bloom, Parameter.enabled, 0));
        RVP_LightRuntimeTuning.set(Group.bloom, Parameter.enabled, 1);
        assertEquals(1, RVP_LightRuntimeTuning.getOrDefault(Group.bloom, Parameter.enabled, 0));
        RVP_LightRuntimeTuning.reset(Group.bloom, Parameter.enabled);
        assertEquals(0, RVP_LightRuntimeTuning.getOrDefault(Group.bloom, Parameter.enabled, 0));
    }

    @Test
    void existingExplosionUpdatesImmediatelyWithoutRestartingAgeAndResetRestoresItExactly() {
        // 调用正式短光登记与采样，在同一年龄内修改参数、重复取样和重置，验证没有重播或累计倍率。
        Object world = new Object();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(world, 100);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 100);
        manager.tick(world, 102);
        var original = manager.collectVisible(Vec3.ZERO, 0.5F, 256, 8, light -> true).get(0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.brightness, 2);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.radiusScale, 3);
        var tuned = manager.collectVisible(Vec3.ZERO, 0.5F, 256, 8, light -> true).get(0);
        assertEquals(original.radius() * 3, tuned.radius());
        assertEquals(original.intensity() * 2, tuned.intensity(), 0.00001F);
        assertEquals(original.remainingTicks(), tuned.remainingTicks());
        assertEquals(original.green(), tuned.green());
        assertEquals(tuned, manager.collectVisible(Vec3.ZERO, 0.5F, 256, 8, light -> true).get(0));
        RVP_LightRuntimeTuning.reset();
        assertEquals(original, manager.collectVisible(Vec3.ZERO, 0.5F, 256, 8, light -> true).get(0));
    }

    @Test
    void radiusIsAppliedBeforeDistanceCullingAndBrightnessZeroCanRecoverExistingLight() {
        // 调用正式预算路径，检查半径扩大能保留边缘光，临时关亮度不会取消尚未过期的条目。
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(new Object(), 100);
        manager.addTransient(new Vec3(28, 0, 0), RVP_LightSourceType.EXPLOSION, null,
                RVP_LightParameters.explosion(4), 100);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 10, 8, light -> true).isEmpty());
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.radiusScale, 2);
        assertEquals(1, manager.collectVisible(Vec3.ZERO, 0, 10, 8, light -> true).size());
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.brightness, 0);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 10, 8, light -> true).isEmpty());
        assertEquals(1, manager.size());
        RVP_LightRuntimeTuning.reset(Group.explosion, Parameter.brightness);
        assertEquals(1, manager.collectVisible(Vec3.ZERO, 0, 10, 8, light -> true).size());
    }

    @Test
    void engineLightAndPlumeShareLiveRangeAndBoundsWhileGeometryAndPhaseStayUnchanged() {
        // 调用发动机阶段预设和两种正式管理器，共同验证远近边界、共享半径与原有喷焰几何。
        Object world = new Object();
        RVP_ScreenLightManager lights = new RVP_ScreenLightManager();
        RVP_RocketPlumeManager plumes = new RVP_RocketPlumeManager();
        lights.beginWorld(world, 100);
        plumes.beginWorld(world, 100);
        UUID key = UUID.randomUUID();
        lights.upsertPersistent(key, Vec3.ZERO, RVP_LightParameters.enginePhase(RVP_LightParameters.engine(300), true, 10));
        plumes.upsert(key, Vec3.ZERO, new Vec3(1, 0, 0), 0.3F, 300, 0.5F, false);
        var originalLight = lights.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true).get(0);
        var originalPlume = plumes.collect(Vec3.ZERO, 0, plume -> true).get(0);
        for (float scale : new float[] {0.05F, 2, 16}) {
            RVP_LightRuntimeTuning.set(Group.engine, Parameter.radiusScale, scale);
            var light = lights.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
            var plume = plumes.collect(Vec3.ZERO, 0, value -> true).get(0);
            assertEquals(Math.max(20, Math.min(500, originalLight.radius() * scale)), light.radius());
            assertEquals(light.radius(), plume.haloRadius());
            assertEquals(originalPlume.length(), plume.length());
            assertEquals(originalPlume.radius(), plume.radius());
            assertEquals(originalPlume.strength(), plume.strength());
            assertEquals(originalLight.intensity(), light.intensity());
        }
        RVP_LightRuntimeTuning.reset();
        assertEquals(originalLight, lights.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true).get(0));
        assertEquals(originalPlume, plumes.collect(Vec3.ZERO, 0, plume -> true).get(0));
    }

    @Test
    void groupsAndSingleParameterResetsDoNotDisturbOtherTuning() {
        // 调用分组/单项重置和导出快照，检查试调互不串组且旧快照不会被后续修改。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.brightness, 3);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.brightness, 0);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.haloIntensity, 8);
        var exported = RVP_LightRuntimeTuning.overrides();
        RVP_LightRuntimeTuning.reset(Group.engine, Parameter.brightness);
        assertEquals(1, RVP_LightRuntimeTuning.get(Group.engine, Parameter.brightness));
        assertEquals(8, RVP_LightRuntimeTuning.get(Group.engine, Parameter.haloIntensity));
        RVP_LightRuntimeTuning.reset(Group.engine, null);
        assertEquals(16, RVP_LightRuntimeTuning.get(Group.engine, Parameter.haloIntensity));
        assertEquals(3, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.brightness));
        assertEquals(8, exported.get(Group.engine).get(Parameter.haloIntensity));
        assertThrows(UnsupportedOperationException.class, () -> exported.get(Group.engine).put(Parameter.haloIntensity, 9F));
    }

    @Test
    void invalidInputsNeverReplaceLastValidValueOrExceedHdrSafetyBudget() {
        // 调用统一写入校验，模拟坏数值和错误分组，确认失败不覆盖上一次有效的核心亮度。
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.coreIntensity, 12000);
        for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1, 60001}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RVP_LightRuntimeTuning.set(Group.engine, Parameter.coreIntensity, value));
            assertEquals(12000, RVP_LightRuntimeTuning.get(Group.engine, Parameter.coreIntensity));
        }
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreIntensity, 1));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.bloom, Parameter.enabled, 0.5F));
        assertTrue(RVP_LightRuntimeTuning.overrides().getOrDefault(Group.explosion, java.util.Map.of()).isEmpty());
    }
}
