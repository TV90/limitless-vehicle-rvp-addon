package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 引信参数。JSON 键 {@code fuse_data}；子弹、火箭、导弹、炸弹、布撒弹共用。
 */
public class RVP_FuseData {

    /** 定时引信：飞行 tick 达到该值后引爆；0 表示不启用。 */
    @SerializedName("delay_tick")
    private int delayTick = 0;

    /** 可编程空爆（MCH）：测距有效时沿弹道累计距离达到 {@code 测距 + airburst_offset} 米时引爆。 */
    @SerializedName("programmable_airburst")
    private boolean programmableAirburst = false;

    @SerializedName("airburst_offset")
    private float airburstOffset = 3f;

    @SerializedName("airburst_measure_min")
    private int airburstMeasureMin = 5;

    @SerializedName("airburst_measure_max")
    private int airburstMeasureMax = 300;

    /** 空爆专用爆炸伤害；未写时使用 {@code detonate_data.explosion_data.damage}。 */
    @SerializedName("airburst_explosion_damage")
    private Float airburstExplosionDamage;

    /** 空爆专用爆炸半径；未写时使用 {@code detonate_data.explosion_data.radius}。 */
    @SerializedName("airburst_explosion_radius")
    private Float airburstExplosionRadius;

    /** 近炸引信：出生后至少经过多少 tick 才启用；&lt;0 表示不限制（默认 -1）。 */
    @SerializedName("proximity_fuse_tick")
    private int proximityFuseTick = -1;

    /**
     * 近炸目标最低高度（格）：目标贴地或脚下该深度内有实心方块时不触发（默认 20）。
     */
    @SerializedName("proximity_fuse_height")
    private int proximityFuseHeight = 20;

    /**
     * 近炸引信半径（米），0 表示不启用 RVP 近炸检测。
     * 未写时可读 {@code detonate_data.explosion_data.proximity_radius}。
     */
    @SerializedName("proximity_radius")
    private float proximityRadius = 0f;

    /** 近炸对命中实体的直接伤害（MCH {@code ProximityFuseDamage}）；未写为 0。 */
    @SerializedName("proximity_fuse_damage")
    private Float proximityFuseDamage;

    /** 近炸专用爆炸伤害；未写时使用 {@code detonate_data.explosion_data.damage}。 */
    @SerializedName("proximity_fuse_explosion_damage")
    private Float proximityFuseExplosionDamage;

    /** 近炸专用爆炸半径；未写时使用 {@code detonate_data.explosion_data.radius}。 */
    @SerializedName("proximity_fuse_explosion_radius")
    private Float proximityFuseExplosionRadius;

    /** 生命周期结束时是否爆炸；false 时只消失。 */
    @SerializedName("detonate_on_life_end")
    private boolean detonateOnLifeEnd = false;

    public int getDelayTick() {
        return Math.max(delayTick, 0);
    }

    public boolean isProgrammableAirburst() {
        return programmableAirburst;
    }

    public float getAirburstOffset() {
        return Math.max(airburstOffset, 0f);
    }

    public int getAirburstMeasureMin() {
        return Math.max(airburstMeasureMin, 0);
    }

    public int getAirburstMeasureMax() {
        return airburstMeasureMax > 0 ? airburstMeasureMax : 300;
    }

    public Float getAirburstExplosionDamage() {
        return airburstExplosionDamage;
    }

    public boolean hasAirburstExplosionDamageOverride() {
        return airburstExplosionDamage != null;
    }

    public Float getAirburstExplosionRadius() {
        return airburstExplosionRadius;
    }

    public boolean hasAirburstExplosionRadiusOverride() {
        return airburstExplosionRadius != null;
    }

    public int getProximityFuseTick() {
        return proximityFuseTick;
    }

    public int getProximityFuseHeight() {
        return Math.max(proximityFuseHeight, 0);
    }

    public float getProximityRadius() {
        return Math.max(proximityRadius, 0f);
    }

    public Float getProximityFuseDamageOverride() {
        return proximityFuseDamage;
    }

    public float getProximityFuseDamage() {
        return proximityFuseDamage == null ? 0f : Math.max(proximityFuseDamage, 0f);
    }

    public boolean hasProximityFuseDamageOverride() {
        return proximityFuseDamage != null;
    }

    public Float getProximityFuseExplosionDamage() {
        return proximityFuseExplosionDamage;
    }

    public boolean hasProximityFuseExplosionDamageOverride() {
        return proximityFuseExplosionDamage != null;
    }

    public Float getProximityFuseExplosionRadius() {
        return proximityFuseExplosionRadius;
    }

    public boolean hasProximityFuseExplosionRadiusOverride() {
        return proximityFuseExplosionRadius != null;
    }

    public boolean isDetonateOnLifeEnd() {
        return detonateOnLifeEnd;
    }
}
