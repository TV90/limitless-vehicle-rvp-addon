package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

/**
 * 引信参数。JSON 键 {@code fuse_data}；子弹、火箭、导弹、炸弹、布撒弹共用。
 * 近炸半径优先读本类 {@link #proximityRadius}，否则可读
 * {@link RVP_DetonateData} 内爆炸数据的 {@code proximity_radius}。
 */
public class RVP_FuseData {

    /**
     * 定时引信：飞行 tick 达到该值后引爆；0 表示不启用。
     * 原 {@code time_tick} 在加载时合并到此字段。
     */
    @SerializedName("delay_tick")
    private int delayTick = 0;

    /**
     * 可编程空爆（MCH）：玩家按锁定键（R）测距后，弹体沿弹道累计飞行距离达到
     * {@code 测距 + airburst_offset} 米时引爆。未测距或测距无效时不触发。
     */
    @SerializedName("programmable_airburst")
    private boolean programmableAirburst = false;

    /** 可编程空爆：测距目标距离之外的附加米数（MCH 固定 +3m，此处可配）。 */
    @SerializedName("airburst_offset")
    private float airburstOffset = 3f;

    /** 可编程空爆：有效测距下限（米），低于等于该值视为未设置（MCH 默认 5）。 */
    @SerializedName("airburst_measure_min")
    private int airburstMeasureMin = 5;

    /** 可编程空爆：有效测距上限（米），高于等于该值视为未设置（MCH 默认 300）。 */
    @SerializedName("airburst_measure_max")
    private int airburstMeasureMax = 300;

    /** 近炸引信半径（米），0 表示不启用 RVP 近炸检测。 */
    @SerializedName("proximity_radius")
    private float proximityRadius = 0f;

    /** 近炸引信：出生后至少经过多少 tick 才启用；&lt;0 表示不限制（MCH 默认 -1）。 */
    @SerializedName("proximity_fuse_tick")
    private int proximityFuseTick = -1;

    /**
     * 近炸引信目标最低高度（格）：目标 {@code onGround} 或脚下该深度内有实心方块时**不触发**
     * （MCH {@code ProximityFuseHeight}，默认 20）。0 表示仅 {@code onGround} 判定。
     */
    @SerializedName("proximity_fuse_height")
    private int proximityFuseHeight = 20;

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

    public float getProximityRadius() {
        return Math.max(proximityRadius, 0f);
    }

    public int getProximityFuseTick() {
        return proximityFuseTick;
    }

    public int getProximityFuseHeight() {
        return Math.max(proximityFuseHeight, 0);
    }

    public boolean isDetonateOnLifeEnd() {
        return detonateOnLifeEnd;
    }
}
