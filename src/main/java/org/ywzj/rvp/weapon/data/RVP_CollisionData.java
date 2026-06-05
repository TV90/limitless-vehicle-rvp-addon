package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 直击伤害、伤害衰减、穿透与跳弹。JSON 键 {@code collision_data}。
 */
public class RVP_CollisionData {

    /** 直接命中伤害；未写时使用武器顶层 {@code damage}。 */
    @SerializedName("direct_damage")
    private Float directDamage;

    /** 直击伤害的目标类别倍率（玩家 / 生物 / 载具类型）。 */
    @SerializedName("direct_damage_factor")
    private RVP_DamageFactor directDamageFactor;

    /**
     * 伤害衰减规则（MCH {@code BulletDecay} 扩展）。{@code domain} 默认 {@code distance}；
     * {@code angle} 为入射角分段（度）。距离类相乘，入射角类互斥，再相乘。
     */
    @SerializedName("damage_decay")
    private List<RVP_DamageDecayRuleData> damageDecay = new ArrayList<>();

    @SerializedName("living_penetration")
    private int livingPenetration = 0;

    @SerializedName("wall_penetration")
    private int wallPenetration = 0;

    @SerializedName("penetration_damage_multiplier")
    private float penetrationDamageMultiplier = 1f;

    @SerializedName("penetration_speed_multiplier")
    private float penetrationSpeedMultiplier = 1f;

    @SerializedName("bounce")
    private int bounce = 0;

    /** 未写且 {@link #bounce} &gt; 0 时默认 0.6。 */
    @SerializedName("bounce_strength")
    private Float bounceStrength;

    @SerializedName("bounce_fuse_tick")
    private int bounceFuseTick = 0;

    @SerializedName("bounce_incidence_angle")
    private float bounceIncidenceAngle = 0f;

    @SerializedName("bounce_on_vehicle")
    private boolean bounceOnVehicle = false;

    /**
     * 方块跳弹：仅当 {@link net.minecraft.world.level.block.state.BlockState#getDestroySpeed} &gt; 该值时允许跳弹（默认 {@value #DEFAULT_BOUNCE_MIN_BLOCK_HARDNESS}）。
     */
    @SerializedName("bounce_min_block_hardness")
    private float bounceMinBlockHardness = DEFAULT_BOUNCE_MIN_BLOCK_HARDNESS;

    public static final float DEFAULT_BOUNCE_MIN_BLOCK_HARDNESS = 2.1f;

    public Float getDirectDamageOverride() {
        return directDamage;
    }

    public boolean hasDirectDamageOverride() {
        return directDamage != null;
    }

    public RVP_DamageFactor getDirectDamageFactor() {
        return directDamageFactor == null ? RVP_DamageFactor.DEFAULT : directDamageFactor;
    }

    public boolean hasDirectDamageFactor() {
        return directDamageFactor != null && directDamageFactor.isConfigured();
    }

    public List<RVP_DamageDecayRuleData> getDamageDecayRules() {
        if (damageDecay == null || damageDecay.isEmpty()) {
            return Collections.emptyList();
        }
        return damageDecay;
    }

    public boolean hasDamageDecay() {
        return !getDamageDecayRules().isEmpty();
    }

    public boolean isSpecified() {
        return directDamage != null || hasDirectDamageFactor() || hasDamageDecay()
                || livingPenetration > 0 || wallPenetration > 0 || bounce > 0 || bounceFuseTick > 0
                || bounceStrength != null || bounceIncidenceAngle > 0f || bounceOnVehicle
                || penetrationDamageMultiplier > 0f && penetrationDamageMultiplier < 0.999f
                || penetrationSpeedMultiplier > 0f && penetrationSpeedMultiplier < 0.999f;
    }

    public int getLivingPenetration() {
        return Math.max(livingPenetration, 0);
    }

    public int getWallPenetration() {
        return Math.max(wallPenetration, 0);
    }

    public float getPenetrationDamageMultiplier() {
        return Mth.clamp(penetrationDamageMultiplier, 0.01f, 1f);
    }

    public float getPenetrationSpeedMultiplier() {
        return Mth.clamp(penetrationSpeedMultiplier, 0.01f, 1f);
    }

    public int getBounce() {
        return Math.max(bounce, 0);
    }

    public boolean hasBounceStrengthOverride() {
        return bounceStrength != null;
    }

    public float getBounceStrength() {
        if (bounceStrength != null) {
            return Mth.clamp(bounceStrength, 0.05f, 1f);
        }
        return 0.6f;
    }

    public int getBounceFuseTick() {
        return Math.max(bounceFuseTick, 0);
    }

    public float getBounceIncidenceAngle() {
        return Mth.clamp(bounceIncidenceAngle, 0f, 90f);
    }

    public boolean isBounceOnVehicle() {
        return bounceOnVehicle;
    }

    /** 方块硬度下限（{@code getDestroySpeed}）；仅严格大于该值的方块可触发跳弹。 */
    public float getBounceMinBlockHardness() {
        return Math.max(bounceMinBlockHardness, 0f);
    }
}
