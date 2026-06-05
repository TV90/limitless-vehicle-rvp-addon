package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.util.Mth;

/**
 * 弹体碰撞行为：实体穿透、穿墙、跳弹与弹跳引信。
 */
public class RVP_CollisionData {

    /** 实体穿透次数，0 表示命中后立即处理。 */
    @SerializedName("piercing")
    private int piercing = 0;

    /** 方块/墙体穿透次数，0 表示不穿墙。 */
    @SerializedName("wall_penetration")
    private int wallPenetration = 0;

    /** 弹跳次数，0 表示不跳弹。 */
    @SerializedName("bounce")
    private int bounce = 0;

    /**
     * 每次弹跳后速度保留比例（相对反射速度），如 0.8 表示每跳一次速度 ×0.8。
     * 未写时默认 0.6（与旧版硬编码一致）。
     */
    @SerializedName("bounce_strength")
    private float bounceStrength = 0f;

    /**
     * 第一次弹跳后经过多少 tick 自动引信（引爆或消失，取决于 detonate_data.explosion_data.explode）。
     * 0 表示不启用弹跳引信。
     */
    @SerializedName("bounce_fuse_tick")
    private int bounceFuseTick = 0;

    /**
     * 入射角阈值（度）：速度方向与撞击面法线夹角 ≥ 该值时才跳弹（掠射跳弹、近垂直不跳）。
     * 0 表示不限制角度（仅受 {@link #bounce} 次数约束）。
     */
    @SerializedName("bounce_incidence_angle")
    private float bounceIncidenceAngle = 0f;

    /**
     * 击中 {@link org.ywzj.vehicle.entity.vehicle.AbstractVehicle} 时是否允许跳弹；默认 false（仅方块等环境跳弹）。
     */
    @SerializedName("bounce_on_vehicle")
    private boolean bounceOnVehicle = false;

    public boolean isSpecified() {
        return piercing > 0 || wallPenetration > 0 || bounce > 0 || bounceFuseTick > 0 || bounceStrength > 0f
                || bounceIncidenceAngle > 0f || bounceOnVehicle;
    }

    public int getPiercing() {
        return Math.max(piercing, 0);
    }

    public int getWallPenetration() {
        return Math.max(wallPenetration, 0);
    }

    public int getBounce() {
        return Math.max(bounce, 0);
    }

    public float getBounceStrength() {
        if (bounceStrength > 0f) {
            return Mth.clamp(bounceStrength, 0.05f, 1f);
        }
        return getBounce() > 0 ? 0.6f : 0.6f;
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
}
