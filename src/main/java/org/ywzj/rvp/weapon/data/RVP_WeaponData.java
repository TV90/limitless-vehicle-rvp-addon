package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.vehicle.pojo.Explosion;

/**
 * 七个公开 {@code rvp:*} 武器类型共用的数据模型。
 *
 * <p>配置应使用 {@code *_data} 分组字段；语义访问统一通过本类 getter。</p>
 */
public class RVP_WeaponData extends BaseVehicleWeaponData {

    /** 运行时内部武器类别，由注册器根据 rvp:* 类型写入，JSON 不需要配置。 */
    private transient RVP_EnumWeaponKind weaponKind = RVP_EnumWeaponKind.ROCKET;

    @SerializedName("sub_type")
    private String subType = "";

    @SerializedName("fire_data")
    private RVP_FireData fireData = new RVP_FireData();

    @SerializedName("projectile_data")
    private RVP_ProjectileData projectileData = new RVP_ProjectileData();

    @SerializedName("fuse_data")
    private RVP_FuseData fuseData = new RVP_FuseData();

    @SerializedName("damage_model_data")
    private RVP_DamageData damageModelData = new RVP_DamageData();

    @SerializedName("collision_data")
    private RVP_CollisionData collisionData = new RVP_CollisionData();

    @SerializedName("effects_data")
    private RVP_EffectsData effectsData = new RVP_EffectsData();

    @SerializedName("detonate_data")
    private RVP_DetonateData detonateData = new RVP_DetonateData();

    @SerializedName("submunition_data")
    private RVP_SubmunitionData submunitionData = new RVP_SubmunitionData();

    @SerializedName("dispenser_data")
    private RVP_DispenserPayloadData dispenserData = new RVP_DispenserPayloadData();

    @SerializedName("seeker_data")
    private RVP_SeekerData seekerData = new RVP_SeekerData();

    @SerializedName("guidance_data")
    private RVP_GuidanceData guidanceData = new RVP_GuidanceData();

    @SerializedName("arm_data")
    private RVP_ArmData armData = new RVP_ArmData();

    @SerializedName("tv_missile_data")
    private RVP_TvMissileData tvMissileData = new RVP_TvMissileData();

    @SerializedName("laser_data")
    private RVP_LaserData laserData = new RVP_LaserData();

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

    public RVP_DamageData getDamageModelData() {
        return damageModelData == null ? new RVP_DamageData() : damageModelData;
    }

    public RVP_CollisionData getCollisionData() {
        return collisionData == null ? new RVP_CollisionData() : collisionData;
    }

    public RVP_EffectsData getEffectsData() {
        return effectsData == null ? new RVP_EffectsData() : effectsData;
    }

    public RVP_DetonateData getDetonateData() {
        RVP_DetonateData data = detonateData == null ? new RVP_DetonateData() : detonateData;
        if (!data.hasAnyEffect() && "incendiary".equalsIgnoreCase(getSubType())) {
            return RVP_DetonateData.legacyIncendiaryFallback();
        }
        return data;
    }

    public RVP_SubmunitionData getSubmunitionData() {
        return submunitionData == null ? new RVP_SubmunitionData() : submunitionData;
    }

    public RVP_DispenserPayloadData getDispenserData() {
        return dispenserData == null ? new RVP_DispenserPayloadData() : dispenserData;
    }

    public RVP_SeekerData getSeekerData() {
        return seekerData == null ? new RVP_SeekerData() : seekerData;
    }

    public RVP_GuidanceData getGuidanceData() {
        return guidanceData == null ? new RVP_GuidanceData() : guidanceData;
    }

    public RVP_GuidanceSteeringData getGuidanceSteeringData() {
        return getGuidanceData().getSteeringData();
    }

    public RVP_ArmData getArmData() {
        return armData == null ? new RVP_ArmData() : armData;
    }

    public RVP_TvMissileData getTvMissileData() {
        return tvMissileData == null ? new RVP_TvMissileData() : tvMissileData;
    }

    public RVP_LaserData getLaserData() {
        return laserData == null ? new RVP_LaserData() : laserData;
    }

    public Explosion getExplosionData() {
        return getDetonateData().getExplosionData();
    }

    public float resolveAirburstExplosionDamage() {
        Float override = getDamageModelData().getAirburstExplosionDamage();
        if (override != null) {
            return override;
        }
        Explosion ex = getExplosionData();
        return ex != null ? ex.damage : 0f;
    }

    public float resolveAirburstExplosionRadius() {
        Float override = getDamageModelData().getAirburstExplosionRadius();
        if (override != null) {
            return override;
        }
        Explosion ex = getExplosionData();
        return ex != null ? ex.radius : 0f;
    }

