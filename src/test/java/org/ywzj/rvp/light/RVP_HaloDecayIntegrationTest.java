package org.ywzj.rvp.light;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.light.RVP_LightSourceType;
import org.ywzj.rvp.client.light.RVP_RocketPlumeManager;
import org.ywzj.rvp.client.light.RVP_ScreenLight;
import org.ywzj.rvp.client.light.RVP_ScreenLightManager;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Group;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Parameter;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实光源/喷焰管理器的独立光晕、继承关系、原始年龄及结束时刻。 */
class RVP_HaloDecayIntegrationTest {
    @BeforeEach @AfterEach
    void reset() {
        // 调用正式会话清理，测试之间不遗留全局曲线覆盖。
        RVP_LightRuntimeTuning.reset();
    }

    private RVP_ScreenLight sample(RVP_ScreenLightManager manager, float partialTick) {
        // 调用生产预算与采样入口，检查真正送往绘制阶段的输入。
        return manager.collectVisible(Vec3.ZERO, partialTick, 256, 8, light -> true).get(0);
    }

    @Test
    void allFiniteSourceTypesChangeOnlyHaloAndCanResetAtTheSameAge() {
        for (RVP_LightSourceType type : RVP_LightSourceType.values()) {
            // 调用正式短光入口，包括独立预设的干扰机短光，验证全局曲线没有漏掉来源类型。
            Object world = new Object();
            RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
            manager.beginWorld(world, 100);
            RVP_LightParameters preset = new RVP_LightParameters(true, 1, 0.8F, 0.4F,
                    8, 1, 40, 0, 0.8F, 0.3F, true, 0, 0);
            manager.addTransient(Vec3.ZERO, type, null, preset, 100);
            manager.tick(world, 120);
            RVP_LightRuntimeTuning.reset();
            // 本项只验证亮度曲线；关闭收缩项，并让曲线3的淡出沿用照亮剩余寿命，
            // 这样曲线只影响亮度，不影响整盏光源寿命。
            RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloShrink, 0);
            RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloFadeOutTicks, 0);
            var original = sample(manager, 0);
            RVP_LightRuntimeTuning.set(Group.global, Parameter.haloDecayCurve, 1);
            var linear = sample(manager, 0);
            float initialHalo = switch (type) {
                case EXPLOSION -> 0.6F;
                case MUZZLE -> 4;
                case ROCKET_ENGINE -> 2;
                case EO_JAMMER -> 1;
            };
            assertEquals(initialHalo * 0.5F, linear.haloIntensity(), 0.00001F);
            assertEquals(original.intensity(), linear.intensity());
            assertEquals(original.green(), linear.green());
            assertEquals(original.blue(), linear.blue());
            assertEquals(original.remainingTicks(), linear.remainingTicks());
            assertEquals(original.radius(), linear.radius());
            assertEquals(original.haloRadius(), linear.haloRadius());
            // 只清曲线覆盖，收缩项保持关闭，才能与原快照逐字段一致。
            RVP_LightRuntimeTuning.reset(Group.global, Parameter.haloDecayCurve);
            assertEquals(original, sample(manager, 0));
            RVP_LightRuntimeTuning.reset();
        }
    }

    @Test
    void explosionPlateauIgnoresSurfaceFadeAndRetimesWithLifetimeWithoutRestartingBirth() {
        Object world = new Object();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(world, 100);
        // 调用正式爆炸预设；本项固定初始光晕亮度16与低亮比例0.15，保持段应为2.4。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.worldHaloIntensity, 16);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloLowBrightness, 0.15F);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloRadiusScale, 1);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloDecayCurve, 2);
        long handle = manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null,
                RVP_LightParameters.explosion(4), 100);
        assertEquals(16, sample(manager, 0).haloIntensity());
        manager.tick(world, 108);
        var early = sample(manager, 0);
        manager.tick(world, 124);
        var later = sample(manager, 0);
        assertEquals(2.4F, early.haloIntensity(), 0.000001F);
        assertEquals(early.haloIntensity(), later.haloIntensity());
        assertTrue(later.intensity() < early.intensity(), "环境照亮应继续使用自己的淡出");
        // 调用实时寿命倍率；出生仍为100刻，当前年龄24刻，不因新曲线或新时长重新闪亮。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.decayScale, 0.5F);
        var slowed = sample(manager, 0);
        assertEquals(40, slowed.remainingTicks());
        assertEquals(2.4F, slowed.haloIntensity(), 0.000001F);
        manager.tick(world, 164);
        assertFalse(manager.hasTransient(handle));
        assertFalse(manager.hasActiveLights());
    }

    @Test
    void delayedPacketsAndFractionalFramesUseOriginalAgeAndDoNotMultiplyTheOldFade() {
        Object world = new Object();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(world, 100);
        RVP_LightRuntimeTuning.set(Group.global, Parameter.haloDecayCurve, 1);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.worldHaloIntensity, 16);
        // 调用迟包登记，出生为92，当前应直接采样8.5/32，而不是重播高亮首帧。
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 92);
        assertEquals(16 * (1 - 8.5F / 32), sample(manager, 0.5F).haloIntensity(), 0.000001F);
        assertEquals(sample(manager, 0.5F), sample(manager, 0.5F));
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloDecayCurve, 2);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloLowBrightness, 0.25F);
        assertEquals(4, sample(manager, 0.5F).haloIntensity());
    }

    @Test
    void sourceOverridesResetToGlobalAndInvalidCurveNumbersNeverReplaceTheSelection() {
        RVP_LightRuntimeTuning.set(Group.global, Parameter.haloDecayCurve, 2);
        assertEquals(2, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.haloDecayCurve));
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloDecayCurve, 1);
        assertEquals(1, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.haloDecayCurve));
        assertEquals(2, RVP_LightRuntimeTuning.get(Group.muzzle, Parameter.haloDecayCurve));
        for (float invalid : new float[] {-1, 4, 0.5F, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloDecayCurve, invalid));
            assertEquals(1, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.haloDecayCurve));
        }
        RVP_LightRuntimeTuning.reset(Group.explosion, Parameter.haloDecayCurve);
        assertEquals(2, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.haloDecayCurve));
    }

    @Test
    void enginePreviewSeparatesHaloFromCoreAndRealPersistentSourcesStayLit() {
        Object world = new Object();
        RVP_RocketPlumeManager plumes = new RVP_RocketPlumeManager();
        plumes.beginWorld(world, 100);
        UUID key = UUID.randomUUID();
        plumes.upsert(key, Vec3.ZERO, new Vec3(1, 0, 0), 0.3F, 300, 1, true);
        plumes.beginWorld(world, 120);
        RVP_LightRuntimeTuning.set(Group.global, Parameter.haloDecayCurve, 2);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.haloLowBrightness, 0.15F);
        // 调用正式喷焰采样，白热核心仍按原预览降至0.5，光晕保持在0.15，长宽不变。
        var preview = plumes.collect(Vec3.ZERO, 0, plume -> true).get(0);
        assertEquals(0.5F, preview.strength());
        assertEquals(0.15F, preview.haloStrength());
        assertEquals(3, preview.length(), 0.00001F);
        assertEquals(0.45F, preview.radius(), 0.00001F);
        plumes.beginWorld(world, 140);
        assertFalse(plumes.hasActive());
        plumes.upsert(key, Vec3.ZERO, new Vec3(1, 0, 0), 0.3F, 300, 0.7F, false);
        var live = plumes.collect(Vec3.ZERO, 0.5F, plume -> true).get(0);
        assertEquals(0.7F, live.strength());
        assertEquals(live.strength(), live.haloStrength());
        RVP_ScreenLightManager lights = new RVP_ScreenLightManager();
        lights.beginWorld(world, 140);
        lights.upsertPersistent(key, Vec3.ZERO, RVP_LightParameters.engine());
        assertEquals(8, sample(lights, 0.5F).haloIntensity());
    }

    @Test
    void explosionDefaultsHoldTheConfiguredPeakThenFadeWithoutChangingSurface() {
        Object world = new Object();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(world, 100);
        // 调用爆炸正式默认值，不预先设置参数，确保玩家直接触发爆炸就能看到默认光晕时间线。
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 100);
        assertEquals(3, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.haloDecayCurve));
        assertEquals(5, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.haloPeakHoldTicks));
        assertEquals(70, RVP_LightRuntimeTuning.get(Group.explosion, Parameter.haloFadeOutTicks));
        var first = sample(manager, 0);
        // 默认：基础半径12×光晕倍率35=420格，广域光晕亮度0.6。
        assertEquals(420, first.haloRadius());
        assertEquals(0.6F, first.haloIntensity(), 0.000001F);
        for (int age = 1; age <= 5; age++) {
            manager.tick(world, 100 + age);
            assertEquals(first.haloIntensity(), sample(manager, 0).haloIntensity());
            // 高亮保持期间淡出比例为1，光晕仍保持静态范围，不在保持段提前收缩。
            assertEquals(first.haloRadius(), sample(manager, 0).haloRadius());
        }
        assertTrue(sample(manager, 0).intensity() < first.intensity());
        assertTrue(sample(manager, 0.5F).haloIntensity() < first.haloIntensity());
        // 保持结束进入淡出，光晕半径开始向中心收缩；照亮半径固定不变。
        assertTrue(sample(manager, 0.5F).haloRadius() < first.haloRadius());
        assertEquals(first.radius(), sample(manager, 0.5F).radius());
        manager.tick(world, 140);
        // 保持5刻后淡出70刻，第40刻正好走到淡出一半：亮度减半，半径收到约95%。
        assertEquals(0.3F, sample(manager, 0).haloIntensity(), 0.000001F);
        assertEquals(399, sample(manager, 0).haloRadius(), 0.000001F);
        manager.tick(world, 176);
        assertFalse(manager.hasActiveLights());
    }

    @Test
    void peakAndFadeTimesAndBrightnessAreIndependentlyTunable() {
        Object world = new Object();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(world, 100);
        // 调用面板共用的正式参数：40亮度保持5刻，再用10刻淡出，环境照亮仍活到原32刻。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.worldHaloIntensity, 40);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloPeakHoldTicks, 5);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloFadeOutTicks, 10);
        // 天空有独立时间线（默认40刻）会延长整盏光，本项只验证光晕与照亮的关系。
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.skyPeakBrightness, 0);
        manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 100);
        manager.tick(world, 105);
        assertEquals(40, sample(manager, 0).haloIntensity());
        manager.tick(world, 110);
        assertEquals(20, sample(manager, 0).haloIntensity());
        manager.tick(world, 115);
        var endedHalo = sample(manager, 0);
        assertEquals(0, endedHalo.haloIntensity());
        assertTrue(endedHalo.intensity() > 0);
        assertEquals(17, endedHalo.remainingTicks());
    }

    @Test
    void longerHaloSurvivesSurfaceEndAndDelayedPacketsUseItsOwnClock() {
        Object world = new Object();
        RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
        manager.beginWorld(world, 100);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloPeakHoldTicks, 3);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.worldHaloIntensity, 16);
        RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloFadeOutTicks, 60);
        long handle = manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null,
                RVP_LightParameters.explosion(4), 100);
        manager.tick(world, 133);
        // 调用真实预算；照亮已结束的第33刻，光晕刚好走到独立淡出的一半，不能被丢弃。
        var haloOnly = sample(manager, 0);
        assertEquals(0, haloOnly.intensity());
        assertEquals(8, haloOnly.haloIntensity(), 0.000001F);
        assertTrue(manager.hasTransient(handle));
        manager.tick(world, 163);
        assertFalse(manager.hasActiveLights());
        // 调用迟包入口，照亮过期但光晕仍有效的爆炸也应按原出生年龄接着播放。
        manager.beginWorld(world, 200);
        assertNotEquals(0, manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null,
                RVP_LightParameters.explosion(4), 167));
        assertEquals(8, sample(manager, 0).haloIntensity(), 0.000001F);
    }

    @Test
    void lightingDecayCannotStretchTheThreeTickPeakOrIndependentFade() {
        for (float scale : new float[] {0.5F, 1, 2}) {
            Object world = new Object();
            RVP_ScreenLightManager manager = new RVP_ScreenLightManager();
            manager.beginWorld(world, 100);
            // 调用不同照亮寿命倍率，光晕固定的3刻保持和29刻淡出应始终不变。
            RVP_LightRuntimeTuning.set(Group.explosion, Parameter.decayScale, scale);
            RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloPeakHoldTicks, 3);
            RVP_LightRuntimeTuning.set(Group.explosion, Parameter.haloFadeOutTicks, 29);
            RVP_LightRuntimeTuning.set(Group.explosion, Parameter.worldHaloIntensity, 16);
            manager.addTransient(Vec3.ZERO, RVP_LightSourceType.EXPLOSION, null, RVP_LightParameters.explosion(4), 100);
            manager.tick(world, 103);
            assertEquals(16, sample(manager, 0).haloIntensity());
            manager.tick(world, 117);
            assertEquals(8, sample(manager, 0.5F).haloIntensity(), 0.000001F);
        }
    }

    @Test
    void independentlyExtendedPreviewHaloIsNotRemovedWhenItsCoreEnds() {
        Object world = new Object();
        RVP_RocketPlumeManager manager = new RVP_RocketPlumeManager();
        manager.beginWorld(world, 100);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.haloDecayCurve, 3);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.haloPeakHoldTicks, 3);
        RVP_LightRuntimeTuning.set(Group.engine, Parameter.haloFadeOutTicks, 80);
        // 调用正式预览管理器；40刻核心结束后，光晕仍应按独立时间继续采样。
        manager.upsert(UUID.randomUUID(), Vec3.ZERO, new Vec3(1, 0, 0), 0.3F, 300, 1, true);
        manager.beginWorld(world, 143);
        var halo = manager.collect(Vec3.ZERO, 0, plume -> true).get(0);
        assertEquals(0, halo.strength());
        assertEquals(0.5F, halo.haloStrength());
        manager.beginWorld(world, 183);
        assertFalse(manager.hasActive());
    }
}


