package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;

/**
 * One payload type inside a {@link RVP_SubmunitionReleaseData} wave.
 */
public class RVP_SubmunitionPayloadData {

    @SerializedName("kind")
    private String kind = "rvp_weapon";

    /** RVP weapon id ({@code rvp:child_id}); empty = clone parent weapon. */
    @SerializedName("weapon_id")
    private String weaponId = "";

    /** Entity type id, e.g. {@code minecraft:arrow}. */
    @SerializedName("entity_type")
    private String entityType = "";

    /** SNBT applied after spawn (optional). */
    @SerializedName("entity_nbt")
    private String entityNbt = "";

    /** Instances spawned per release event for this payload entry. */
    @SerializedName("count")
    private int count = 1;

    @SerializedName("spread")
    private RVP_SubmunitionSpreadData spread = new RVP_SubmunitionSpreadData();

    /** Add parent {@link org.ywzj.rvp.entity.projectile.RVP_BaseBullet#getDeltaMovement()}. */
    @SerializedName("inherit_parent_velocity")
    private boolean inheritParentVelocity = true;

    /** Add shooter vehicle motion (like {@link RVP_ProjectileData#isInheritVehicleVelocity()}). */
    @SerializedName("inherit_vehicle_velocity")
    private boolean inheritVehicleVelocity = false;

    /** Scales inherited / aimed velocity. */
    @SerializedName("velocity_scale")
    private float velocityScale = 1f;

    /** 发射方向 yaw（度），与弹体 yRot 同约定（0=南 +Z，顺时针为正，-90=东、90=西）；默认 0 = 沿母弹弹轴。 */
    @SerializedName("launch_yaw")
    private float launchYaw = 0f;

    /** 发射方向 pitch（度），与弹体 xRot 同约定（90=正下、-90=正上）；默认 0 = 沿母弹弹轴。 */
    @SerializedName("launch_pitch")
    private float launchPitch = 0f;

    /**
     * 发射角度基准：{@code relative} = 相对母弹当前姿态叠加（随弹体俯仰/偏航变化）；
     * {@code absolute} = 世界系固定角度。默认 {@code relative}。
     */
    @SerializedName("launch_angle_mode")
    private String launchAngleMode = "relative";

    /**
     * 发射初速；0 = 沿用 {@link #inheritParentVelocity} × {@link #velocityScale} 的长度，仅替换方向。
     * 与 {@link #launchYaw} / {@link #launchPitch} 三者至少一个非默认值时才启用发射角度逻辑。
     */
    @SerializedName("launch_speed")
    private float launchSpeed = 0f;

    /** Passed to {@link org.ywzj.rvp.weapon.core.RVP_ProjectileSpawner} for RVP weapons. */
    @SerializedName("power_scale")
    private float powerScale = 1f;

    /**
     * If true, the spawned projectile may execute <strong>its own weapon JSON</strong>
     * {@code submunition_data} (multi-stage / cluster stage-2). Default false so leaf bomblets
     * do not chain again.
     */
    @SerializedName("allow_submunition")
    private boolean allowSubmunition = false;

    /** Multiplies direct damage on spawned RVP projectiles (optional). */
    @SerializedName("damage_multiplier")
    private Float damageMultiplier;

    /** When true, spawned RVP projectile gets {@link RVP_Explosion#disabled()}. */
    @SerializedName("suppress_explosion")
    private boolean suppressExplosion = false;

    public RVP_EnumSubmunitionPayloadKind getKind() {
        return RVP_EnumSubmunitionPayloadKind.fromString(kind);
    }

    public ResourceLocation resolveWeaponId(ResourceLocation parentWeaponId) {
        if (weaponId == null || weaponId.isBlank()) {
            return parentWeaponId;
        }
        if (weaponId.contains(":")) {
            return ResourceLocation.tryParse(weaponId);
        }
        return ResourceLocation.fromNamespaceAndPath("rvp", weaponId);
    }

    public ResourceLocation resolveEntityType() {
        if (entityType == null || entityType.isBlank()) {
            return null;
        }
        return ResourceLocation.tryParse(entityType);
    }

    public int getCount() {
        return Math.max(count, 0);
    }

    public RVP_SubmunitionSpreadData getSpread() {
        return spread == null ? new RVP_SubmunitionSpreadData() : spread;
    }

    public boolean isInheritParentVelocity() {
        return inheritParentVelocity;
    }

    public boolean isInheritVehicleVelocity() {
        return inheritVehicleVelocity;
    }

    public float getVelocityScale() {
        return Math.max(velocityScale, 0.01f);
    }

    public float getLaunchYaw() {
        return launchYaw;
    }

    public float getLaunchPitch() {
        return launchPitch;
    }

    /** 是否启用发射角度逻辑：yaw / pitch / speed 至少一个非默认值。 */
    public boolean isLaunchAnglesEnabled() {
        return launchYaw != 0f || launchPitch != 0f || launchSpeed > 0f;
    }

    /** 发射角度是否为世界系绝对角度；false 时以母弹当前姿态为基准叠加。 */
    public boolean isLaunchAngleAbsolute() {
        return "absolute".equalsIgnoreCase(launchAngleMode);
    }

    public float getLaunchSpeed() {
        return Math.max(launchSpeed, 0f);
    }

    public float getPowerScale() {
        return Math.max(powerScale, 0.01f);
    }

    public boolean isAllowSubmunition() {
        return allowSubmunition;
    }

    public Float getDamageMultiplier() {
        return damageMultiplier;
    }

    public boolean isSuppressExplosion() {
        return suppressExplosion;
    }

    public String getEntityNbt() {
        return entityNbt == null ? "" : entityNbt;
    }
}
