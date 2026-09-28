package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证默认大气插值与显式 JSON 倍率表的优先级。 */
class RVP_ProjectileDataAltitudeDragTest {

    /** 当前 schema 的 JSON 反序列化器，用于验证缺省、空表和显式表的区别。 */
    private final Gson gson = new Gson();

    /** 未配置时应在现实大气锚点间平滑变化，并在表外保持端点倍率。 */
    @Test
    void absentTableUsesCompressedAtmosphere() {
        RVP_ProjectileData data = gson.fromJson("{}", RVP_ProjectileData.class);

        assertEquals(1.000f, data.resolveAltitudeDragFactor(-64), 1.0E-6f);
        assertEquals(1.000f, data.resolveAltitudeDragFactor(64), 1.0E-6f);
        assertEquals(0.338f, data.resolveAltitudeDragFactor(307), 1.0E-6f);
        assertEquals(0.316f, data.resolveAltitudeDragFactor(320), 0.001f);
        assertEquals(0.073f, data.resolveAltitudeDragFactor(550), 1.0E-6f);
        assertEquals(0.014f, data.resolveAltitudeDragFactor(1000), 1.0E-6f);
        assertEquals(data.resolveAltitudeDragFactor(320),
                data.resolveAeroSteeringLimits(3.0, 320).densityFactor(), 1.0E-6);
    }

    /** 显式空表保留旧的全高度 1.0 语义，显式 null 则选用默认表。 */
    @Test
    void explicitEmptyTableDisablesDefault() {
        RVP_ProjectileData empty = gson.fromJson("{\"altitude_drag_factor\":{}}", RVP_ProjectileData.class);
        RVP_ProjectileData nullTable = gson.fromJson("{\"altitude_drag_factor\":null}", RVP_ProjectileData.class);

        assertEquals(1.0f, empty.resolveAltitudeDragFactor(550), 1.0E-6f);
        assertEquals(0.073f, nullTable.resolveAltitudeDragFactor(550), 1.0E-6f);
    }

    /** 显式表按旧规则直接取段值；未命中或非法值不会落到默认大气表。 */
    @Test
    void explicitTableOverridesDefaultWithoutInterpolation() {
        RVP_ProjectileData data = gson.fromJson("""
                {"altitude_drag_factor":{"[[0,200]]":1.5,"[[200,500]]":0.8,"[[500,inf]]":-1.0}}
                """, RVP_ProjectileData.class);

        assertEquals(1.5f, data.resolveAltitudeDragFactor(150), 1.0E-6f);
        assertEquals(0.8f, data.resolveAltitudeDragFactor(320), 1.0E-6f);
        assertEquals(1.0f, data.resolveAltitudeDragFactor(550), 1.0E-6f);
        assertEquals(1.0f, data.resolveAltitudeDragFactor(-1), 1.0E-6f);
    }
}
