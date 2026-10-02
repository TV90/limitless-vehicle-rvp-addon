package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;
import org.ywzj.rvp.client.state.RVP_ClientGPSState.Mode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GPS 模式参数化数据解析（gps_modes/multi_max_points/radar_update_interval_second）纯逻辑测试。 */
class RVP_GuidanceDataGpsModesTest {

    private static RVP_GuidanceDataGPS parse(String json) {
        return new Gson().fromJson(json, RVP_GuidanceDataGPS.class);
    }

    /** 缺省/空列表回退默认 [SINGLE, MULTI]——存量武器零变化。 */
    @Test
    void missingOrEmptyModesFallBackToSingleMulti() {
        assertEquals(List.of(Mode.SINGLE, Mode.MULTI), parse("{}").getConfiguredModes());
        assertEquals(List.of(Mode.SINGLE, Mode.MULTI),
                parse("{\"gps_modes\":[]}").getConfiguredModes());
        assertEquals(List.of(Mode.SINGLE, Mode.MULTI),
                RVP_GuidanceDataGPS.resolveConfiguredModes(null));
    }

    /** 大小写不敏感、未知值丢弃、去重。 */
    @Test
    void modesParseIgnoreCaseDropUnknownAndDeduplicate() {
        List<Mode> modes = parse(
                "{\"gps_modes\":[\"fast\",\"bogus\",\"MULTI\",\"Fast\",\"RADAR\",\"single\"]}")
                .getConfiguredModes();
        assertEquals(List.of(Mode.FAST, Mode.MULTI, Mode.RADAR, Mode.SINGLE), modes);
    }

    /** 超过 4 个截断前 4 个（"最多填写四种"）。 */
    @Test
    void modesCapAtFourEntries() {
        List<Mode> modes = parse(
                "{\"gps_modes\":[\"radar\",\"single\",\"multi\",\"fast\",\"radar\"]}")
                .getConfiguredModes();
        assertEquals(List.of(Mode.RADAR, Mode.SINGLE, Mode.MULTI, Mode.FAST), modes);
        assertEquals(4, modes.size());
    }

    /** MULTI 上限钳制 [1, 64]，缺省 8；RADAR 间隔钳制 [0.5, 60]，缺省 5。 */
    @Test
    void multiCapAndRadarIntervalClamped() {
        assertEquals(8, parse("{}").getMultiMaxPoints());
        assertEquals(1, parse("{\"multi_max_points\":0}").getMultiMaxPoints());
        assertEquals(64, parse("{\"multi_max_points\":999}").getMultiMaxPoints());
        assertEquals(5.0f, parse("{}").getRadarUpdateIntervalSecond(), 1.0E-6f);
        assertEquals(0.5f, parse("{\"radar_update_interval_second\":0}")
                .getRadarUpdateIntervalSecond(), 1.0E-6f);
        assertEquals(60.0f, parse("{\"radar_update_interval_second\":999}")
                .getRadarUpdateIntervalSecond(), 1.0E-6f);
    }

    /** 非 GPS 数据实例（含 null）双端解析回退默认。 */
    @Test
    void resolveHelpersFallBackOnNonGpsData() {
        RVP_GuidanceData plain = new RVP_GuidanceData();
        assertEquals(List.of(Mode.SINGLE, Mode.MULTI),
                RVP_GuidanceDataGPS.resolveConfiguredModes(plain));
        assertEquals(8, RVP_GuidanceDataGPS.resolveMultiMaxPoints(plain));
        assertEquals(8, RVP_GuidanceDataGPS.resolveMultiMaxPoints(null));
        assertEquals(5.0f, RVP_GuidanceDataGPS.resolveRadarUpdateIntervalSecond(plain), 1.0E-6f);
        assertTrue(RVP_GuidanceDataGPS.parseMode("nope") == null);
        assertEquals(Mode.RADAR, RVP_GuidanceDataGPS.parseMode("radar"));
    }
}
