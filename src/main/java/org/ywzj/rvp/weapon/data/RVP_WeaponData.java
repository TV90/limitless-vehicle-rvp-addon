package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import com.google.gson.annotations.JsonAdapter;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceLaunchConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceModelResolver;
import org.ywzj.rvp.guidance.RVP_GuidancePhase;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;

import java.util.List;

/**
 * 七个公开 {@code rvp:*} 武器类型共用的数据模型（{@code data/rvp/weapons/<id>.json}）。
 *
 * <p>配置应使用 {@code *_data} 分组字段；语义访问统一通过本类 getter。
 * 各分组字段的 JavaDoc 以对应 {@code RVP_*Data} 类为准（样板见 {@link RVP_FireData}）。</p>
 */
public class RVP_WeaponData extends BaseVehicleWeaponData {

    /** 是否在 HUD 中显示 MSL 导弹指示器（菱形框+距离）。 */
    @SerializedName("show_msl_indicator")
    private boolean showMslIndicator = false;

    @SerializedName("tactical_map_icon")
    private String tacticalMapIcon = "";
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
    @JsonAdapter(RVP_GuidanceDataAdapter.class)
    private RVP_GuidanceData guidanceData = new RVP_GuidanceData();

    /** 服务器固定 GPS 目标虚拟中段参数，默认关闭，见 {@link RVP_VirtualMidcourseData}。 */
    @SerializedName("virtual_midcourse_data")
    private RVP_VirtualMidcourseData virtualMidcourseData = new RVP_VirtualMidcourseData();

    @SerializedName("misc_data")
    private RVP_MiscData miscData = new RVP_MiscData();

    /** {@code rvp:laser} 射程与光束外观，见 {@link RVP_LaserData}。 */
    @SerializedName("laser_data")
    private RVP_LaserData laserData = new RVP_LaserData();

    /** 目标指示吊舱参数，见 {@link RVP_TargetingPodData}。 */
    @SerializedName("targeting_pod_data")
    private RVP_TargetingPodData targetingPodData = new RVP_TargetingPodData();

    /**
     * 发射前是否要求火控锁定目标（导弹等）；为 true 且无锁时客户端提示
     * {@code ui.need_lock_entity}。
     */
    @SerializedName("rvp_fire_control_sensor_mode")
    private String fireControlSensorMode = "";

    /**
     * Optional per-weapon override for the firing unit sensor type.
     * Lets one shared weapon station behave as RF/EO/IR depending on the currently selected weapon.
     */
    @SerializedName("fire_control_sensor_type_override")
    private WeaponUnitData.FireControlSensorType fireControlSensorTypeOverride;

    /**
     * 导引头圈 HUD 颜色覆盖（RGB 十六进制字符串，如 {@code "0x30FF30"} / {@code "#FFAA00"}）。
     * 配置后该武器处于选中状态时，{@code VehicleAimAtOverlay} 的导引头圈（含大圈）
     * 用此颜色绘制——未锁定显示该颜色，锁定时统一红色指示；未配置走本体机型基色
     * （直升机绿 / 固定翼白，绿色无红通道会导致锁定后变黑）。仅客户端渲染消费。
     */
    @SerializedName("seeker_color")
    private String seekerColor;

    /** {@link #seekerColor} 的解析缓存：null=未解析；负值=解析失败按未配置处理。 */
    private transient Integer seekerColorRgbCache;

    /**
     * 覆盖所属武器站的 {@code parent_weapon_unit_aim}：true=弹着点预测与准心锚定到母武器站，
     * false=使用自身挂架位置；null（未配置）=继承站级静态配置。
     * 消费点仅客户端（{@code WeaponUnit.currentWeaponHitPosition} 与 {@code VehicleAimAtOverlay}）。
     */
    @SerializedName("parent_weapon_unit_aim_override")
    private Boolean parentWeaponUnitAimOverride;

    public RVP_EnumWeaponKind getWeaponKind() {
        return weaponKind;
    }

    public boolean isShowMslIndicator() {
        return showMslIndicator;
    }

