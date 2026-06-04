package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 伤害模型参数：直接命中伤害、距离衰减，以及引信专用爆炸/伤害覆盖。
 */
public class RVP_DamageData {

    /** 直接命中伤害覆盖值；为空时使用武器顶层 damage。 */
    @SerializedName("direct")
    private Float direct;

    /** MCH {@code BulletDecay} 规则列表；各规则伤害系数相乘。 */
    @SerializedName("decay")
    private List<RVP_DamageDecayRuleData> decay = new ArrayList<>();

    /**
     * 可编程空爆触发的爆炸伤害；未写时使用 {@code detonate_data.explosion_data.damage}。
     */
    @SerializedName("airburst_explosion_damage")
    private Float airburstExplosionDamage;

    /**
     * 可编程空爆触发的爆炸半径；未写时使用 {@code detonate_data.explosion_data.radius}。
     */
    @SerializedName("airburst_explosion_radius")
    private Float airburstExplosionRadius;

    /**
     * 近炸引信触发的爆炸伤害；未写时使用 {@code detonate_data.explosion_data.damage}。
     */
    @SerializedName("proximity_fuse_explosion_damage")
    private Float proximityFuseExplosionDamage;

    /**
     * 近炸引信触发的爆炸半径；未写时使用 {@code detonate_data.explosion_data.radius}。
     */
    @SerializedName("proximity_fuse_explosion_radius")
    private Float proximityFuseExplosionRadius;

    /**
     * 近炸引信对命中实体的直接伤害（MCH {@code ProximityFuseDamage}）；未写时为 0（仅爆炸）。
     */
    @SerializedName("proximity_fuse_damage")
    private Float proximityFuseDamage;

    /**
     * 直击与爆炸波及伤害的目标类别倍率（玩家 / 生物 / 载具实体类型）。
     */
    @SerializedName("damage_factor")
    private RVP_DamageFactor damageFactor;

    public Float getDirectOverride() {
        return direct;
    }

    /** True when JSON defined {@code damage_model_data} with direct or decay. */
    public boolean isSpecified() {
        if (direct != null) {
            return true;
        }
        return hasDecay();
    }

    public List<RVP_DamageDecayRuleData> getDecayRules() {
        if (decay == null || decay.isEmpty()) {
            return Collections.emptyList();
        }
        return decay;
    }

    public boolean hasDecay() {
        return !getDecayRules().isEmpty();
    }

    public Float getAirburstExplosionDamage() {
        return airburstExplosionDamage;
    }

    public Float getAirburstExplosionRadius() {
        return airburstExplosionRadius;
    }

    public Float getProximityFuseExplosionDamage() {
        return proximityFuseExplosionDamage;
    }

    public Float getProximityFuseExplosionRadius() {
        return proximityFuseExplosionRadius;
    }

    public float getProximityFuseDamage() {
        return proximityFuseDamage == null ? 0f : Math.max(proximityFuseDamage, 0f);
    }

    public RVP_DamageFactor getDamageFactor() {
        return damageFactor == null ? RVP_DamageFactor.DEFAULT : damageFactor;
    }

    public boolean hasDamageFactor() {
        return damageFactor != null && damageFactor.isConfigured();
    }
}
