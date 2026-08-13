package org.ywzj.rvp.countermeasure;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证游戏内实际载具 JSON（顶层 countermeasure 键）能被 RVP 数据模型正确解析。
 */
class GameVehicleCountermeasureParseTest {

    private static final String GAME_VEHICLE = "E:/client_ywzj - 副本/ywzj/.minecraft/versions/Optimized fps/limitless_vehicle/rvp/data/rvp/vehicles/f22a.json";

    @Test
    void parseGameF22a() throws Exception {
        File file = new File(GAME_VEHICLE);
        assertTrue(file.exists(), "游戏载具文件不存在");
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        JsonElement element = JsonParser.parseString(text);
        RVP_CountermeasureData data = RVP_CountermeasureData.parse(element);
        assertNotNull(data, "载具 JSON 顶层 countermeasure 解析为 null（解析 bug）");
        assertTrue(data.isEnabled());

        RVP_CountermeasureSystemData flare = data.getFlare();
        assertNotNull(flare);
        assertEquals("decoy_flare_barrel", flare.getLauncherParts().get(0));
        assertEquals(32, flare.getTotal());
        assertEquals(16711680, flare.getDecoy().getGlowColor());

        RVP_CountermeasureSystemData chaff = data.getChaff();
        assertNotNull(chaff);
        assertEquals("decoy_flare_barrel", chaff.getLauncherParts().get(0));
        assertEquals(60, chaff.getRadarJamCooldownTick());
    }
}
