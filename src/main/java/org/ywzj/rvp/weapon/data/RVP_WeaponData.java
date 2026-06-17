package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceConfigResolver;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;

import java.util.List;

/**
 * 七个公开 {@code rvp:*} 武器类型共用的数据模型（{@code data/rvp/weapons/<id>.json}）。
 *
 * <p>配置应使用 {@code *_data} 分组字段；语义访问统一通过本类 getter。
 * 各分组字段的 JavaDoc 以对应 {@code RVP_*Data} 类为准（样板见 {@link RVP_FireData}）。</p>
 */
public class RVP_WeaponData extends BaseVehicleWeaponData {

    /** 运行时内部武器类别，由 {@link org.ywzj.rvp.all.RVP_WeaponTypes} 根据 {@code type} 写入，JSON 勿配置。 */
    private transient RVP_EnumWeaponKind weaponKind = RVP_EnumWeaponKind.ROCKET;

    /** 子类型标签（如 incendiary），供逻辑分支或显示；非 MCH 迁移字段。 */
    @SerializedName("sub_type")
    private String subType = "";

    /** 开火模式、散布、点射/蓄力等，见 {@link RVP_FireData}。 */
    @SerializedName("fire_data")
    private RVP_FireData fireData = new RVP_FireData();

    /** 弹体初速、重力、阻力、火箭发动机等，见 {@link RVP_ProjectileData}。 */
    @SerializedName("projectile_data")
    private RVP_ProjectileData projectileData = new RVP_ProjectileData();

    /** 定时/近炸/空爆等引信，见 {@link RVP_FuseData}。 */
    @SerializedName("fuse_data")
    private RVP_FuseData fuseData = new RVP_FuseData();

    /** 直击伤害、伤害衰减、穿透与跳弹，见 {@link RVP_CollisionData}。 */
    @SerializedName("collision_data")
    private RVP_CollisionData collisionData = new RVP_CollisionData();

    /** 轨迹/命中/爆炸粒子与机枪曳光，见 {@link RVP_EffectsData}。 */
    @SerializedName("effects_data")
    private RVP_EffectsData effectsData = new RVP_EffectsData();

    /** 落点爆炸与火焰/药水等自定义效果，见 {@link RVP_DetonateData}。 */
    @SerializedName("detonate_data")
    private RVP_DetonateData detonateData = new RVP_DetonateData();

    /** 子母弹 / 空中布撒（触发器、载荷、散布），见 {@link RVP_SubmunitionData}。 */
    @SerializedName("submunition_data")
    private RVP_SubmunitionData submunitionData = new RVP_SubmunitionData();

    /** 落点布撒物品与散布（任意武器类型可用），见 {@link RVP_DispenserPayloadData}。 */
    @SerializedName("dispenser_data")
    private RVP_DispenserPayloadData dispenserData = new RVP_DispenserPayloadData();

    /** 分段制导阶段与源（含 ARM/TV 专用参数），见 {@link RVP_GuidanceData}。 */
    @SerializedName("guidance_data")
    private RVP_GuidanceData guidanceData = new RVP_GuidanceData();

    /** {@code rvp:laser} 射程与光束外观，见 {@link RVP_LaserData}。 */
    @SerializedName("laser_data")
    private RVP_LaserData laserData = new RVP_LaserData();

    /**
     * 发射前是否要求火控锁定目标（导弹等）；为 true 且无锁时客户端提示
     * {@code ui.need_lock_entity}。
     */
    @SerializedName("require_lock")
    private boolean requireLock = true;

    public RVP_EnumWeaponKind getWeaponKind() {
        return weaponKind;
    }

    public void setWeaponKind(RVP_EnumWeaponKind weaponKind) {
        this.weaponKind = weaponKind == null ? RVP_EnumWeaponKind.ROCKET : weaponKind;
    }

    public String getSubType() {
        return subType == null ? "" : subType;
    }

