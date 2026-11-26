package org.ywzj.rvp.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.ywzj.rvp.mixin.WeaponUnitPojoMixin;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_ThermalImagerTypeTest {
    private final Gson gson = new Gson();

    @ParameterizedTest
    @CsvSource({"white_hot,0", "low_res_white_hot,1", "green_low_light,2", "blue_low_light,3",
            "black_hot,4", "yellow_purple,5", "low_res_red_hot,6", "very_low_res_white_hot,7"})
    void readsStationPalette(String name, int mode) {
        var station = gson.fromJson("{\"thermal_imager_type\":\"" + name + "\"}", WeaponUnitPojoMixin.class);
        assertEquals(mode, station.ywzj_rvp$getThermalImagerType().shaderMode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"thermal_imager_type\":null}", "{\"thermal_imager_type\":\"missing\"}",
            "{\"thermal_imager_type\":[]}", "{\"thermal_imager_type\":[null,{},9,\"missing\"]}"})
    void fallsBackToOriginalWhiteHot(String json) {
        assertEquals(RVP_ThermalImagerType.WHITE_HOT,
                gson.fromJson(json, WeaponUnitPojoMixin.class).ywzj_rvp$getThermalImagerType());
    }

    @Test
    void keepsConfiguredOrderAndDropsDuplicateOrUnknownModes() {
        var station = gson.fromJson("""
                {"thermal_imager_type":["low_res_red_hot",null,"future","black_hot","low_res_red_hot","yellow_purple"]}
                """, WeaponUnitPojoMixin.class);
        assertEquals(List.of(RVP_ThermalImagerType.LOW_RES_RED_HOT, RVP_ThermalImagerType.BLACK_HOT,
                RVP_ThermalImagerType.YELLOW_PURPLE), station.ywzj_rvp$getThermalImagerTypes());
        assertEquals(RVP_ThermalImagerType.LOW_RES_RED_HOT, station.ywzj_rvp$getThermalImagerType());
    }
}
