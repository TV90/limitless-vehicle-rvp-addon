package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@code projectile_data.collision_box_size} 解析钳制回归；不构造世界或实体。 */
class RVP_ProjectileDataCollisionBoxSizeTest {

    private final Gson gson = new Gson();

    /** 未配置回退实体注册默认 1/16 格，历史行为逐位不变。 */
    @Test
    void missingFieldFallsBackToDefault() {
        RVP_ProjectileData data = gson.fromJson("{}", RVP_ProjectileData.class);
        assertEquals(RVP_ProjectileData.COLLISION_BOX_SIZE_DEFAULT, data.getCollisionBoxSize(), 1.0E-6F);
        assertEquals(0.0625F, data.getCollisionBoxSize(), 1.0E-6F);
    }

    /** 合法配置原样生效。 */
    @Test
    void validValueIsKept() {
        RVP_ProjectileData data = gson.fromJson("{\"collision_box_size\": 2.0}", RVP_ProjectileData.class);
        assertEquals(2.0F, data.getCollisionBoxSize(), 1.0E-6F);
    }

    /** 非法值（负数/NaN）回退默认而非放大。 */
    @Test
    void illegalValueFallsBackToDefault() {
        assertEquals(RVP_ProjectileData.COLLISION_BOX_SIZE_DEFAULT,
                gson.fromJson("{\"collision_box_size\": -1.0}", RVP_ProjectileData.class).getCollisionBoxSize(), 1.0E-6F);
        assertEquals(RVP_ProjectileData.COLLISION_BOX_SIZE_DEFAULT,
                gson.fromJson("{\"collision_box_size\": 0.01}", RVP_ProjectileData.class).getCollisionBoxSize(), 1.0E-6F);
    }

    /** 过大值钳制到 16 格上限。 */
    @Test
    void oversizeValueIsClamped() {
        RVP_ProjectileData data = gson.fromJson("{\"collision_box_size\": 64.0}", RVP_ProjectileData.class);
        assertEquals(16.0F, data.getCollisionBoxSize(), 1.0E-6F);
    }

    /** JsonObject 路径等价验证（Gson 直反序列化与嵌套解析共用同一 getter）。 */
    @Test
    void jsonObjectPathMatchesDirectPath() {
        JsonObject root = new JsonObject();
        JsonObject projectile = new JsonObject();
        projectile.addProperty("collision_box_size", 1.5D);
        root.add("projectile_data", projectile);
        RVP_ProjectileData data = gson.fromJson(root.getAsJsonObject("projectile_data"), RVP_ProjectileData.class);
        assertEquals(1.5F, data.getCollisionBoxSize(), 1.0E-6F);
    }
}
