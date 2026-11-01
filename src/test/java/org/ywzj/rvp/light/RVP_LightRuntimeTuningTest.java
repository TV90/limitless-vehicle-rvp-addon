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
    @Test
    void ambientSettingsDoNotMutateLightAgeEmissionOrLifetime() {
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        Object world = new Object();
        // 调用正式出生和采样，在同一游戏年龄修改环境参数，CPU快照必须完全相同。
        manager.beginWorld(world, 100);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 100);
        manager.tick(world, 102);
        var before = manager.collectVisible(Vec3.ZERO, .5F, 256, 8, light -> true);
        RVP_LightRuntimeTuning.set(Group.global, Parameter.ambientWorldWeight, .3F);
        RVP_LightRuntimeTuning.set(Group.global, Parameter.ambientMinStrength, .25F);
        RVP_LightRuntimeTuning.set(Group.global, Parameter.ambientCurve, 2);
        assertEquals(before, manager.collectVisible(Vec3.ZERO, .5F, 256, 8, light -> true));
        RVP_LightRuntimeTuning.set(Group.global, Parameter.ambientEnabled, 0);
        assertEquals(before, manager.collectVisible(Vec3.ZERO, .5F, 256, 8, light -> true));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.engine, Parameter.ambientWorldWeight, .5F));
    }

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
                case explosion -> 5;
                case muzzle -> 4;
                default -> 2;
            };
            float radiusScale = group == Group.muzzle ? 1.2F : group == Group.explosion ? 20 : 5;
            float haloRadiusScale = group == Group.engine ? 5 : group == Group.explosion ? 35 : 0;
            assertEquals(original.intensity() * brightness, actual.intensity());
            assertEquals(original.radius() * radiusScale, actual.radius());
            assertEquals(original.radius() * haloRadiusScale, RVP_LightRuntimeTuning.haloRadius(group, original.radius()));
            assertEquals(original.enabled(), actual.enabled());
            assertEquals(original.durationTicks(), actual.durationTicks());
            assertEquals(original.decay(), actual.decay());
        }
        assertEquals(0.6F, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.worldHaloIntensity), .00001F);
        // 空中起爆离地门槛：默认100格，0表示不限制；只有全局组支持该参数。
        assertEquals(100, RVP_LightRuntimeTuning.get(Group.global, Parameter.airBurstClearance));
        assertFalse(Parameter.airBurstClearance.supports(Group.explosion));
        assertFalse(Parameter.airBurstClearance.supports(Group.muzzle));
        assertFalse(Parameter.airBurstClearance.supports(Group.engine));
        assertFalse(Parameter.airBurstClearance.supports(Group.bloom));
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
        assertEquals(original.intensity() * 0.4F, tuned.intensity(), 0.00001F);
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
        // 天空有独立预算会额外放宽距离，本项只验证照亮半径，所以先把它关掉。
        RVP_LightRuntimeTuning.set(Group.global, Parameter.skyDistance, 0);
        // 默认光晕半径倍率 35 会让可见半径远超本次测试的 10 格预算，先关掉光晕与核心，只留照亮。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.worldHaloIntensity, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreIntensity, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreHaloIntensity, 0);
        // 默认照亮倍率 20（基础12→240格）同样超出 10 格预算，从 0 开始再验证放大。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.radiusScale, 0);
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
            // 天空染色与白热核心默认开启，会让“两种范围都为0”的光源仍被预算保留；本项先关掉它们。
            if (Parameter.skyPeakBrightness.supports(group)) { RVP_LightRuntimeTuning.set(group, Parameter.skyPeakBrightness, 0); }
            if (Parameter.coreIntensity.supports(group)) { RVP_LightRuntimeTuning.set(group, Parameter.coreIntensity, 0); }
            if (Parameter.coreHaloIntensity.supports(group)) {
                RVP_LightRuntimeTuning.set(group, Parameter.coreHaloIntensity, 0);
            }
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
        // 新默认值：亮度5（1.25×5=6.25）、照亮倍率20（基础12→240）、光晕倍率35（12→420）、广域光晕0.6。
        assertEquals(6.25F, original.intensity(), .00001F);
        assertEquals(240, original.radius());
        assertEquals(420, original.haloRadius());
        assertEquals(0.6F, original.haloIntensity(), .00001F);
        // 调用光晕范围调参，保持已默认开启的爆炸光晕，再验证其独立亮度与重置行为。
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
        // 关闭天空染色与白热核心后照亮、光晕、天空三路都为0，光源才真正没有任何贡献。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyPeakBrightness, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreIntensity, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreHaloIntensity, 0);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).isEmpty());
        RVP_LightRuntimeTuning.reset(Group.explosion, null);
        assertEquals(original, manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0));
        assertEquals(5, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.brightness));
        assertEquals(20, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.radiusScale));
    }

    @Test
    void explosionHaloContractsTowardCenterWhileIlluminationKeepsRadiusAndBrightnessEnvelope() {
        // 调用正式爆炸采样，验证淡出时只有光晕半径收缩，照亮半径、亮度包络和寿命口径都不变。
        Object world = new Object();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(world, 100);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 100);
        var start = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        manager.tick(world, 117);
        var half = manager.collectVisible(Vec3.ZERO, .5F, 256, 8, value -> true).get(0);
        manager.tick(world, 127);
        var late = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(0.1F, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.haloShrink), .00001F);
        assertEquals(420, start.haloRadius());
        // 17.5刻时光晕淡出比例约0.9156，默认收缩幅度0.1让半径只收到约91.6%（收缩只作用于半径）。
        float halfHaloFade = RVP_LightRuntimeTuning.haloFade(Group.explosion, 17.5, 32, 1);
        assertEquals(420 * (1 - 0.1F * (1 - halfHaloFade)), half.haloRadius(), .01F);
        assertTrue(late.haloRadius() < half.haloRadius());
        assertTrue(late.haloRadius() > 0);
        assertEquals(start.radius(), late.radius());
        // 收缩只改半径：光晕亮度仍等于独立峰值乘曲线，照亮亮度仍是自己的衰减包络。
        assertEquals(0.6F * halfHaloFade, half.haloIntensity(), .001F);
        assertTrue(late.intensity() > 0 && late.intensity() < half.intensity());
        // 强度在0～1间线性插值：用满幅度验证公式，再验证0.5与0两档。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloShrink, 1);
        assertEquals(1, RVP_LightRuntimeTuning.haloShrinkFactor(Group.explosion, 1));
        assertEquals(.5F, RVP_LightRuntimeTuning.haloShrinkFactor(Group.explosion, .5F), .00001F);
        assertEquals(0, RVP_LightRuntimeTuning.haloShrinkFactor(Group.explosion, 0));
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloShrink, .5F);
        assertEquals(.75F, RVP_LightRuntimeTuning.haloShrinkFactor(Group.explosion, .5F), .00001F);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloShrink, 0);
        assertEquals(1, RVP_LightRuntimeTuning.haloShrinkFactor(Group.explosion, 0));
        // 炮口默认不收缩；持续光与干扰机不支持该参数，必须返回1而不是把NaN交给渲染。
        assertEquals(1, RVP_LightRuntimeTuning.haloShrinkFactor(Group.muzzle, 0));
        assertEquals(1, RVP_LightRuntimeTuning.haloShrinkFactor(Group.engine, 0));
        assertEquals(1, RVP_LightRuntimeTuning.haloShrinkFactor(null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloShrink, 1.5F));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.engine, Parameter.haloShrink, 1));
    }

    @Test
    void explosionSkyScatterTintsTheWholeSkyThenContractsAndSurvivesDisabledLighting() {
        // 调用正式爆炸采样，验证天空染色的强度与宽度随时间变化，并与照亮、光晕和八灯预算相互独立。
        Object world = new Object();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(world, 100);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 100);
        var start = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        manager.tick(world, 117);
        var half = manager.collectVisible(Vec3.ZERO, .5F, 256, 8, value -> true).get(0);
        manager.tick(world, 127);
        var late = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        // 默认峰值1、保持2刻+淡出38刻、收拢1：满亮时整片天空；17.5刻按天空曲线降到约0.64。
        assertEquals(1F, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.skyPeakBrightness));
        assertEquals(2, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.skyHoldTicks));
        assertEquals(38, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.skyFadeTicks));
        assertEquals(1F, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.skyContract));
        assertEquals(1F, start.skyIntensity(), .00001F);
        assertEquals(1, start.skySpread());
        assertEquals(RVP_LightRuntimeTuning.skyFade(Group.explosion, 17.5), half.skyIntensity(), .00001F);
        assertEquals(half.skyIntensity(), half.skySpread(), .00001F);
        assertTrue(half.skyIntensity() > 0.5F && half.skyIntensity() < 0.8F);
        assertTrue(late.skyIntensity() > 0 && late.skyIntensity() < half.skyIntensity());
        assertTrue(late.skySpread() < half.skySpread());
        // 天空时间线与光晕完全独立：把光晕淡出压到5刻，天空仍按自己的38刻继续亮。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloFadeOutTicks, 5);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0).skyIntensity() > 0);
        assertEquals(1F * RVP_LightRuntimeTuning.skyFade(Group.explosion, 27),
                manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0).skyIntensity(), .00001F);
        RVP_LightRuntimeTuning.reset(Group.explosion, Parameter.haloFadeOutTicks);
        // 天空保持刻数与峰值亮度都可单独调：保持拉长后同一刻更亮，峰值提高后强度按比例变大。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyHoldTicks, 20);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0).skyIntensity()
                > half.skyIntensity());
        RVP_LightRuntimeTuning.reset(Group.explosion, Parameter.skyHoldTicks);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyPeakBrightness, 1.6F);
        assertEquals(1.6F * RVP_LightRuntimeTuning.skyFade(Group.explosion, 27),
                manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0).skyIntensity(), .00001F);
        RVP_LightRuntimeTuning.reset(Group.explosion, Parameter.skyPeakBrightness);
        // 天空项不改变照亮半径与整盏光源寿命。
        assertEquals(240, late.radius());
        assertEquals(RVP_LightRuntimeTuning.haloDurationTicks(Group.explosion, 32) - 27, late.remainingTicks());
        // 照亮与光晕全关时天空仍在，光源必须继续通过预算；这是独立的天空通道。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.brightness, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloRadiusScale, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.worldHaloIntensity, 0);
        // 关闭白热核心，本项只验证天空通道的独立性（核心另有专门回归）。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreIntensity, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreHaloIntensity, 0);
        var skyOnly = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(0, skyOnly.intensity());
        assertEquals(0, skyOnly.haloRadius());
        assertEquals(0, skyOnly.visibleRadius());
        assertTrue(skyOnly.skyIntensity() > 0);
        // 关闭天空染色后同样不再有贡献，光源不会被预算保留。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyPeakBrightness, 0);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).isEmpty());
        // 持续光与炮口默认不写天空；不支持该参数的分组返回0/1，不把NaN交给渲染。
        assertEquals(0, RVP_LightRuntimeTuning.skyIntensity(Group.engine, 0));
        assertEquals(0, RVP_LightRuntimeTuning.skyIntensity(Group.muzzle, 0));
        assertEquals(0, RVP_LightRuntimeTuning.skyIntensity(null, 0));
        assertEquals(1, RVP_LightRuntimeTuning.skySpread(Group.engine, 0));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyPeakBrightness, 8.5F));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyHoldTicks, 41F));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyFadeTicks, 641F));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyContract, -0.1F));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.engine, Parameter.skyPeakBrightness, 1));
    }

    @Test
    void skyDistanceParameterWidensOnlySkyLights() {
        // 调用正式管理器与预算：天空专用距离只放宽天空染色，不影响地面照亮、光晕与核心。
        Object world = new Object();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(world, 100);
        manager.addTransient(new Vec3(0, 0, 1500), RVP_LightSourceType.EXPLOSION, null,
                RVP_LightParameters.explosion(4), 100);
        assertEquals(2048, RVP_LightRuntimeTuning.get(Group.global, Parameter.skyDistance));
        // 默认 2048 格天空预算：1500 格外的空爆即使远超地面预算 256 也必须保留。
        assertEquals(1, manager.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true).size());
        // 设为 0 后回到普通距离判定，同一盏灯被剔除。
        RVP_LightRuntimeTuning.set(Group.global, Parameter.skyDistance, 0);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 8, light -> true).isEmpty());
        // 上限与非法值：0～32768，越界直接拒绝写入。
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.global, Parameter.skyDistance, 32769));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.global, Parameter.skyDistance, -1));
    }

    @Test
    void explosionCoreEmitsRoundHdrCoreShrinkingWhileIlluminationStaysIndependent() {
        // 调用正式爆炸采样：白热核心从出生最大半径随寿命收缩、亮度跟随光晕淡出，照亮半径与亮度包络不受影响。
        Object world = new Object();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(world, 100);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 100);
        var start = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        manager.tick(world, 116);
        var half = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        // 默认参数：参考当量（半径4格→基础12格）下出生 0.8 格核心、0.3 收缩幅度、10 格光团、40/40 亮度。
        assertEquals(0.8F, start.emission().coreRadius(), .00001F);
        assertEquals(10.0F, start.emission().haloRadius(), .00001F);
        assertEquals(40, start.emission().coreIntensity());
        assertEquals(40, start.emission().haloIntensity(), .00001F);
        assertEquals(1, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.coreRadiusScale));
        assertEquals(1, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.coreHaloScale));
        assertEquals(0.3F, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.coreShrink));
        // 16刻时寿命走过一半：半径按 (1-0.3×0.5)=0.85 收缩，亮度按光晕淡出下降。
        assertEquals(0.68F, half.emission().coreRadius(), .00001F);
        assertEquals(8.5F, half.emission().haloRadius(), .00001F);
        assertTrue(half.emission().coreIntensity() < start.emission().coreIntensity());
        assertTrue(half.emission().coreIntensity() > 0);
        // 当量倍率：同一参数下半径8格（基础20格）的爆炸核心按 20/12 放大，两个倍率可分别取0关闭当量缩放。
        RVP_ScreenLightManager bigger = new RVP_ScreenLightManager();
        bigger.beginWorld(world, 100);
        bigger.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(8), 100);
        var largeStart = bigger.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(0.8F * 20 / 12, largeStart.emission().coreRadius(), .00001F);
        assertEquals(10.0F * 20 / 12, largeStart.emission().haloRadius(), .00001F);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreRadiusScale, 0);
        assertNull(bigger.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0).emission(),
                "核心当量倍率0表示关闭核心，只剩照亮与光晕");
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreRadiusScale, 2);
        assertEquals(0.8F * 2 * 20 / 12, bigger.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0)
                .emission().coreRadius(), .00001F);
        // 光团当量缩放与核心分开：只放大光团不影响核心半径。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreRadiusScale, 1);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreHaloScale, 3);
        var wideHalo = bigger.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0).emission();
        assertEquals(0.8F * 20 / 12, wideHalo.coreRadius(), .00001F);
        assertEquals(10.0F * 3 * 20 / 12, wideHalo.haloRadius(), .00001F);
        RVP_LightRuntimeTuning.reset();
        // 核心与照亮互不影响：关掉照亮、光晕与天空后只剩白热核心，仍必须通过预算。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.brightness, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloRadiusScale, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.worldHaloIntensity, 0);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyPeakBrightness, 0);
        var coreOnly = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(0, coreOnly.intensity());
        assertEquals(0, coreOnly.haloRadius());
        assertEquals(0, coreOnly.skyIntensity());
        assertNotNull(coreOnly.emission());
        assertTrue(coreOnly.visibleRadius() > 0, "只有白热核心的光源仍必须通过预算");
        // 收缩幅度可调：设为0时同一刻保持出生半径；设为1时按寿命线性收到 5% 下限。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreShrink, 0);
        assertEquals(0.8F, manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0)
                .emission().coreRadius(), .00001F);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreShrink, 1);
        assertEquals(0.8F * 0.5F, manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0)
                .emission().coreRadius(), .00001F);
        // 核心亮度可单独关掉，只保留外围光团。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreIntensity, 0);
        var haloOnly = manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).get(0);
        assertEquals(0, haloOnly.emission().coreIntensity());
        assertTrue(haloOnly.emission().haloIntensity() > 0);
        // 两路都为0时不再产生自发光，此时没有任何贡献，光源不再提交。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreHaloIntensity, 0);
        assertTrue(manager.collectVisible(Vec3.ZERO, 0, 256, 8, value -> true).isEmpty());
        // 发动机与炮口走各自的自发光通道；不支持该参数的分组直接拒绝写入。
        RVP_LightRuntimeTuning.reset();
        assertNull(RVP_LightRuntimeTuning.coreEmission(Group.engine, 12, .5F, 1));
        assertNull(RVP_LightRuntimeTuning.coreEmission(Group.muzzle, 12, .5F, 1));
        assertNull(RVP_LightRuntimeTuning.coreEmission(Group.explosion, 12, .5F, 0));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.engine, Parameter.coreRadius, 1));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreRadius, 65));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.explosion, Parameter.coreShrink, 1.5F));
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
        // 爆炸现在也支持 coreIntensity，改用真正不支持该参数的分组验证拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.bloom, Parameter.coreIntensity, 1));
        assertThrows(IllegalArgumentException.class,
                () -> RVP_LightRuntimeTuning.set(Group.bloom, Parameter.enabled, 0.5F));
        assertTrue(RVP_LightRuntimeTuning.overrides().getOrDefault(Group.explosion, java.util.Map.of()).isEmpty());
    }
}





