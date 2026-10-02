package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证炮口烟/殉燃口径与曳光视觉口径使用独立字段。 */
class RVP_EffectsDataCaliberTest {

    /** 测试 JSON 解析使用的 Gson 实例。 */
    private static final Gson GSON = new Gson();

    /** 显式 tracer_caliber 只覆盖曳光口径，caliber 保持炮口烟/殉燃口径。 */
    @Test
    void tracerCaliberIsIndependentFromCaliber() {
        RVP_EffectsData effects = GSON.fromJson(
                "{\"caliber\":125,\"tracer_caliber\":24.765}", RVP_EffectsData.class);

        assertEquals(125.0f, effects.getCaliber(), 1.0E-6f);
        assertEquals(24.765f, effects.getTracerCaliber(), 1.0E-6f);
    }

    /** 缺少 tracer_caliber 时仍回退 caliber，保持未拆分数据的视觉行为。 */
    @Test
    void tracerCaliberFallsBackToCaliber() {
        RVP_EffectsData effects = GSON.fromJson("{\"caliber\":30}", RVP_EffectsData.class);

        assertEquals(30.0f, effects.getCaliber(), 1.0E-6f);
        assertEquals(30.0f, effects.getTracerCaliber(), 1.0E-6f);
    }
}
