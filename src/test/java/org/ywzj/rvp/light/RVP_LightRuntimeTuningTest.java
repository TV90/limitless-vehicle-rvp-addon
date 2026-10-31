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
    void defaultsApplySourceSpecificBrightnessAndRangesWithoutOverrides() {
        // 调用正式预设和调参入口对照默认值，确认覆盖/重置不会丢失原有画质开关的选择。
        for (Group group : new Group[] {Group.explosion, Group.muzzle, Group.engine}) {
            RVP_LightParameters original = switch (group) {
                case explosion -> RVP_LightParameters.explosion(8);
                case muzzle -> RVP_LightParameters.muzzle(125, false);
                default -> RVP_LightParameters.engine(300);
            };
            var actual = RVP_LightRuntimeTuning.apply(group, original);
            float brightness = switch (group) {
                case explosion -> 20;
                case muzzle -> 4;
                default -> 2;
            };
            float radiusScale = group == Group.muzzle ? 1.2F : 5;
            float haloRadiusScale = group == Group.engine ? 5 : 0;
            assertEquals(original.intensity() * brightness, actual.intensity());
            assertEquals(original.radius() * radiusScale, actual.radius());
            assertEquals(original.radius() * haloRadiusScale, RVP_LightRuntimeTuning.haloRadius(group, original.radius()));
            assertEquals(original.enabled(), actual.enabled());
            assertEquals(original.durationTicks(), actual.durationTicks());
            assertEquals(original.decay(), actual.decay());
        }
        assertEquals(16, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.worldHaloIntensity));
        assertEquals(0.4F, RVP_LightRuntimeTuning.get(Group.engine, Parameter.worldHaloIntensity));
        assertEquals(5, RVP_LightRuntimeTuning.get(Group.engine, Parameter.haloIntensity));
        assertTrue(RVP_LightRuntimeTuning.overrides().isEmpty());
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
        assertEquals(36, tuned.radius());
        assertEquals(original.intensity() * .1F, tuned.intensity(), 0.00001F);
        assertEquals(original.haloIntensity(), tuned.haloIntensity());
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
        manager.addTransient(new Vec3(100, 0, 0), RVP_LightSourceType.EXPLOSION, null,
                RVP_LightParameters.explosion(4), 100);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 10, 8, light -> true).isEmpty());
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.radiusScale, 10);
        assertEquals(1, manager.collectVisible(Vec3.ZERO, 0, 10, 8, light -> true).size());
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.brightness, 0);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 10, 8, light -> true).isEmpty());
        assertEquals(1, manager.size());
        RVP_LightRuntimeTuning.reset(Group.explosion, Parameter.brightness);
        assertEquals(1, manager.collectVisible(Vec3.ZERO, 0, 10, 8, light -> true).size());
    }

    @Test
    void engineLightAndPlumeRangesAreIndependentWhileGeometryAndPhaseStayUnchanged() {
        // 调用发动机阶段预设和两种正式管理器，验证照亮与光晕各自调参且不改变推进阶段或几何。
        Object world = new Object();
        RVP_ScreenLightManager lights = new RVP_ScreenLightManager();
        RVP_RocketPlumeManager plumes = new RVP_RocketPlumeManager();
        lights.beginWorld(world, 100);
        plumes.beginWorld(world, 100);
        UUID key = UUID.randomUUID();
        RVP_LightParameters preset = RVP_LightParameters.enginePhase(RVP_LightParameters.engine(300), true, 10);
        lights.upsertPersistent(key, Vec3.ZERO, preset);
        plumes.upsert(key, Vec3.ZERO, new Vec3(1, 0, 0), 0.3F, 300, 0.5F, false);
        var originalLight = lights.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true).get(0);
        var originalPlume = plumes.collect(Vec3.ZERO, 0, plume -> true).get(0);
        for (float scale : new float[] {0, 0.05F, 2, 16, 1024}) {
            RVP_LightRuntimeTuning.set(Group.engine, Parameter.radiusScale, scale);
            var light = lights.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
            var plume = plumes.collect(Vec3.ZERO, 0, value -> true).get(0);
            assertEquals(Math.min(8192, preset.radius() * scale), light.radius());
            assertEquals(originalLight.haloRadius(), light.haloRadius());
            assertEquals(originalPlume.haloRadius(), plume.haloRadius());
            assertEquals(originalPlume.length(), plume.length());
            assertEquals(originalPlume.radius(), plume.radius());
            assertEquals(originalPlume.strength(), plume.strength());
            assertEquals(originalLight.intensity(), light.intensity());
        }
        RVP_LightRuntimeTuning.reset(Group.engine, Parameter.radiusScale);
        for (float scale : new float[] {0, 0.05F, 2, 16, 1024}) {
            RVP_LightRuntimeTuning.set(Group.engine, Parameter.haloRadiusScale, scale);
            var light = lights.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
            var plume = plumes.collect(Vec3.ZERO, 0, value -> true).get(0);
            assertEquals(originalLight.radius(), light.radius());
            assertEquals(Math.min(8192, preset.radius() * scale), light.haloRadius());
            assertEquals(light.haloRadius(), plume.haloRadius());
            assertEquals(originalPlume.length(), plume.length());
            assertEquals(originalPlume.radius(), plume.radius());
            assertEquals(originalPlume.strength(), plume.strength());
        }
        RVP_LightRuntimeTuning.reset();
        assertEquals(originalLight, lights.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true).get(0));
        assertEquals(originalPlume, plumes.collect(Vec3.ZERO, 0, plume -> true).get(0));
    }

    @Test
    void everySourceKeepsHaloOnlyOrLightingOnlyAndRestoresExistingEntriesWithoutRestartingAge() {
        for (Group group : new Group[] {Group.explosion, Group.muzzle, Group.engine}) {
            // 调用真实管理器采样，防止“照亮半径为零”在预算阶段把仍有光晕的光源丢掉。
            RVP_LightRuntimeTuning.reset();
            Object world = new Object();
            RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
            manager.beginWorld(world, 100);
            RVP_LightSourceType type = switch (group) {
                case explosion -> RVP_LightSourceType.EXPLOSION;
                case muzzle -> RVP_LightSourceType.MUZZLE;
                default -> RVP_LightSourceType.ROCKET_ENGINE;
            };
            RVP_LightParameters preset = switch (group) {
                case explosion -> RVP_LightParameters.explosion(4);
                case muzzle -> RVP_LightParameters.muzzle(125, false);
                default -> RVP_LightParameters.engine(300);
            };
            manager.addTransient(Vec3.ZERO, type, null, preset, 100);
            var original = manager.collectVisible(Vec3.ZERO, .5F, 256, 8, value -> true).get(0);
            RVP_LightRuntimeTuning.set(group, Parameter.radiusScale, 0);
            RVP_LightRuntimeTuning.set(group, Parameter.haloRadiusScale, 4);
            var haloOnly = manager.collectVisible(Vec3.ZERO, .5F, 256, 8, value -> true).get(0);
            assertEquals(0, haloOnly.radius());
            assertEquals(preset.radius() * 4, haloOnly.haloRadius());
            assertEquals(original.intensity(), haloOnly.intensity());
            assertEquals(original.remainingTicks(), haloOnly.remainingTicks());
            RVP_LightRuntimeTuning.set(group, Parameter.haloRadiusScale, 0);
            assertTrue(manager.collectVisible(Vec3.ZERO, .5F, 256, 8, value -> true).isEmpty());
            assertEquals(1, manager.size());
            RVP_LightRuntimeTuning.set(group, Parameter.radiusScale, 4);
            var lightingOnly = manager.collectVisible(Vec3.ZERO, .5F, 256, 8, value -> true).get(0);
            assertEquals(preset.radius() * 4, lightingOnly.radius());
            assertEquals(0, lightingOnly.haloRadius());
            RVP_LightRuntimeTuning.reset(group, null);
            assertEquals(original, manager.collectVisible(Vec3.ZERO, .5F, 256, 8, value -> true).get(0));
        }
    }

    @Test
    void expandedTuningRangesReachRenderingInputsAndRespectFiniteHdrCoreLimit() {
        // 调用实际输入生成，验证扩大范围后不被旧500格或表面权重1的隐含上限吃掉。
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.radiusScale, 1024);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.haloRadiusScale, 1024);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.brightness, 256);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.surface, 16);
        RVP_LightRuntimeTuning.set(Group.bloom, Parameter.nearStrength, 256);
        RVP_LightRuntimeTuning.set(Group.bloom, Parameter.farStrength, 256);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.worldHaloIntensity, 60000);
        RVP_LightRuntimeTuning.set(Group.global, Parameter.distanceScale, 128);
        var tuned = RVP_LightRuntimeTuning.apply(Group.engine, RVP_LightParameters.engine(300));
        assertEquals(8192, tuned.radius());
        assertEquals(8192, RVP_LightRuntimeTuning.haloRadius(Group.engine, 50));
        assertEquals(1024, tuned.intensity());
        assertEquals(12.8F, tuned.surfaceWeight(), .0001F);
        assertEquals(32768, org.ywzj.rvp.config.RVP_LightingConfig.maxDistance());
        assertThrows(IllegalArgumentException.class, () -> RVP_LightRuntimeTuning.set(Group.engine, Parameter.coreIntensity, 60001));
        assertThrows(IllegalArgumentException.class, () -> RVP_LightRuntimeTuning.set(Group.bloom, Parameter.farStrength, Float.POSITIVE_INFINITY));
    }

    @Test
    void explosionWideHaloBrightnessIsIndependentAndResetRestoresTheNewDefaults() {
        // 调用正式爆炸采样；广域光晕改亮不改变照地，照亮倍率改动也不反向改变广域光晕。
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(new Object(), 100);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 100);
        var original = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(25, original.intensity());
        assertEquals(60, original.radius());
        assertEquals(0, original.haloRadius());
        assertEquals(16, original.haloIntensity());
        // 调用光晕范围调参，显式开启暂默认关闭的爆炸光晕，再验证其独立亮度与重置行为。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloRadiusScale, 1);
        var enabled = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(12, enabled.haloRadius());
        assertEquals(original.intensity(), enabled.intensity());
        assertEquals(original.radius(), enabled.radius());
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.worldHaloIntensity, 3);
        var dimmed = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(3, dimmed.haloIntensity());
        assertEquals(original.intensity(), dimmed.intensity());
        assertEquals(original.radius(), dimmed.radius());
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.brightness, 0);
        var haloOnly = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(0, haloOnly.intensity());
        assertEquals(3, haloOnly.haloIntensity());
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.worldHaloIntensity, 0);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).isEmpty());
        RVP_LightRuntimeTuning.reset(Group.explosion, null);
        assertEquals(original, manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0));
        assertEquals(20, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.brightness));
        assertEquals(5, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.radiusScale));
    }

    @Test
    void groupsAndSingleParameterResetsDoNotDisturbOtherTuning() {
        // 调用分组/单项重置和导出快照，检查试调互不串组且旧快照不会被后续修改。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.brightness, 3);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.brightness, 0);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.haloIntensity, 8);
        var exported = RVP_LightRuntimeTuning.overrides();
        RVP_LightRuntimeTuning.reset(Group.engine, Parameter.brightness);
        assertEquals(2, RVP_LightRuntimeTuning.get(Group.engine, Parameter.brightness));
        assertEquals(8, RVP_LightRuntimeTuning.get(Group.engine, Parameter.haloIntensity));
        RVP_LightRuntimeTuning.reset(Group.engine, null);
        assertEquals(5, RVP_LightRuntimeTuning.get(Group.engine, Parameter.haloIntensity));
        assertEquals(3, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.brightness));
        assertEquals(8, exported.get(Group.engine).get(Parameter.haloIntensity));
        assertThrows(UnsupportedOperationException.class, () -> exported.get(Group.engine).put(Parameter.haloIntensity, 9F));
    }

    @Test
    void independentGlowTuningNeverChangesEngineLightGeometryPhaseOrOtherGlowGroups() {
        // 调用真实发动机和喷焰管理器，验证新增光晕参数不会反向改变照地亮度、尺寸和燃烧阶段。
        Object world = new Object();
        RVP_ScreenLightManager lights = new RVP_ScreenLightManager();
        RVP_RocketPlumeManager plumes = new RVP_RocketPlumeManager();
        lights.beginWorld(world, 100);
        plumes.beginWorld(world, 100);
        UUID key = UUID.randomUUID();
        lights.upsertPersistent(key, Vec3.ZERO, RVP_LightParameters.enginePhase(RVP_LightParameters.engine(300), true, 10));
        plumes.upsert(key, Vec3.ZERO, new Vec3(1, 0, 0), 0.3F, 300, 0.5F, false);
        var originalLights = lights.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true);
        var originalPlumes = plumes.collect(Vec3.ZERO, 0, plume -> true);
        for (float outer : new float[] {0, 8, 256}) {
            RVP_LightRuntimeTuning.set(Group.engine, Parameter.haloIntensity, outer);
            RVP_LightRuntimeTuning.set(Group.engine, Parameter.worldHaloIntensity, 4);
            RVP_LightRuntimeTuning.set(Group.bloom, Parameter.nearStrength, 0);
            RVP_LightRuntimeTuning.set(Group.bloom, Parameter.farStrength, 2);
            assertEquals(originalLights, lights.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true));
            assertEquals(originalPlumes, plumes.collect(Vec3.ZERO, 0, plume -> true));
            assertEquals(60000, RVP_LightRuntimeTuning.get(Group.engine, Parameter.coreIntensity));
            RVP_LightRuntimeTuning.reset(Group.bloom, Parameter.nearStrength);
            assertEquals(1, RVP_LightRuntimeTuning.get(Group.bloom, Parameter.nearStrength));
            assertEquals(2, RVP_LightRuntimeTuning.get(Group.bloom, Parameter.farStrength));
            assertEquals(4, RVP_LightRuntimeTuning.get(Group.engine, Parameter.worldHaloIntensity));
        }
        RVP_LightRuntimeTuning.reset(Group.engine, Parameter.worldHaloIntensity);
        assertEquals(0.4F, RVP_LightRuntimeTuning.get(Group.engine, Parameter.worldHaloIntensity));
        assertEquals(256, RVP_LightRuntimeTuning.get(Group.engine, Parameter.haloIntensity));
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

    @Test
    void eoJammerPersistentLightKeepsFiniteRangeHaloAxisAndSteadyBrightness() {
        // 调用干扰机持续光的正式登记与采样：总设置分组没有半径/权重倍率，NaN 必须按 1 处理。
        UUID key = UUID.randomUUID();
        Vec3 axis = new Vec3(0, 0, 1);
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        Object world = new Object();
        manager.beginWorld(world, 100);
        manager.upsertPersistent(key, Vec3.ZERO, RVP_LightSourceType.EO_JAMMER, RVP_LightParameters.eoJammer(), axis);
        var light = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(2.4F, light.radius());
        assertEquals(2.4F, light.haloRadius());
        assertEquals(1.2F, light.intensity(), .0001F);
        // 光晕那一路单独按 0.4 折算：肉眼只看微弱红晕，表面受光不吃折扣。
        assertEquals(0.48F, light.haloIntensity(), .0001F);
        assertTrue(Float.isFinite(light.red()) && Float.isFinite(light.green()) && Float.isFinite(light.blue()));
        assertEquals(axis, light.hemisphereAxis());
        assertEquals(RVP_LightSourceType.EO_JAMMER, light.type());
        // 调用逐刻心跳续期；持续光不吃短光的淡出包络，亮度不能每刻回弹造成闪烁。
        manager.upsertPersistent(key, Vec3.ZERO, RVP_LightSourceType.EO_JAMMER, RVP_LightParameters.eoJammer(), axis);
        manager.tick(world, 101);
        manager.upsertPersistent(key, Vec3.ZERO, RVP_LightSourceType.EO_JAMMER, RVP_LightParameters.eoJammer(), axis);
        assertEquals(light, manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0));
    }

    @Test
    void eoJammerOwnVehicleLightKeepsSurfaceIlluminationWithoutHalo() {
        // 本车只摘光晕：照亮半径、亮度、表面权重必须逐项保持，否则观瞄里车体会跟着变暗。
        UUID key = UUID.randomUUID();
        Vec3 axis = new Vec3(0, 0, 1);
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(new Object(), 100);
        RVP_LightParameters full = RVP_LightParameters.eoJammer();
        manager.upsertPersistent(key, Vec3.ZERO, RVP_LightSourceType.EO_JAMMER, full.withoutHalo(), axis);
        var light = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(full.radius(), light.radius());
        assertEquals(full.intensity(), light.intensity(), .0001F);
        assertEquals(full.surfaceWeight(), light.surfaceWeight(), .0001F);
        assertEquals(axis, light.hemisphereAxis());
        assertEquals(0F, light.haloRadius());
        assertEquals(0F, light.haloIntensity());
        assertFalse(light.bloom());
        // 下一帧下车（不再是本车）换回完整预设，光晕立刻回来。
        manager.upsertPersistent(key, Vec3.ZERO, RVP_LightSourceType.EO_JAMMER, full, axis);
        var restored = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(0.48F, restored.haloIntensity(), .0001F);
        assertTrue(restored.haloRadius() > 0F);
    }

    @Test
    void eoJammerPerPlateLightsShareOwnerAndClearTogether() {
        // 一块玻片一盏灯：左右两块要同时可见，且各自有独立位置；按车的归属一次撤干净。
        UUID vehicle = UUID.randomUUID();
        UUID left = UUID.nameUUIDFromBytes("left".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID right = UUID.nameUUIDFromBytes("right".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(new Object(), 100);
        Vec3 axis = new Vec3(0, 0, 1);
        manager.upsertPersistent(left, vehicle, new Vec3(-1.44, 2.06, 2.54),
                RVP_LightSourceType.EO_JAMMER, RVP_LightParameters.eoJammer(), axis);
        manager.upsertPersistent(right, vehicle, new Vec3(1.50, 2.06, 2.54),
                RVP_LightSourceType.EO_JAMMER, RVP_LightParameters.eoJammer(), axis);
        var lights = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true);
        assertEquals(2, lights.size());
        // 对外报告的键是归属实体，本车遮挡自己灯的那条判断才认得出这是本车的灯。
        assertEquals(vehicle, lights.get(0).persistentKey());
        assertEquals(vehicle, lights.get(1).persistentKey());
        manager.removePersistent(left);
        assertEquals(1, manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).size());
        manager.removePersistent(vehicle);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).isEmpty());
    }

    @Test
    void eoJammerNightVisionPresetIsWiderAndBrighterThanNakedEye() {
        // 红外干扰灯在像增强管里被放大：同一盏灯在夜视下半径更大、光晕更亮。
        UUID key = UUID.randomUUID();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(new Object(), 100);
        manager.upsertPersistent(key, Vec3.ZERO, RVP_LightSourceType.EO_JAMMER,
                RVP_LightParameters.eoJammerNightVision(), new Vec3(0, 0, 1));
        var nightVision = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(4.5F, nightVision.radius());
        assertEquals(4.5F, nightVision.haloRadius());
        // 3.0 × 0.4 = 1.2，相对肉眼那档的 0.48 是 2.5 倍。
        assertEquals(1.2F, nightVision.haloIntensity(), .0001F);
    }
}