    public RVP_FireData getFireData() {
        return fireData == null ? new RVP_FireData() : fireData;
    }

    public RVP_ProjectileData getProjectileData() {
        return projectileData == null ? new RVP_ProjectileData() : projectileData;
    }

    public RVP_FuseData getFuseData() {
        return fuseData == null ? new RVP_FuseData() : fuseData;
    }

    public RVP_CollisionData getCollisionData() {
        return collisionData == null ? new RVP_CollisionData() : collisionData;
    }

    public RVP_EffectsData getEffectsData() {
        return effectsData == null ? new RVP_EffectsData() : effectsData;
    }

    public RVP_DetonateData getDetonateData() {
        return detonateData == null ? new RVP_DetonateData() : detonateData;
    }

    public RVP_SubmunitionData getSubmunitionData() {
        return submunitionData == null ? new RVP_SubmunitionData() : submunitionData;
    }

    public RVP_DispenserPayloadData getDispenserData() {
        return dispenserData == null ? new RVP_DispenserPayloadData() : dispenserData;
    }

    public RVP_GuidanceData getGuidanceData() {
        return guidanceData == null ? new RVP_GuidanceData() : guidanceData;
    }

    /** 发射前 UI 用；飞行中优先当前激活阶段。 */
    public RVP_GuidanceSteeringData getGuidanceSteeringData() {
        return getGuidanceSteeringData(null);
    }

    public RVP_GuidanceSteeringData getGuidanceSteeringData(@Nullable RVP_BaseBullet projectile) {
        return RVP_GuidanceConfigResolver.resolveSteering(this, projectile);
    }

    public RVP_LaserData getLaserData() {
        return laserData == null ? new RVP_LaserData() : laserData;
    }

    public RVP_Explosion getExplosionData() {
        return getDetonateData().getExplosionData();
    }

    public float resolveAirburstExplosionDamage() {
        if (getFuseData().hasAirburstExplosionDamageOverride()) {
            return getFuseData().getAirburstExplosionDamage();
        }
        RVP_Explosion ex = getExplosionData();
        return ex != null ? ex.damage : 0f;
    }

    public float resolveAirburstExplosionRadius() {
        if (getFuseData().hasAirburstExplosionRadiusOverride()) {
            return getFuseData().getAirburstExplosionRadius();
        }
        RVP_Explosion ex = getExplosionData();
        return ex != null ? ex.radius : 0f;
    }

    public float resolveProximityFuseExplosionDamage() {
        if (getFuseData().hasProximityFuseExplosionDamageOverride()) {
            return getFuseData().getProximityFuseExplosionDamage();
        }
        RVP_Explosion ex = getExplosionData();
        return ex != null ? ex.damage : 0f;
    }

    public float resolveProximityFuseExplosionRadius() {
        if (getFuseData().hasProximityFuseExplosionRadiusOverride()) {
            return getFuseData().getProximityFuseExplosionRadius();
        }
        RVP_Explosion ex = getExplosionData();
        return ex != null ? ex.radius : 0f;
    }

    public float getProximityFuseDirectDamage() {
        return getFuseData().getProximityFuseDamage();
    }

    public boolean isRequireLock() {
        return requireLock;
    }

    public float getProjectileVelocity() {
        if (getProjectileData().hasVelocityOverride()) {
            return getProjectileData().getVelocityOverride();
        }
        return getVelocity();
    }

    /**
     * Blocks-per-tick muzzle speed, aligned with {@link org.ywzj.vehicle.vehicle.weapon.VehicleCannon}
     * ({@code shootFromRotation(..., data.getVelocity(), ...)}) and {@link org.ywzj.vehicle.vehicle.weapon.VehicleRocket}.
     */
    public float resolveMuzzleSpeed(RVP_EnumWeaponKind kind) {
        float fromProjectile = getProjectileData().hasVelocityOverride()
                ? getProjectileData().getVelocityOverride()
                : 0f;
        float top = getVelocity();
        if (kind == RVP_EnumWeaponKind.MACHINEGUN) {
            if (fromProjectile > 0f) {
                return fromProjectile;
            }
            if (top > 0f) {
                return top;
            }
            return 16f;
        }
        if (fromProjectile > 0f) {
            return fromProjectile;
        }
        return Math.max(top, 0.01f);
    }

