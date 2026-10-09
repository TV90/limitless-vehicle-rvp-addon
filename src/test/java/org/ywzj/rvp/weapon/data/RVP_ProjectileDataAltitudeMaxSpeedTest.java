package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证高度分层极速倍率表的语义。与阻力倍率的唯一差异：不存在默认分层表——
 * 未配置（null）、显式空表、未命中区间或非法值一律按 1.0 处理（极速不随高度变化）。
 */
class RVP_ProjectileDataAltitudeMaxSpeedTest {

    /** 当前 schema 的 JSON 反序列化器。 */
    private final Gson gson = new Gson();

    /** 未配置字段时极速倍率恒为 1.0（平极速），任何高度都不产生分层。 */
    @Test
    void absentFieldKeepsFlatMaxSpeed() {
        RVP_ProjectileData data = gson.fromJson("{}", RVP_ProjectileData.class);
        assertEquals(1.0f, data.resolveAltitudeMaxSpeedFactor(-64), 1.0E-6f);
        assertEquals(1.0f, data.resolveAltitudeMaxSpeedFactor(0), 1.0E-6f);
        assertEquals(1.0f, data.resolveAltitudeMaxSpeedFactor(320), 1.0E-6f);
        assertEquals(1.0f, data.resolveAltitudeMaxSpeedFactor(4000), 1.0E-6f);
    }

    /** 显式区间表取段值、不插值；未命中区间回退 1.0。 */
    @Test
    void explicitTableTakesSegmentValueWithoutInterpolation() {
        RVP_ProjectileData data = gson.fromJson("""
                {"altitude_max_speed_factor":{"[[-64,100]]":0.8,"[[100,inf]]":1.15}}
                """, RVP_ProjectileData.class);

        assertEquals(0.8f, data.resolveAltitudeMaxSpeedFactor(-64), 1.0E-6f);
        assertEquals(0.8f, data.resolveAltitudeMaxSpeedFactor(0), 1.0E-6f);
        assertEquals(1.15f, data.resolveAltitudeMaxSpeedFactor(150), 1.0E-6f);
        assertEquals(1.15f, data.resolveAltitudeMaxSpeedFactor(1000), 1.0E-6f);

        RVP_ProjectileData gapped = gson.fromJson(
                "{\"altitude_max_speed_factor\":{\"[[0,100]]\":0.9}}", RVP_ProjectileData.class);
        assertEquals(1.0f, gapped.resolveAltitudeMaxSpeedFactor(-64), 1.0E-6f);
        assertEquals(0.9f, gapped.resolveAltitudeMaxSpeedFactor(50), 1.0E-6f);
        assertEquals(1.0f, gapped.resolveAltitudeMaxSpeedFactor(1000), 1.0E-6f);
    }

    /** 显式空表表示明确关闭分层（全高度 1.0），与缺省字段同效。 */
    @Test
    void emptyTableEqualsFlat() {
        RVP_ProjectileData empty = gson.fromJson(
                "{\"altitude_max_speed_factor\":{}}", RVP_ProjectileData.class);
        assertEquals(1.0f, empty.resolveAltitudeMaxSpeedFactor(200), 1.0E-6f);
    }

    /** 区间值非法（≤0 / 非有限）时该段按 1.0 处理，不抛出、不传播非法倍率。 */
    @Test
    void invalidSegmentValueFallsBackToOne() {
        RVP_ProjectileData data = gson.fromJson(
                "{\"altitude_max_speed_factor\":{\"[[-inf,100]]\":-0.5,\"[[100,inf]]\":0}}",
                RVP_ProjectileData.class);
        assertEquals(1.0f, data.resolveAltitudeMaxSpeedFactor(0), 1.0E-6f);
        assertEquals(1.0f, data.resolveAltitudeMaxSpeedFactor(500), 1.0E-6f);
    }

    /** 倍率直接缩放钳制上限：max_speed=6 时，倍率 0.8 → 有效极速 4.8；倍率 1.15 → 6.9。 */
    @Test
    void factorScalesEffectiveMaxSpeed() {
        RVP_ProjectileData scaled = gson.fromJson("""
                {"max_speed":6.0,"altitude_max_speed_factor":{"[[-inf,100]]":0.8,"[[100,inf]]":1.15}}
                """, RVP_ProjectileData.class);

        assertEquals(4.8f, scaled.getMaxSpeed()
                * scaled.resolveAltitudeMaxSpeedFactor(0), 1.0E-4f);
        assertEquals(6.9f, scaled.getMaxSpeed()
                * scaled.resolveAltitudeMaxSpeedFactor(500), 1.0E-4f);
    }
}
