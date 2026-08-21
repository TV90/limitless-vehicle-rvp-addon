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

    /** Enables automatic AHEAD programming. This remains independent from manual programmable airburst. */
    @SerializedName("ahead_enabled")
    private boolean aheadEnabled = false;

    @SerializedName("ahead_burst_offset_meters")
    private float aheadBurstOffsetMeters = 3f;

    @SerializedName("ahead_require_lock")
    private boolean aheadRequireLock = true;

    @SerializedName("ahead_min_ground_clearance")
    private float aheadMinGroundClearance = 0f;

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

    /**
     * 近地引信检测高度（格）：沿世界系绝对 {@code -Y} 检测可碰撞方块，忽略流体；
     * {@code 0} 表示不启用。解保后，弹体当前位置或本 Tick 运动段达到该离地高度时触发。
     */
    @SerializedName("ground_proximity_fuse_distance")
    private float groundProximityFuseDistance = 0f;

    /**
     * 近地引信解保时间（tick）：弹体 {@code updateCount} 达到该值后开始检测；
     * 默认 {@code 0}，表示出生后立即允许检测，仅在 {@code ground_proximity_fuse_distance > 0} 时生效。
     */
    @SerializedName("ground_proximity_fuse_arm_tick")
    private int groundProximityFuseArmTick = 0;

    /**
     * 攻顶引信：检测弹体正下方（世界系绝对 -Y 轴，不随弹体姿态变化）半锥角区域内的实体。
     * 命中后触发引信（复用近炸全额伤害与 {@code on_fuse} 子母弹链路）。
     */
    @SerializedName("top_attack_fuse_enabled")
    private boolean topAttackFuseEnabled = false;

    /** 攻顶引信从弹体向正下方的最大检测距离（米）。 */
    @SerializedName("top_attack_fuse_distance")
    private float topAttackFuseDistance = 6f;

    /** 攻顶引信检测半锥角（度）：实体与正下方方向的偏移角上限。 */
    @SerializedName("top_attack_fuse_fov")
    private float topAttackFuseFov = 25f;

    /** 攻顶引信探测到目标后延时起爆的 tick 数；0 = 立即触发。 */
    @SerializedName("top_attack_fuse_delay_tick")
    private int topAttackFuseDelayTick = 0;

    /** 攻顶引信解保 tick：出生后至少经过该 tick 才启用；0 = 不限制（默认）。 */
    @SerializedName("top_attack_fuse_arm_tick")
    private int topAttackFuseArmTick = 0;

    /**
     * 智能引信模式（默认开启）：攻顶探测命中后不立即引爆，而是记录检测点（目标 AABB 中心），
     * 解除当前制导并改飞向「检测点正上方 ±{@code top_attack_smart_target_radius} 圆内、
     * 高度为触发时刻导弹高度」的目标点，到达后再引爆，缓解 fov 圈过大导致的偏爆。
     */
    @SerializedName("top_attack_smart_enabled")
    private boolean topAttackSmartEnabled = true;

    /** 智能引信目标点水平随机半径（米）。 */
    @SerializedName("top_attack_smart_target_radius")
    private float topAttackSmartTargetRadius = 0.5f;

    /** 智能引信到达判定：水平距离 ≤ 该值（米）且垂直高度差 ≤ {@code arrive_vertical} 时引爆。 */
    @SerializedName("top_attack_smart_arrive_horizontal")
    private float topAttackSmartArriveHorizontal = 0.5f;

    /** 智能引信到达判定：垂直高度差 ≤ 该值（米）。 */
    @SerializedName("top_attack_smart_arrive_vertical")
    private float topAttackSmartArriveVertical = 1.0f;

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

    @SerializedName("entity_collision_safe_tick")
    private Integer entityCollisionSafeTick;

    public int getDelayTick() {
        return Math.max(delayTick, 0);
    }

    public boolean isProgrammableAirburst() {
        return programmableAirburst;
    }

    public boolean isAheadEnabled() {
        return aheadEnabled;
    }

    public float getAheadBurstOffsetMeters() {
        return Math.max(aheadBurstOffsetMeters, 0f);
    }

    public boolean isAheadRequireLock() {
        return aheadRequireLock;
    }

    public float getAheadMinGroundClearance() {
        return Math.max(aheadMinGroundClearance, 0f);
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

    public boolean isTopAttackFuseEnabled() {
        return topAttackFuseEnabled;
    }

    public float getTopAttackFuseDistance() {
        return Math.max(topAttackFuseDistance, 0f);
    }

    public float getTopAttackFuseFov() {
        return Math.max(topAttackFuseFov, 0f);
    }

    public int getTopAttackFuseDelayTick() {
        return Math.max(topAttackFuseDelayTick, 0);
    }

    public int getTopAttackFuseArmTick() {
        return Math.max(topAttackFuseArmTick, 0);
    }

    public boolean isTopAttackSmartEnabled() {
        return topAttackSmartEnabled;
    }

    public float getTopAttackSmartTargetRadius() {
        return Math.max(topAttackSmartTargetRadius, 0f);
    }

    public float getTopAttackSmartArriveHorizontal() {
        return Math.max(topAttackSmartArriveHorizontal, 0f);
    }

    public float getTopAttackSmartArriveVertical() {
        return Math.max(topAttackSmartArriveVertical, 0f);
    }

    public int getProximityFuseHeight() {
        return Math.max(proximityFuseHeight, 0);
    }

    public float getProximityRadius() {
        return Math.max(proximityRadius, 0f);
    }

    public float getGroundProximityFuseDistance() {
        return Float.isFinite(groundProximityFuseDistance)
                ? Math.max(groundProximityFuseDistance, 0f)
                : 0f;
    }

    public int getGroundProximityFuseArmTick() {
        return Math.max(groundProximityFuseArmTick, 0);
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

    public boolean hasEntityCollisionSafeTickOverride() {
        return entityCollisionSafeTick != null;
    }

    public int getEntityCollisionSafeTick() {
        return entityCollisionSafeTick == null ? 0 : Math.max(entityCollisionSafeTick, 0);
    }
}
