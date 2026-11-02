package org.ywzj.rvp.light;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_LightingConfig;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.data.RVP_FireData;
import org.ywzj.rvp.weapon.data.RVP_VisualEffectData;
import static org.junit.jupiter.api.Assertions.*;

/** 代码统一控制光源、武器 JSON 不再提供覆盖入口、P5 固定关闭的行为回归。 */
class RVP_LightParametersTest {
    /** 仅用于验证普通武器数据解析不会保留被撤回的光源字段。 */
    private static final Gson GSON = new Gson();

    @Test
    void allCodePresetsGenerateScreenLightWithoutRequestingBlockLight() {
        for (RVP_LightParameters light : new RVP_LightParameters[] {
                RVP_LightParameters.explosion(4), RVP_LightParameters.muzzle(7.62F, false),
                RVP_LightParameters.muzzle(125, false), RVP_LightParameters.muzzle(120, true),
                RVP_LightParameters.engine()}) {
            assertTrue(light.enabled());
            assertTrue(light.radius() > 0 && light.radius() <= RVP_LightParameters.ENGINE_MAX_RADIUS);
            assertTrue(light.intensity() > 0 && light.intensity() <= 4);
            assertTrue(light.durationTicks() >= 1 && light.durationTicks() <= 40);
            assertEquals(0, light.blockLightLevel());
        }
    }

    @Test
    void p5CannotBeEnabledByConfiguration() {
        // 调用实际入口，P5 不再注册可开启真实光的服务端配置规格。
        assertFalse(RVP_LightingConfig.blockLightEnabled());
        assertEquals(0, RVP_LightParameters.explosion(64).blockLightLevel());
        assertEquals(0, RVP_LightParameters.muzzle(155, false).blockLightLevel());
    }

    @Test
    void explosionAndOwnOcclusionKeepLocalAndNewDefaults() {
        // 调用配置读取入口：照亮默认全部关闭，本车遮挡沿用默认关闭。
        assertFalse(RVP_LightingConfig.screenEnabled());
        assertFalse(RVP_LightingConfig.explosionLightEnabled());
        assertFalse(RVP_LightingConfig.muzzleLightEnabled());
        assertFalse(RVP_LightingConfig.explosionCoreEnabled());
        assertFalse(RVP_LightingConfig.explosionHaloEnabled());
        assertFalse(RVP_LightingConfig.skyScatterEnabled());
        assertFalse(RVP_LightingConfig.smokeLightEnabled());
        assertFalse(RVP_LightingConfig.missileHaloEnabled());
        assertTrue(RVP_LightingConfig.bloomEnabled());
        assertTrue(RVP_LightingConfig.eoJammerGlowEnabled());
        assertTrue(RVP_LightingConfig.plumeEnabled());
        assertFalse(RVP_LightingConfig.sourceVehicleOccludesOwnLight());
        // 真实方块光仍保持本地关闭状态，屏幕光开关不改变它。
        assertEquals(0, RVP_LightParameters.explosion(8).blockLightLevel());
    }

    @Test
    void explosionRadiusIsBoundedEvenForNuclearAndInvalidInput() {
        assertTrue(RVP_LightParameters.explosion(8192).radius() <= 32);
        assertEquals(RVP_LightParameters.explosion(4), RVP_LightParameters.explosion(Float.NaN));
        assertEquals(RVP_LightParameters.explosion(4), RVP_LightParameters.explosion(Float.POSITIVE_INFINITY));
        assertTrue(RVP_LightParameters.explosion(-10).radius() > 0);
    }

    @Test
    void heavyCannonUsesStrongerWhiterLightThanMachineGun() {
        RVP_LightParameters machineGun = RVP_LightParameters.muzzle(7.62F, false);
        RVP_LightParameters cannon = RVP_LightParameters.muzzle(125, false);
        assertTrue(cannon.radius() > machineGun.radius());
        assertTrue(cannon.intensity() > machineGun.intensity());
        assertTrue(cannon.green() > machineGun.green());
        assertTrue(cannon.blue() > machineGun.blue());
        assertTrue(machineGun.mergeWindowTicks() >= cannon.mergeWindowTicks());
    }

    @Test
    void invalidCaliberKeepsCodeDefaultInsteadOfProducingNaN() {
        RVP_LightParameters defaults = RVP_LightParameters.muzzle(7.62F, false);
        assertEquals(defaults, RVP_LightParameters.muzzle(Float.NaN, false));
        assertEquals(defaults, RVP_LightParameters.muzzle(Float.POSITIVE_INFINITY, false));
        assertEquals(defaults, RVP_LightParameters.muzzle(-1, false));
    }

    @Test
    void engineEnvelopeMakesFirstStageBrightestAndKeepsP5Off() {
        RVP_LightParameters defaults = RVP_LightParameters.engine();
        RVP_LightParameters first = RVP_LightParameters.enginePhase(defaults, false, 100);
        RVP_LightParameters second = RVP_LightParameters.enginePhase(defaults, true, 100);
        RVP_LightParameters ending = RVP_LightParameters.enginePhase(defaults, false, 1);
        assertTrue(first.intensity() > second.intensity());
        assertEquals(first.radius(), second.radius());
        assertTrue(second.radius() >= 20);
        assertTrue(first.green() > second.green());
        assertTrue(first.intensity() > ending.intensity());
        assertEquals(0, first.blockLightLevel());
        assertEquals(0, second.blockLightLevel());
    }

