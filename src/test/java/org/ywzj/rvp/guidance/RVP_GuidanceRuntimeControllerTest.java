package org.ywzj.rvp.guidance;

import com.google.gson.Gson;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataAdapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_GuidanceRuntimeControllerTest {

    private final Gson gson = new Gson();

    /** 模拟实体先更新、通用控制器后更新；两个入口都必须服从相同的交接保护。 */
    @Test
    void duplicatePhaseCallsCannotBypassLaunchAndAttitudeProtection() {
        RVP_GuidanceData guidance = parse("""
                {"guidance_type":"GPS","preset_cruise_altitude":750,
                 "terminal_guidance":{"guidance_type":"ARH","guidance_start_dist":200}}
                """);
        var state = new RVP_GuidancePhaseState();
        var early = new RVP_GuidanceTransitionContext(5, 100, 100);
        // 调用生产共用入口两次，复现实体与父类顺序而不是仅检查布尔保护函数。
        assertFalse(RVP_GuidanceRuntimeController.updatePhase(state, guidance, early, 20, 20, false, false));
        assertFalse(RVP_GuidanceRuntimeController.updatePhase(state, guidance, early, 20, 20, false, false));
        assertFalse(state.isTerminal());
        var ignited = new RVP_GuidanceTransitionContext(20, 100, 100);
        assertFalse(RVP_GuidanceRuntimeController.updatePhase(state, guidance, ignited, 20, 20, false, true));
        assertFalse(RVP_GuidanceRuntimeController.updatePhase(state, guidance, ignited, 20, 20, true, false));
        assertTrue(RVP_GuidanceRuntimeController.updatePhase(state, guidance, ignited, 20, 20, true, true));
        assertFalse(RVP_GuidanceRuntimeController.updatePhase(state, guidance, ignited, 20, 20, true, true));
        assertTrue(state.isTerminal());
    }

    /** 搜索开机不等于捕获；只有实体意图才交接控制，禁用惯性时不得强行恢复坐标制导。 */
    @Test
    void presetFallbackWaitsForEntityIntentAndRespectsInertialSetting() {
        RVP_GuidanceData guidance = parse("""
                {"guidance_type":"GPS","preset_cruise_altitude":750,
                 "terminal_guidance":{"guidance_type":"ARH","enable_inertial_guidance":true}}
                """);
        // 调用实际阶段配置解析器与回退策略，验证扫描失败/成功两条路径。
        var active = RVP_GuidanceModelResolver.resolveActive(guidance, RVP_GuidancePhase.TERMINAL);
        assertTrue(RVP_GuidanceRuntimeMath.usesPresetPointFallback(guidance, active, false));
        assertFalse(RVP_GuidanceRuntimeMath.usesPresetPointFallback(guidance, active, true));
        var disabled = parse("""
                {"guidance_type":"GPS","preset_cruise_altitude":750,
                 "terminal_guidance":{"guidance_type":"ARH","enable_inertial_guidance":false}}
                """);
        assertFalse(RVP_GuidanceRuntimeMath.usesPresetPointFallback(disabled,
                RVP_GuidanceModelResolver.resolveActive(disabled, RVP_GuidancePhase.TERMINAL), false));
    }

    /** PRESET 近距离发射时，冷发射/点火窗口内必须保留主段，不能提前切终端 ARH。 */
    @Test
    void presetTerminalTransitionWaitsForLaunchWindow() {
        RVP_GuidanceData guidance = parse("""
                {
                  "guidance_type":"GPS",
                  "preset_cruise_altitude":750,
                  "terminal_guidance":{
                    "guidance_type":"ARH",
                    "guidance_start_dist":200
                  }
                }
                """);

        assertTrue(RVP_GuidanceRuntimeController.shouldDeferPresetTerminalTransition(
                guidance, 19, 20, 20));
        assertFalse(RVP_GuidanceRuntimeController.shouldDeferPresetTerminalTransition(
                guidance, 20, 20, 20));
    }

    /** 非 PRESET 弹仍按原终端阶段逻辑运行，不被冷发射保护门影响。 */
    @Test
    void nonPresetGuidanceIsNotDeferred() {
        RVP_GuidanceData guidance = parse("""
                {
                  "guidance_type":"GPS",
                  "terminal_guidance":{"guidance_type":"ARH"}
                }
                """);

        assertFalse(RVP_GuidanceRuntimeController.shouldDeferPresetTerminalTransition(
                guidance, 5, 20, 20));
    }

    private RVP_GuidanceData parse(String guidanceJson) {
        String json = "{\"guidance_data\":" + guidanceJson + "}";
        return gson.fromJson(json, GuidanceHolder.class).guidanceData;
    }

    private static final class GuidanceHolder {
        @SerializedName("guidance_data")
        @JsonAdapter(RVP_GuidanceDataAdapter.class)
        private RVP_GuidanceData guidanceData;
    }
}
