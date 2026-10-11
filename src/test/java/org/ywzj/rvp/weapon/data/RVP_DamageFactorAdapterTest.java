package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_DamageFactorAdapterTest {

    /**
     * 生产路径是字段级 {@code @JsonAdapter}（挂在 {@code RVP_Explosion.explosionDamageFactor} 上），
     * 因此测试也用字段级 Holder——若把 adapter 注册成类型级，{@code context.deserialize} 会
     * 路由回 adapter 自身无限递归（StackOverflow），且会污染 direct_damage_factor 的默认解析。
     */
    private static class Holder {
        @JsonAdapter(RVP_DamageFactorAdapter.class)
        @SerializedName("f")
        RVP_DamageFactor factor;
    }

    private static RVP_DamageFactor parse(String json) {
        return new Gson().fromJson(json, Holder.class).factor;
    }

    /** 数字写法 2.0 ⇒ vehicle_default=2.0（旧语义：对所有载具统一）。 */
    @Test
    void numberFormWrapsAsVehicleDefault() {
        RVP_DamageFactor factor = parse("{\"f\": 2.0}");
        assertNotNull(factor);
        assertEquals(2.0f, factor.getVehicleDefault(), 1.0E-6f);
        assertTrue(factor.isConfigured());
    }

    /** 对象写法直通：vehicles 按机型命中，未命中回退 vehicle_default。 */
    @Test
    void objectFormParsesDirectly() {
        String json = "{\"f\": {\"vehicle_default\": 1.0, "
                + "\"vehicles\": {\"ywzj_vehicle:rotary_wing_vehicle\": 2.0}}}";
        RVP_DamageFactor factor = parse(json);
        assertNotNull(factor);
        assertEquals(1.0f, factor.getVehicleDefault(), 1.0E-6f);
        assertEquals(2.0f, factor.getVehicles().get("ywzj_vehicle:rotary_wing_vehicle"), 1.0E-6f);
        assertFalse(factor.getVehicles().containsKey("ywzj_vehicle:tracked_vehicle"));
    }

    @Test
    void nullAndInvalidValuesYieldNull() {
        assertNull(parse("{\"f\": null}"));
        assertNull(parse("{\"f\": 0}"));
        assertNull(parse("{\"f\": -1.5}"));
        assertNull(parse("{\"f\": \"text\"}"));
    }

    /** 序列化写回对象形态（Gson 字段级 adapter 要求双侧实现）。 */
    @Test
    void serializeWritesObjectForm() {
        Holder holder = new Gson().fromJson("{\"f\": 2.0}", Holder.class);
        String json = new Gson().toJson(holder);
        assertTrue(json.contains("vehicle_default"), json);
        assertTrue(json.contains("f"), json);
    }
}
