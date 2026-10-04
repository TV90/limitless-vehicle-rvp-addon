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