    /** @deprecated use {@link #resolveMuzzleSpeed(RVP_EnumWeaponKind)} */
    @Deprecated
    public float getCannonMuzzleVelocity() {
        return resolveMuzzleSpeed(RVP_EnumWeaponKind.MACHINEGUN);
    }

    public float getCannonGravity() {
        float g = getProjectileData().getGravity();
        return g == 0f ? 0f : Math.abs(g);
    }

    public float getCannonFriction() {
        float drag = Math.abs(getProjectileData().getDrag());
        if (drag <= 0f) {
            return 0.01f;
        }
        return Math.min(Math.max(drag, 0.001f), 0.4f);
    }

    public int getLivingPenetration() {
        return getCollisionData().getLivingPenetration();
    }

    public int getWallPenetration() {
        return getCollisionData().getWallPenetration();
    }

    public float getPenetrationDamageMultiplier() {
        return getCollisionData().getPenetrationDamageMultiplier();
    }

    public float getPenetrationSpeedMultiplier() {
        return getCollisionData().getPenetrationSpeedMultiplier();
    }

    public int getBounce() {
        return getCollisionData().getBounce();
    }

    public float getBounceStrength() {
        return getCollisionData().getBounceStrength();
    }

    public int getBounceFuseTick() {
        return getCollisionData().getBounceFuseTick();
    }

    public float getBounceIncidenceAngle() {
        return getCollisionData().getBounceIncidenceAngle();
    }

    public boolean isBounceOnVehicle() {
        return getCollisionData().isBounceOnVehicle();
    }

    public float getBounceMinBlockHardness() {
        return getCollisionData().getBounceMinBlockHardness();
    }

    public List<RVP_DamageDecayRuleData> getDamageDecayRules() {
        return getCollisionData().getDamageDecayRules();
    }

    public int getRigidityTime() {
        return getGuidanceSteeringData().getRigidityTime();
    }

    public double getTurningFactor() {
        return getGuidanceSteeringData().getTurningFactor();
    }

    public float getGravity() {
        return getProjectileData().getGravity();
    }

    public float getGravityInWater() {
        return getProjectileData().getGravityInWater();
    }

    public float getDragInAir() {
        return getProjectileData().getDrag();
    }

    public float getDragInWater() {
        return getProjectileData().getDragInWater();
    }

    public boolean isActiveRadar() {
        return usesGuidanceType(RVP_EnumGuidanceType.ARH);
    }

    public boolean isSemiActiveRadar() {
        return usesGuidanceType(RVP_EnumGuidanceType.SARH);
    }

    public boolean isRadarHoming() {
        return isActiveRadar() || isSemiActiveRadar();
    }

    /** 发射前锁定 UI 用；飞行中优先当前激活阶段的导引头。 */
    public RVP_GuidanceSeekerData resolveLaunchSeeker() {
        return resolveLaunchSeeker(null);
    }

    public RVP_GuidanceSeekerData resolveLaunchSeeker(@Nullable RVP_BaseBullet projectile) {
        return RVP_GuidanceConfigResolver.resolveSeeker(this, projectile);
    }

    public int getScanInterval() {
        return resolveLaunchSeeker().getScanIntervalTick();
    }

    public float getMaxLockOnRange() {
        return resolveLaunchSeeker().getRange();
    }

    public float getMaxLockOnAngle() {
        return resolveLaunchSeeker().getFov();
    }

    public float getMaxDegreeOfMissile() {
        return getGuidanceSteeringData().getMaxDegreeOfMissile();
    }

