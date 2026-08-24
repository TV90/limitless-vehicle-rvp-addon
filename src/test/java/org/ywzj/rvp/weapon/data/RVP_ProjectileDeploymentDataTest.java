package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RVP_ProjectileDeploymentDataTest {

    /** 测试当前 schema 反序列化与非法值归一化的 Gson 实例。 */
    private static final Gson GSON = new Gson();

    @Test
    void deploymentHalfLifeUsesSafeCurrentSchemaValues() {
        RVP_ProjectileData defaults = GSON.fromJson("{}", RVP_ProjectileData.class);
        RVP_ProjectileData configured = GSON.fromJson(
                "{\"deployment_horizontal_half_life_ticks\":20}", RVP_ProjectileData.class);
        RVP_ProjectileData negative = GSON.fromJson(
                "{\"deployment_horizontal_half_life_ticks\":-5}", RVP_ProjectileData.class);
        RVP_ProjectileData invalid = GSON.fromJson(
                "{\"deployment_horizontal_half_life_ticks\":\"NaN\"}", RVP_ProjectileData.class);

        assertEquals(0f, defaults.getDeploymentHorizontalHalfLifeTicks(), 1.0E-6f);
        assertEquals(20f, configured.getDeploymentHorizontalHalfLifeTicks(), 1.0E-6f);
        assertEquals(0f, negative.getDeploymentHorizontalHalfLifeTicks(), 1.0E-6f);
        assertEquals(0f, invalid.getDeploymentHorizontalHalfLifeTicks(), 1.0E-6f);
    }
}