    public String getTacticalMapIcon() {
        return tacticalMapIcon == null ? "" : tacticalMapIcon;
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

    public RVP_VirtualMidcourseData getVirtualMidcourseData() {
        return virtualMidcourseData == null ? new RVP_VirtualMidcourseData() : virtualMidcourseData;
    }

    public RVP_MiscData getMiscData() {
        return miscData == null ? new RVP_MiscData() : miscData;
    }

    /** 发射前 UI 用；飞行中优先当前激活阶段。 */
    public RVP_LaserData getLaserData() {
        return laserData == null ? new RVP_LaserData() : laserData;
    }

    /** 目标指示吊舱参数。 */
    public RVP_TargetingPodData getTargetingPodData() {
        return targetingPodData == null ? new RVP_TargetingPodData() : targetingPodData;
    }

    @Nullable
    public String resolveMissileNameOnHud(float distance) {
        return getMiscData().resolveMissileNameOnHud(distance);
    }

    @Nullable
    public String resolveMissileNameOnHudWithFallback(float distance) {
        return getMiscData().resolveMissileNameOnHudWithFallback(distance);
    }

    @Nullable
    public String resolveMissileNameOnRadar(float distance) {
        return getMiscData().resolveMissileNameOnRadar(distance);
    }

    public float resolveSignalIntensityFactorOnRadar(float distance) {
        return getMiscData().resolveSignalIntensityFactorOnRadar(distance);
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
        return getFireData().isRequireLock();
    }

    public boolean isEnableIrHmd() {
        return getGuidanceData().isEnableIrHmd();
    }

    public String getFireControlSensorMode() {
        return fireControlSensorMode == null ? "" : fireControlSensorMode;
    }

    @Nullable
    public Boolean getParentWeaponUnitAimOverride() {
        return parentWeaponUnitAimOverride;
    }

    @Nullable
    public WeaponUnitData.FireControlSensorType getFireControlSensorTypeOverride() {
        return fireControlSensorTypeOverride;
    }

    /**
     * 解析 {@code seeker_color} 为 RGB 整数（低 24 位）。
     *
     * @return 未配置或解析失败返回 null（按本体默认颜色处理）；支持 {@code 0xRRGGBB}、{@code #RRGGBB}、{@code RRGGBB}
     */
    @Nullable
    public Integer getSeekerColorRgb() {
        Integer cached = seekerColorRgbCache;
        if (cached == null) {
            cached = parseSeekerColor(seekerColor);
            seekerColorRgbCache = cached;
        }
        return cached >= 0 ? cached : null;
    }

    /** 解析十六进制颜色串；未配置/非法返回 -1（调用方按未配置处理）。 */
    private static int parseSeekerColor(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        } else if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return -1;
        }
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
        return usesGuidanceType(RVP_EnumGuidanceType.ARH)
                || usesGuidanceType(RVP_EnumGuidanceType.AIR);
    }

    public boolean isSemiActiveRadar() {
        return usesGuidanceType(RVP_EnumGuidanceType.SARH);
    }

    public boolean isInfrared() {
        return usesGuidanceType(RVP_EnumGuidanceType.IR);
    }

    /** Whether the missile carries a seeker head that can acquire a target autonomously. */
    public boolean hasSeeker() {
        return isActiveRadar()
                || isSemiActiveRadar()
                || isInfrared()
                || isAntiRadiationMissile();
    }

    public boolean isRadarHoming() {
        return isActiveRadar() || isSemiActiveRadar();
    }

    public boolean isHomingProjectile() {
        return usesGuidanceType(RVP_EnumGuidanceType.IR)
                || isRadarHoming()
                || isAntiRadiationMissile();
    }

    /** 发射前锁定 UI 用；飞行中优先当前激活阶段的导引头。 */
    public RVP_GuidanceLaunchConfig resolveLaunchGuidanceConfig() {
        return RVP_GuidanceModelResolver.resolveLaunch(this);
    }

    public RVP_GuidanceActiveConfig resolveActiveGuidanceConfig(RVP_GuidancePhase phase) {
        return RVP_GuidanceModelResolver.resolveActive(this, phase);
    }

    public int resolveGuidanceScanIntervalTick() {
        Integer interval = getGuidanceData().getScanIntervalTick();
        return interval == null ? 2 : interval;
    }

    public float resolveLaunchLockRange() {
        return (float) RVP_GuidanceRuntimeGeometry.resolveScanRadius(
                resolveLaunchGuidanceConfig().targetDistanceRange());
    }

    public float resolveLaunchSeekerFullFov() {
        return resolveLaunchGuidanceConfig().maxLockAngle();
    }

    public float resolveLaunchOffAxisLockAngle() {
        return resolveLaunchGuidanceConfig().maxOffAxisLockAngle();
    }

    /** 瞄准吊舱射线长度；优先 {@link RVP_LaserData}，默认 8192。 */
    public float getTargetingPodRange() {
        if (getLaserData().getRange() > 1f) {
            return getLaserData().getRange();
        }
        return 8192f;
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
        return getGuidanceData().usesGuidanceType(type);
    }

    public boolean hasHumanInTheLoop() {
        RVP_GuidanceData guidance = getGuidanceData();
        return guidance instanceof RVP_GuidanceDataHITL;
    }

    public boolean isSaclosTvGuided() {
        RVP_GuidanceData guidance = getGuidanceData();
        return guidance instanceof RVP_GuidanceDataHITL
                && guidance.getGuidanceType() == RVP_EnumGuidanceType.HITL_TV;
    }

    /** Laser-spot weapons that need the vehicle laser-designation client state. */
    public boolean isVehicleLaserGuided() {
        RVP_GuidanceData guidance = getGuidanceData();
        return guidance.getGuidanceType() == RVP_EnumGuidanceType.LH
                || guidance.getGuidanceType() == RVP_EnumGuidanceType.SALH;
    }

    /** 需要操作手持续提供世界瞄准点的制导类型（激光点、驾束或视线指令）。 */
    public boolean isOperatorGuided() {
        return isOperatorGuidanceType(getGuidanceData().getGuidanceType());
    }

    /** 判断制导类型是否需要客户端或 AI 持续同步世界瞄准点。 */
    static boolean isOperatorGuidanceType(@Nullable RVP_EnumGuidanceType guidanceType) {
        return guidanceType == RVP_EnumGuidanceType.LH
                || guidanceType == RVP_EnumGuidanceType.SALH
                || guidanceType == RVP_EnumGuidanceType.LBR
                || guidanceType == RVP_EnumGuidanceType.SACLOS;
    }

    public boolean isHitlClosTvGuided() {
        RVP_GuidanceData guidance = getGuidanceData();
        return guidance.getGuidanceType() == RVP_EnumGuidanceType.HITL_CLOS_TV;
    }

    /** New SACLOS is an operator line-of-sight command, not a laser seeker. */
    public boolean isCommandGuided() {
        RVP_GuidanceData guidance = getGuidanceData();
        return guidance.getGuidanceType() == RVP_EnumGuidanceType.SACLOS
                || guidance.getGuidanceType() == RVP_EnumGuidanceType.HITL_CLOS_TV;
    }
}
