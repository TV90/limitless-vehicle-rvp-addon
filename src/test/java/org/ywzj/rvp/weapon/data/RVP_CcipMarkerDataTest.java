package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 验证 CCIP 地面标记的武器侧默认值、JSON 覆盖与颜色解析。 */
class RVP_CcipMarkerDataTest {

    private final Gson gson = new Gson();

    @Test
    void defaultsMatchLegacyHardcodedValues() {
        RVP_CcipMarkerData marker = new RVP_CcipMarkerData();
        assertEquals(0.5D, marker.getRadiusScale());
        assertEquals(2.5D, marker.getHeightClamp());
        assertEquals(0.08D, marker.getSurfaceLift());
        assertEquals(0.35D, marker.getLineHeightScreenFraction());
        assertEquals(0.008D, marker.getRingWidthPerBlock());
        assertEquals(0.0035D, marker.getLineWidthPerBlock());
        assertEquals(0xC800FF00, marker.getRingColor());
        assertEquals(0x3000FF00, marker.getLineTopColor());
    }

    @Test
    void jsonOverridesMarkerAppearance() {
        RVP_CcipMarkerData marker = gson.fromJson("""
                {
                  "radius_scale": 0.8,
                  "height_clamp": 1.25,
                  "line_height_screen_fraction": 0.5,
                  "ring_width_per_block": 0.01,
                  "ring_color": "#112233",
                  "line_top_color": "0x80FF0000"
                }
                """, RVP_CcipMarkerData.class);
        assertEquals(0.8D, marker.getRadiusScale());
        assertEquals(1.25D, marker.getHeightClamp());
        assertEquals(0.5D, marker.getLineHeightScreenFraction());
        assertEquals(0.01D, marker.getRingWidthPerBlock());
        assertEquals(0xFF112233, marker.getRingColor());
        assertEquals(0x80FF0000, marker.getLineTopColor());
    }

    @Test
    void weaponDataBindsCcipMarkerJsonKey() throws Exception {
        Field field = RVP_WeaponData.class.getDeclaredField("ccipMarkerData");
        SerializedName name = field.getAnnotation(SerializedName.class);
        assertNotNull(name);
        assertEquals("ccip_marker_data", name.value());
    }
}