    public float resolveProximityFuseExplosionDamage() {
        Float override = getDamageModelData().getProximityFuseExplosionDamage();
        if (override != null) {
            return override;
        }
        Explosion ex = getExplosionData();
        return ex != null ? ex.damage : 0f;
    }

    public float resolveProximityFuseExplosionRadius() {
        Float override = getDamageModelData().getProximityFuseExplosionRadius();
        if (override != null) {
            return override;
        }
        Explosion ex = getExplosionData();
        return ex != null ? ex.radius : 0f;
    }

    public boolean isRequireLock() {
        return requireLock;
    }

    public float getProjectileVelocity() {
        Float override = getProjectileData().getVelocityOverride();
        return override != null ? override : getVelocity();
    }

    public float getCannonMuzzleVelocity() {
        float top = getVelocity();
        Float proj = getProjectileData().getVelocityOverride();
        if (top > 0f && top != 10f) {
            return top;
        }
        if (proj != null && proj > 0f) {
            return proj;
        }
        return 16f;
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

    public int getPiercing() {
        return getCollisionData().getPiercing();
    }

    public int getWallPenetration() {
        return getCollisionData().getWallPenetration();
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

    public int getScanInterval() {
        return getSeekerData().getScanIntervalTick();
    }

    public float getMaxLockOnRange() {
        return getSeekerData().getRange();
    }

    public float getMaxLockOnAngle() {
        return getSeekerData().getFov();
    }

    public float getMaxDegreeOfMissile() {
        return getGuidanceSteeringData().getMaxDegreeOfMissile();
    }

    public float getLockMinHeight() {
        return getSeekerData().getLockMinHeight();
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
        Explosion explosion = getExplosionData();
        if (explosion != null && explosion.proximityFuze && explosion.proximityRadius > 0f) {
            return explosion.proximityRadius;
        }
        return 0f;
    }

    public int getBomblet() {
        return getSubmunitionData().getCount();
    }

    public int getBombletSTime() {
        if (getSubmunitionData().getIntervalTick() > 0) {
            return getSubmunitionData().getIntervalTick();
        }
        return getSubmunitionData().getDelayTick();
    }

    public float getBombletDiff() {
        return getSubmunitionData().getSpread();
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

    public int getAntiRadiationScanIntervalTick() {
        return getArmData().getScanIntervalTick();
    }

    public int getAntiRadiationMemoryTick() {
        return getArmData().getMemoryTick();
    }

    public int getAntiRadiationRadiationPulseMemoryTick() {
        return getArmData().getRadiationPulseMemoryTick();
    }

    public boolean isAntiRadiationAllowReacquire() {
        return getArmData().isAllowReacquire();
    }

    public float getAntiRadiationLockedBonus() {
        return getArmData().getLockedBonus();
    }

    public float getLaserRange() {
        return getLaserData().getRange();
    }

    public RVP_LaserVisualData getLaserVisual() {
        return getLaserData().getVisualData();
    }

    public float getTVMissileControlRange() {
        return getTvMissileData().getControlRange();
    }

    public int getTVMissileTimeoutTick() {
        return getTvMissileData().getTimeoutTick();
    }

    public int getTVMissileVideoModeMask() {
        return getTvMissileData().getVideoModeMask();
    }

    public int getDefaultTVMissileVideoMode() {
        return getTvMissileData().getDefaultVideoMode();
    }

    @Override
    public float getDamage() {
        return getDirectDamage();
    }

    public float getDirectDamage() {
        Float direct = getDamageModelData().getDirectOverride();
        if (direct != null) {
            return direct;
        }
        return super.getDamage();
    }

    /** 直击、爆炸、近炸直伤共用的目标类别伤害倍率。 */
    public RVP_DamageFactor getDamageFactor() {
        return getDamageModelData().getDamageFactor();
    }

    /**
     * 发射角度散布（度）。{@link RVP_FireData#getSpreadOverride()} 优先于顶层 {@code inaccuracy}，
     * 与 {@link #getDirectDamage()} / {@code damage_model_data.direct} 相对 {@code damage} 的规则一致。
     */
    @Override
    public float getInaccuracy() {
        Float spread = getFireData().getSpreadOverride();
        if (spread != null) {
            return Math.max(spread, 0f);
        }
        return super.getInaccuracy();
    }

    public boolean usesGuidanceType(RVP_EnumGuidanceType type) {
        if (type == null) {
            return false;
        }
        return getGuidanceData().getStages().stream()
                .flatMap(stage -> stage.getSources().stream())
                .anyMatch(source -> source.getType() == type);
    }
}