    @Test
    void engineHasMaximumPresetSurfaceLightAndIndependentHDRCore() {
        // 调用真实代码预设，发光强度不能再被普通表面光的旧 0.75 数值限制。
        RVP_LightParameters engine = RVP_LightParameters.engine();
        RVP_LightParameters.EngineEmission emission = RVP_LightParameters.engineEmission(engine.radius(), engine.intensity());
        assertEquals(4F, engine.intensity());
        assertEquals(60000F, emission.coreIntensity());
        assertEquals(16F, emission.haloIntensity());
        assertTrue(emission.haloRadius() > emission.coreRadius());
        assertTrue(engine.bloom());
        assertEquals(0, engine.volumetricStrength());
        assertEquals(0, engine.blockLightLevel());
    }

    @Test
    void secondPulseAndBurnoutAlsoReduceSourceEmission() {
        // 调用推进阶段与自发光生成，二脉冲和燃尽不能只减弱环境照明、却留下恒亮核心。
        RVP_LightParameters first = RVP_LightParameters.engine();
        RVP_LightParameters second = RVP_LightParameters.enginePhase(first, true, 100);
        RVP_LightParameters ending = RVP_LightParameters.enginePhase(first, false, 1);
        RVP_LightParameters.EngineEmission firstGlow = RVP_LightParameters.engineEmission(first.radius(), first.intensity());
        RVP_LightParameters.EngineEmission secondGlow = RVP_LightParameters.engineEmission(second.radius(), second.intensity());
        RVP_LightParameters.EngineEmission endingGlow = RVP_LightParameters.engineEmission(ending.radius(), ending.intensity());
        assertEquals(firstGlow.coreIntensity() * 0.7F, secondGlow.coreIntensity(), 0.001F);
        // 旧圆形喷口处于代码暂停状态；扩大环境范围也不能把它的物理核心放大到几百格。
        assertEquals(firstGlow.coreRadius(), secondGlow.coreRadius());
        assertTrue(endingGlow.coreIntensity() < firstGlow.coreIntensity());
        assertTrue(endingGlow.haloIntensity() < firstGlow.haloIntensity());
    }

    @Test
    void sourceEmissionRejectsInvalidInputsAndHasFiniteHDRBudget() {
        // 调用真实自发光入口，损坏坐标/强度以及过大的外部参数不能把 Inf 送进浮点画面。
        assertEquals(0F, RVP_LightParameters.engineEmission(7, 0).coreIntensity());
        assertEquals(0F, RVP_LightParameters.engineEmission(Float.NaN, 4).coreIntensity());
        assertEquals(0F, RVP_LightParameters.engineEmission(7, Float.POSITIVE_INFINITY).coreIntensity());
        RVP_LightParameters.EngineEmission largest = RVP_LightParameters.engineEmission(Float.MAX_VALUE, Float.MAX_VALUE);
        assertEquals(60000F, largest.coreIntensity());
        assertTrue(Float.isFinite(largest.haloRadius()));
        assertTrue(largest.coreRadius() < 1);
    }

    @Test
    void weaponJSONDoesNotRetainLightConfigurationObjects() {
        // 调用真实数据类的 Gson 解析；无论对象里写什么，光参数都不能进入当前武器数据模型。
        assertIgnored("{\"muzzle_light\":{\"enabled\":false,\"color\":\"invalid\",\"radius\":999}}", RVP_FireData.class);
        assertIgnored("{\"rocket_engine_light\":{\"enabled\":false,\"radius\":999}}", RVP_EffectsData.class);
        assertIgnored("{\"screen_light\":{\"enabled\":false,\"radius\":999}}", RVP_VisualEffectData.class);
    }

    @Test
    void ignoredLightObjectDoesNotEnableReplacementVisualOrChangePreset() {
        RVP_VisualEffectData data = GSON.fromJson(
                "{\"screen_light\":{\"enabled\":true},\"preset_data\":{\"max_clouds\":12}}", RVP_VisualEffectData.class);
        assertFalse(data.isEnabled());
        assertTrue(data.getEffectType().isEmpty());
        assertEquals(12, data.copyPresetData().get("max_clouds").getAsInt());
        assertFalse(data.copyPresetData().has("rvp_screen_light"));
    }

    @Test
    void engineWorldRadiusAddsMillimeterCaliberAndCapsAtFiveHundred() {
        float[][] cases = {{0, 20}, {100, 30}, {300, 50}, {4800, 500}, {5000, 500}, {Float.MAX_VALUE, 500}};
        for (float[] value : cases) {
            RVP_LightParameters engine = RVP_LightParameters.engine(value[0]);
            assertEquals(value[1], engine.radius(), 0.00001F);
            assertEquals(4, engine.intensity());
            assertEquals(0, engine.blockLightLevel());
        }
        for (float invalid : new float[] {-1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertEquals(20, RVP_LightParameters.engine(invalid).radius());
        }
    }

    @Test
    void stageAndBurnoutDimBrightnessWithoutChangingTheCaliberRange() {
        for (float caliber : new float[] {0, 100, 4800}) {
            RVP_LightParameters first = RVP_LightParameters.engine(caliber);
            for (boolean second : new boolean[] {false, true}) {
                for (int remaining : new int[] {100, 20, 1, 0}) {
                    RVP_LightParameters sample = RVP_LightParameters.enginePhase(first, second, remaining);
                    assertEquals(first.radius(), sample.radius());
                    assertTrue(sample.radius() >= 20 && sample.radius() <= 500);
                    assertTrue(sample.intensity() <= first.intensity());
                }
            }
        }
    }

    /** 比较真实解析结果，确认未知光字段不改变普通武器数据的序列化语义。 */
    private static <T> void assertIgnored(String json, Class<T> type) {
        assertEquals(GSON.toJsonTree(GSON.fromJson("{}", type)), GSON.toJsonTree(GSON.fromJson(json, type)));
    }
}
