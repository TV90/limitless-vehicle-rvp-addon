package org.ywzj.rvp.client.light;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 预算与诊断回调：剔除原因必须与正式判定一致，且只有开启诊断时才回调。 */
class RVP_ScreenLightBudgetTest {
    /** 普通爆炸快照：照亮半径 60 格、光晕 12 格、不染天空。 */
    private static RVP_ScreenLight explosion(Vec3 position) {
        return new RVP_ScreenLight(position, 1, 0.82F, 0.48F, 60, 12, 25, 16, 10,
                RVP_LightSourceType.EXPLOSION, null, 0.85F, true, 0);
    }

    /** 只染天空的快照：照亮与光晕都为 0，天空强度非 0。 */
    private static RVP_ScreenLight skyOnly(Vec3 position, float skyIntensity) {
        return new RVP_ScreenLight(position, 1, 0.82F, 0.48F, 0, 0, 0, 0, skyIntensity, 1, null, 20,
                RVP_LightSourceType.EXPLOSION, null, 0.85F, true, 0, null);
    }

    /** 只有圆形白热核心的快照：照亮、光晕、天空都为 0。 */
    private static RVP_ScreenLight coreOnly(Vec3 position, float coreRadius) {
        return new RVP_ScreenLight(position, 1, 0.82F, 0.48F, 0, 0, 0, 0, 0, 1,
                new org.ywzj.rvp.light.RVP_LightParameters.EngineEmission(coreRadius, coreRadius * 3.5F, 60000, 12),
                20, RVP_LightSourceType.EXPLOSION, null, 0.85F, true, 0, null);
    }

    @Test
    void distanceAndLimitRejectionsAreReportedWithNumbers() {
        List<String> reasons = new ArrayList<>();
        List<RVP_ScreenLight> source = new ArrayList<>();
        // 空爆常见情形：爆心远高于观察点，超过 maxDistance + 可见半径 后被整盏剔除。
        source.add(explosion(new Vec3(0, 400, 0)));
        for (int index = 0; index < 10; index++) {
            // 十条近处同类光（都在 316 格预算内），验证八灯截断会给出“名额不足”而不是“无贡献”。
            source.add(explosion(new Vec3(index * 20, 0, 0)));
        }
        List<RVP_ScreenLight> selected = RVP_ScreenLightBudget.collect(source, Vec3.ZERO, 256, 8,
                light -> true, (light, reason) -> reasons.add(reason));
        assertEquals(8, selected.size());
        assertTrue(reasons.stream().anyMatch(reason -> reason.contains("超出距离预算")),
                "远距离空爆必须报告距离预算原因：" + reasons);
        assertTrue(reasons.stream().anyMatch(reason -> reason.contains("八灯预算不足")),
                "超出名额的光源必须报告预算原因：" + reasons);
        // 正式入口不带回调，结果必须一致。
        assertEquals(selected, RVP_ScreenLightBudget.collect(source, Vec3.ZERO, 256, 8, light -> true));
    }

    @Test
    void skyOnlyLightSurvivesBudgetAndContributionLessLightIsExplained() {
        List<String> reasons = new ArrayList<>();
        List<RVP_ScreenLight> selected = RVP_ScreenLightBudget.collect(
                List.of(skyOnly(Vec3.ZERO, 0.8F), skyOnly(new Vec3(10, 0, 0), 0), coreOnly(new Vec3(0, 0, 40), 1.2F)),
                Vec3.ZERO, 256, 8, light -> true, (light, reason) -> reasons.add(reason));
        // 天空强度非 0 时即使照亮与光晕都为 0 也必须提交；只有白热核心的光源同样必须保留。
        assertEquals(2, selected.size());
        assertTrue(selected.stream().anyMatch(light -> light.skyIntensity() > 0.5F));
        assertTrue(selected.stream().anyMatch(light -> light.emission() != null));
        // 三者全为 0 的那条要给出原因。
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("没有任何可见贡献"), reasons.get(0));
    }

    @Test
    void skyLightsUseTheirOwnDistanceBudgetAndFallBackWhenItIsZero() {
        List<String> reasons = new ArrayList<>();
        RVP_ScreenLight far = skyOnly(new Vec3(0, 0, 1500), 0.8F);
        // 普通预算 256 格：天空灯在 1500 网格外，必须被剔除并说明距离。
        assertTrue(RVP_ScreenLightBudget.collect(List.of(far), Vec3.ZERO, 256, 8, light -> true,
                (light, reason) -> reasons.add(reason)).isEmpty());
        assertTrue(reasons.get(0).contains("超出距离预算"), reasons.get(0));
        // 天空专用预算 2048 格：同一盏灯被保留，普通照亮仍只用 256 格。
        List<RVP_ScreenLight> kept = RVP_ScreenLightBudget.collect(List.of(far), Vec3.ZERO, 256, 2048, 8,
                light -> true, null);
        assertEquals(1, kept.size());
        // 专用预算为 0 时退回普通预算。
        assertTrue(RVP_ScreenLightBudget.collect(List.of(far), Vec3.ZERO, 256, 0, 8, light -> true, null).isEmpty());
        // 地面照亮灯不受天空专用预算影响。
        RVP_ScreenLight surface = explosion(new Vec3(0, 0, 1500));
        assertTrue(RVP_ScreenLightBudget.collect(List.of(surface), Vec3.ZERO, 256, 2048, 8,
                light -> true, null).isEmpty());
    }

    @Test
    void diagnosticsStaySilentWhenTheCallbackIsAbsent() {
        List<RVP_ScreenLight> source = List.of(explosion(new Vec3(0, 400, 0)), explosion(new Vec3(0, 0, 4)));
        List<RVP_ScreenLight> selected = RVP_ScreenLightBudget.collect(source, Vec3.ZERO, 128, 8, light -> true);
        assertEquals(1, selected.size());
        assertFalse(selected.isEmpty());
    }
}
