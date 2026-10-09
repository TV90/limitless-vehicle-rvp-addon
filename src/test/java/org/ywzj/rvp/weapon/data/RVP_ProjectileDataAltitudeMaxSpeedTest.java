package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证高度分层极速倍率表的语义：<b>节点线性插值（全曲线连续无跳变）</b>——每档倍率是该档
 * 上边界处的节点值，高度在相邻节点之间线性过渡，低于首节点取首档值；上界无穷的尾档从
 * 上一节点起按前一档宽度线性过渡到尾档值并保持。
 * 不存在默认分层表：未配置（null）、显式空表或全部非法值一律按 1.0 处理（极速不随高度变化）。
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

    /** 生产四档配置：节点=各档上边界（128→0.8、256→1.0、512→1.15），尾档 1.3 在 512+256=768 处达成。 */
    @Test
    void productionTiersInterpolateBetweenBoundaries() {
        RVP_ProjectileData data = gson.fromJson("""
                {"altitude_max_speed_factor":{"[[512,inf]]":1.3,"[[256,512]]":1.15,
                 "[[128,256]]":1.0,"[[-inf,128]]":0.8}}
                """, RVP_ProjectileData.class);

        assertEquals(0.8f, data.resolveAltitudeMaxSpeedFactor(50), 1.0E-6f);
        assertEquals(0.8f, data.resolveAltitudeMaxSpeedFactor(128), 1.0E-6f);
        assertEquals(0.9f, data.resolveAltitudeMaxSpeedFactor(192), 1.0E-6f);
        assertEquals(1.0f, data.resolveAltitudeMaxSpeedFactor(256), 1.0E-6f);
        assertEquals(1.075f, data.resolveAltitudeMaxSpeedFactor(384), 1.0E-6f);
        assertEquals(1.15f, data.resolveAltitudeMaxSpeedFactor(512), 1.0E-6f);
        assertEquals(1.225f, data.resolveAltitudeMaxSpeedFactor(640), 1.0E-6f);
        assertEquals(1.3f, data.resolveAltitudeMaxSpeedFactor(768), 1.0E-6f);
        assertEquals(1.3f, data.resolveAltitudeMaxSpeedFactor(1000), 1.0E-6f);
    }

    /** 两档配置：节点=上边界（100→0.8），尾档 1.15 在 100+164=264 处达成。 */
    @Test
    void twoTierConfigHoldsLowerPlateauThenInterpolates() {
        RVP_ProjectileData data = gson.fromJson("""
                {"altitude_max_speed_factor":{"[[-64,100]]":0.8,"[[100,inf]]":1.15}}
                """, RVP_ProjectileData.class);

        assertEquals(0.8f, data.resolveAltitudeMaxSpeedFactor(-64), 1.0E-6f);
        assertEquals(0.8f, data.resolveAltitudeMaxSpeedFactor(0), 1.0E-6f);
        assertEquals(0.8f, data.resolveAltitudeMaxSpeedFactor(100), 1.0E-6f);
        assertEquals(1.15f, data.resolveAltitudeMaxSpeedFactor(1000), 1.0E-6f);
    }

    /** 显式空表表示明确关闭分层（全高度 1.0），与缺省字段同效。 */
    @Test
    void emptyTableEqualsFlat() {
        RVP_ProjectileData empty = gson.fromJson(
                "{\"altitude_max_speed_factor\":{}}", RVP_ProjectileData.class);
        assertEquals(1.0f, empty.resolveAltitudeMaxSpeedFactor(200), 1.0E-6f);
    }

    /** 全部区间值非法（≤0 / 非有限）时无可用节点，恒按 1.0 处理，不传播非法倍率。 */
    @Test
    void invalidSegmentValueFallsBackToOne() {
        RVP_ProjectileData data = gson.fromJson(
                "{\"altitude_max_speed_factor\":{\"[[-inf,100]]\":-0.5,\"[[100,inf]]\":0}}",
                RVP_ProjectileData.class);
        assertEquals(1.0f, data.resolveAltitudeMaxSpeedFactor(0), 1.0E-6f);
        assertEquals(1.0f, data.resolveAltitudeMaxSpeedFactor(500), 1.0E-6f);
    }

    /** 非法档被跳过后，剩余合法节点照常生效（不再产生 1.0 回退）。 */
    @Test
    void invalidEntriesAreSkippedValidAnchorsStillApply() {
        RVP_ProjectileData data = gson.fromJson(
                "{\"altitude_max_speed_factor\":{\"[[-inf,100]]\":-0.5,\"[[100,inf]]\":1.15}}",
                RVP_ProjectileData.class);
        assertEquals(1.15f, data.resolveAltitudeMaxSpeedFactor(0), 1.0E-6f);
        assertEquals(1.15f, data.resolveAltitudeMaxSpeedFactor(500), 1.0E-6f);
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