    public float getLockMinHeight() {
        return resolveLaunchSeeker().getLockMinHeight();
    }

    public float getMaxGuideHeadAngle() {
        return resolveLaunchSeeker().getGuideHeadMaxAngle();
    }

    /** 瞄准吊舱射线长度；优先 {@link RVP_LaserData}，默认 8192。 */
    public float getTargetingPodRange() {
        if (getLaserData().getRange() > 1f) {
            return getLaserData().getRange();
        }
        return 8192f;
    }

    public boolean isPredictTargetPos() {
        return getGuidanceSteeringData().isPredictTargetPos();
    }

    public int getTickEndHoming() {
        return getGuidanceSteeringData().getTickEndHoming();
    }

    public boolean isGpsMissile() {
        return usesGuidanceType(RVP_EnumGuidanceType.GPS);
    }

    public int getDelayFuse() {
        return getFuseData().getDelayTick();
    }

    public float getProximityFuseDist() {
        if (getFuseData().getProximityRadius() > 0f) {
            return getFuseData().getProximityRadius();
        }
        RVP_Explosion explosion = getExplosionData();
        if (explosion != null && explosion.proximityFuze && explosion.proximityRadius > 0f) {
            return explosion.proximityRadius;
        }
        return 0f;
    }

    /** 是否使用本体 {@link org.ywzj.vehicle.entity.weapon.MissileEntity} 推力/阻力模型。 */
    public boolean usesPropulsion() {
        return getProjectileData().usesPropulsion();
    }

    public float getResolvedMass() {
        return getProjectileData().getResolvedMass();
    }

    public float getResolvedThrust() {
        return getProjectileData().getResolvedThrust();
    }

    public float getResolvedMotorBurnTime() {
        return getProjectileData().getResolvedMotorBurnTime();
    }

    public int getResolvedIgnitionDelayTick() {
        return getProjectileData().getResolvedIgnitionDelayTick();
    }

    public float getResolvedDragCoefficient() {
        return getProjectileData().getResolvedDragCoefficient();
    }

    public boolean isInheritVehicleVelocity() {
        return getProjectileData().isInheritVehicleVelocity();
    }

    public boolean isAntiRadiationMissile() {
        return usesGuidanceType(RVP_EnumGuidanceType.ARM);
    }

    public float getLaserRange() {
        return getLaserData().getRange();
    }

    public RVP_LaserVisualData getLaserVisual() {
        return getLaserData().getVisualData();
    }

    @Override
    public float getDamage() {
        return getDirectDamage();
    }

    public float getDirectDamage() {
        if (getCollisionData().hasDirectDamageOverride()) {
            return getCollisionData().getDirectDamageOverride();
        }
        return super.getDamage();
    }

    /** 直击伤害的目标类别倍率。 */
    public RVP_DamageFactor getDirectDamageFactor() {
        return getCollisionData().getDirectDamageFactor();
    }

    /** @deprecated use {@link #getDirectDamageFactor()} */
    @Deprecated
    public RVP_DamageFactor getDamageFactor() {
        return getDirectDamageFactor();
    }

    /**
     * 发射角度散布（度）。{@link RVP_FireData#getSpreadOverride()} 优先于顶层 {@code inaccuracy}，
     * 与 {@link #getDirectDamage()} / {@code collision_data.direct_damage} 相对顶层 {@code damage} 的规则一致。
     */
    @Override
    public float getInaccuracy() {
        if (getFireData().hasSpreadOverride()) {
            return Math.max(getFireData().getSpreadOverride(), 0f);
        }
        return super.getInaccuracy();
    }

    public boolean usesGuidanceType(RVP_EnumGuidanceType type) {
        if (type == null) {
            return false;
        }
        return getGuidanceData().hasSourceType(type);
    }

    public boolean hasHumanInTheLoop() {
        return getGuidanceData().isHumanInTheLoopEnabled();
    }
}
