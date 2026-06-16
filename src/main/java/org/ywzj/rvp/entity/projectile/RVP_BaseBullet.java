package org.ywzj.rvp.entity.projectile;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceConfigMerger;
import org.ywzj.rvp.guidance.RVP_GuidanceController;
import org.ywzj.rvp.guidance.RVP_GuidancePhaseSelector;
import org.ywzj.rvp.guidance.RVP_GuidanceRigidityUtil;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.vehicle.util.VehicleExplosion;
import org.ywzj.rvp.weapon.util.RVP_BounceUtil;
import org.ywzj.rvp.weapon.util.RVP_WallPenetrationUtil;
import org.ywzj.rvp.weapon.damage.RVP_DamageApplier;
import org.ywzj.rvp.network.RVP_BulletHitDebugNetworking;
import org.ywzj.rvp.weapon.util.RVP_DamageDecayUtil;
import org.ywzj.rvp.weapon.damage.RVP_DecayContext;
import org.ywzj.rvp.weapon.damage.RVP_HitboxDamageContext;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.weapon.data.RVP_CollisionData;
import org.ywzj.rvp.weapon.data.RVP_DamageDecayRuleData;
import org.ywzj.rvp.weapon.data.RVP_FuseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore;
import org.ywzj.rvp.weapon.data.RVP_DispenserPayloadData;
import org.ywzj.rvp.weapon.effects.RVP_DetonateApplier;
import org.ywzj.rvp.weapon.effects.RVP_DispenserPlacement;
import org.ywzj.rvp.weapon.effects.RVP_ProjectileParticleEffects;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger;
import org.ywzj.rvp.weapon.submunition.RVP_SubmunitionRunner;
import org.ywzj.vehicle.all.AllDamageTypes;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.particle.BulletHoleOption;
import org.ywzj.vehicle.util.BulletHitResult;
import org.ywzj.vehicle.util.EntityUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Common runtime base for all new RVP projectile entities.
 *
 * <p>It owns generic projectile mechanics: kinematics, fuses, proximity,
 * submunition timing, impact effects, penetration, ricochet and damage falloff.
 * Guidance-specific motion changes are delegated to {@link RVP_GuidanceController}.</p>
 */
public abstract class RVP_BaseBullet extends AmmoEntity {

    private static final double PARTICLE_VIEW_DISTANCE = 512.0D;
    private static final double PARTICLE_VIEW_DISTANCE_SQ = PARTICLE_VIEW_DISTANCE * PARTICLE_VIEW_DISTANCE;
    private static final Logger LOGGER = LogUtils.getLogger();

    protected RVP_WeaponData rvpData;
    /** Snapshot of {@code collision_data.damage_decay} at spawn (decoupled from shared weapon index data). */
    private List<RVP_DamageDecayRuleData> damageDecayRules = List.of();
    protected RVP_EnumWeaponKind weaponKind = RVP_EnumWeaponKind.ROCKET;
    protected AbstractVehicle shooterVehicle;
    protected WeaponUnit shooterWeaponUnit;
    protected double flightSpeed;
    /** Official cannon-style linear friction (machinegun only). */
    protected float cannonFriction = 0.01f;
    /** Official cannon-style positive-down gravity per tick (machinegun only). */
    protected float cannonGravity;
    protected int updateCount;
    /** 可编程空爆测距（米），来自 MCH {@code airburstDist}。 */
    protected int airburstDist;
    protected double airburstTravelled;
    protected boolean airburstTriggered;
  @Nullable
    protected RVP_SubmunitionRunner submunitionRunner;
    /** Child projectiles increment depth; blocks chains beyond {@link org.ywzj.rvp.weapon.submunition.RVP_SubmunitionSpawner#MAX_DEPTH}. */
    protected int submunitionDepth;
    protected int livingPenetrationLeft;
    protected int wallPenetrationLeft;
    protected final Set<Integer> piercedLivingIds = new HashSet<>();
    /** Completed penetrations (living or solid wall); drives stacked damage decay. */
    protected int penetrationEventCount;
    protected float penetrationDamageMultiplier = 1f;
    protected float penetrationSpeedMultiplier = 1f;
    protected int bounceLeft;
    /** Bounces already applied this flight (prevents re-granting budget after config hot-reload). */
    protected int bouncesConsumed;
    protected float bounceStrength = 0.6f;
    protected int bounceFuseTick;
    protected int bounceFuseCountdown = -1;
    protected float bounceIncidenceAngleMin;
    protected boolean bounceOnVehicle;
    protected double flightDistance;
    /** 最近一次撞击的入射角（度），用于落点爆炸伤害衰减。 */
    protected float lastImpactIncidenceAngleDeg = Float.NaN;

    @Nullable
    protected BlockHitResult lastBlockHit;

    @Nullable
    protected Entity targetEntity;
    @Nullable
    protected Vec3 targetPos;
    @Nullable
    protected Vec3 lastGuidancePos;
    protected final Map<Long, Integer> radiationPulseTickMap = new HashMap<>();
    protected int antiRadiationNextScanTick;
    protected int antiRadiationMemoryLeftTick;
    protected boolean antiRadiationLostPermanent;

    /** 发动机熄火的 tick 数（服务端计算，通过生成数据包同步到客户端，解决 rvpData null 时持续出烟的问题）。 */
    protected int motorBurnEndTick = Integer.MAX_VALUE;

    /** 本 tick 内直击命中的载具 ID 集合，用于区分 HE 直击与非直击爆炸的 ERA 破坏。 */
    protected final java.util.Set<Integer> directHitVehicleIds = new java.util.HashSet<>();

    /** 尾焰粒子上帧位置（对标本体 MissileEntity.particlePosO）。 */
    @Nullable
    protected Vec3 particlePosO;

    /** ARM preselect target vehicle ID (from HUD selection). -1 = none. */
    protected int preselectedVehicleId = -1;
    /** ARM preselect target radar index. -1 = any. */
    protected int preselectedRadarIndex = -1;

    /** ===== ARH 主动雷达制导状态字段（对标本体的 MissileEntity） ===== */
    /** 弹载雷达是否已开机（主动雷达截获状态）。 */
    protected boolean activeRadarOn;
    /** 弹载雷达是否已成功捕获目标（true 后不再依赖载机雷达续标）。 */
    protected boolean activeRadarCatch;
    /** 主动雷达丢失目标后的倒计时 tick。≥60 自毁。 */
    protected int activeRadarLostTargetTick;
    /** 主动雷达开机距离阈值（JSON 中由 active_radar_activation_range 配置）。 */
    protected double activeRadarActivationRange = 1024.0;

    protected int guidanceStageIndex = -1;
    protected int guidanceStageEnteredTick;
    protected final java.util.Map<Integer, Integer> guidanceStageEnteredTicks = new java.util.HashMap<>();
    protected int guidanceOverlapResolveIndex = -1;
    protected final java.util.Set<Integer> guidanceStickyPhaseIndices = new java.util.HashSet<>();
    @Nullable
    protected RVP_EnumGuidanceType activeSourceType;
    @Nullable
    protected String activeStageName;
    /** Set when MCLOS {@code take_over_motion} applied wire-direct steering this tick. */
    private boolean guidanceWireDirectApplied;

    public RVP_BaseBullet(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
        this.keepChunkLoaded = true;
    }

    public RVP_BaseBullet(EntityType<? extends Projectile> type, Level level) {
        this(type, level, null);
    }

    public void initFromWeapon(RVP_WeaponData data, RVP_EnumWeaponKind kind, AbstractVehicle vehicle, LivingEntity shooter,
                               Vec3 spawnPos, AimRot aim, Vec3 initialMotion) {
        this.rvpData = data;
        this.damageDecayRules = List.copyOf(data.getDamageDecayRules());
        this.weaponKind = kind == null ? RVP_EnumWeaponKind.ROCKET : kind;
        this.shooterVehicle = vehicle;
        this.vehicle = vehicle;
        this.setOwner(shooter);
        this.damage = data.getDirectDamage();
        this.headShot = data.getHeadshotMultiplier();
        this.explosion = data.getExplosionData();
        this.life = data.getLife();
        // 计算发动机熄火 tick，供客户端尾焰控制（rvpData 不传客户端）
        if (data.usesPropulsion()) {
            int ignition = data.getResolvedIgnitionDelayTick();
            int burnTicks = Math.round(data.getResolvedMotorBurnTime());
            this.motorBurnEndTick = ignition + burnTicks;
        } else {
            this.motorBurnEndTick = Integer.MAX_VALUE;
        }
        this.submunitionRunner = RVP_SubmunitionRunner.create(data.getSubmunitionData(), submunitionDepth);
        this.livingPenetrationLeft = data.getLivingPenetration();
        this.wallPenetrationLeft = data.getWallPenetration();
        this.piercedLivingIds.clear();
        this.penetrationEventCount = 0;
        this.penetrationDamageMultiplier = data.getPenetrationDamageMultiplier();
        this.penetrationSpeedMultiplier = data.getPenetrationSpeedMultiplier();
        this.bouncesConsumed = 0;
        this.bounceLeft = data.getBounce();
        this.bounceStrength = data.getBounceStrength();
        this.bounceFuseTick = data.getBounceFuseTick();
        this.bounceIncidenceAngleMin = data.getBounceIncidenceAngle();
        this.bounceOnVehicle = data.isBounceOnVehicle();
        Vec3 spawnMotion = initialMotion;
        if (usesCannonBallistics(kind)) {
            this.cannonFriction = data.getCannonFriction();
            this.cannonGravity = data.getCannonGravity();
            this.flightSpeed = Math.max(spawnMotion.length(), 0.01f);
        } else {
            float projectileSpeed = data.getProjectileVelocity();
            if (data.getProjectileData().isRocketEngineMisconfigured()) {
                LOGGER.warn(
                        "Weapon {} has_rocket_engine=true but missing mass/thrust/motor_burn_time; using simplified ballistics",
                        data.getWeaponId());
            } else if (!data.usesPropulsion() && kind == RVP_EnumWeaponKind.ROCKET && projectileSpeed > 4f) {
                // Legacy high-speed rockets without propulsion: scale once at spawn (was wrongly applied every tick).
                spawnMotion = spawnMotion.scale(projectileSpeed / 4f);
            }
            this.flightSpeed = Math.max(Math.max(spawnMotion.length(), projectileSpeed), 0.01f);
        }
        this.setPos(spawnPos);
        this.setDeltaMovement(spawnMotion);
    }

    public int getSubmunitionDepth() {
        return submunitionDepth;
    }

    public void setSubmunitionDepth(int depth) {
        this.submunitionDepth = Math.max(depth, 0);
    }

    public void disableSubmunitionReleases() {
        if (submunitionRunner != null) {
            submunitionRunner.disableAll();
        }
    }

    protected boolean trySubmunitionTrigger(RVP_EnumSubmunitionTrigger trigger) {
        if (submunitionRunner == null || level().isClientSide()) {
            return false;
        }
        return submunitionRunner.fireTrigger(this, trigger);
    }

    /** 从炮塔武器槽读取 R 键测距结果（发射前由 {@link RVP_ProjectileSpawner} 调用）。 */
    public void bindProgrammableAirburstRange(WeaponUnit unit, int weaponIndex) {
        if (rvpData == null || shooterVehicle == null || unit == null
                || !rvpData.getFuseData().isProgrammableAirburst()) {
            airburstDist = 0;
            return;
        }
        airburstDist = RVP_AirburstRangeStore.get(shooterVehicle, unit, weaponIndex);
    }

    @Nullable
    public RVP_WeaponData getRvpData() {
        return rvpData;
    }

    /**
     * True when every currently active stage has passed its per-stage {@code rigidity_time} window.
     */
    public boolean rvp$isPastRigidityTime() {
        RVP_WeaponData data = resolveWeaponConfig();
        if (data == null) {
            return true;
        }
        return !RVP_GuidanceRigidityUtil.isAnyActiveStageRigid(this, data);
    }

    /** True while an active guidance stage includes SACLOS. */
    public boolean rvp$isInSaclosGuidanceStage() {
        RVP_WeaponData data = resolveWeaponConfig();
        if (data == null || !data.usesGuidanceType(RVP_EnumGuidanceType.SACLOS)) {
            return false;
        }
        return RVP_GuidancePhaseSelector.selectActive(this, data).stream()
                .anyMatch(selection -> selection.stage().getSources().stream()
                        .anyMatch(source -> source.getType() == RVP_EnumGuidanceType.SACLOS));
    }

    /** Live spawn config, or reload from the weapon index when {@link #rvpData} was not kept. */
    @Nullable
    protected RVP_WeaponData resolveWeaponConfig() {
        if (rvpData != null) {
            return rvpData;
        }
        ResourceLocation id = getWeaponId();
        if (id == null) {
            return null;
        }
        return CommonAssetsManager.vehicleWeaponManager().getIndex(id)
                .map(index -> index.data() instanceof RVP_WeaponData weaponData ? weaponData : null)
                .orElse(null);
    }

    protected List<RVP_DamageDecayRuleData> damageDecayRules() {
        if (!damageDecayRules.isEmpty()) {
            return damageDecayRules;
        }
        RVP_WeaponData config = resolveWeaponConfig();
        if (config == null) {
            return Collections.emptyList();
        }
        List<RVP_DamageDecayRuleData> loaded = config.getDamageDecayRules();
        if (!loaded.isEmpty()) {
            damageDecayRules = List.copyOf(loaded);
        }
        return damageDecayRules;
    }

    /** Distance in meters for decay sampling (includes the current tick segment before motion integration). */
    protected float decaySampleDistanceM() {
        Vec3 start = collisionSegmentStart();
        Vec3 end = collisionSegmentEnd();
        return (float) (flightDistance + start.distanceTo(end));
    }

    public RVP_EnumWeaponKind getWeaponKind() {
        return weaponKind;
    }

    public double getFlightSpeed() {
        return flightSpeed;
    }

    public AbstractVehicle getShooterVehicle() {
        return shooterVehicle;
    }

    public WeaponUnit getShooterWeaponUnit() {
        return shooterWeaponUnit;
    }

    public void setShooterWeaponUnit(WeaponUnit shooterWeaponUnit) {
        this.shooterWeaponUnit = shooterWeaponUnit;
    }

    @Nullable
    public Entity getTargetEntity() {
        return targetEntity;
    }

    public void setTargetEntity(@Nullable Entity target) {
        this.targetEntity = target;
        if (target != null) {
            this.lastGuidancePos = aimPoint(target);
        }
    }

    @Nullable
    public Vec3 getTargetPos() {
        return targetPos;
    }

    public void setTargetPos(@Nullable Vec3 targetPos) {
        this.targetPos = targetPos;
        if (targetPos != null) {
            this.lastGuidancePos = targetPos;
        }
    }

    @Nullable
    public Vec3 getLastGuidancePos() {
        return lastGuidancePos;
    }

    public void clearTarget() {
        this.targetEntity = null;
        this.targetPos = null;
    }

    public void rememberGuidancePos(@Nullable Vec3 pos) {
        if (pos != null) {
            this.lastGuidancePos = pos;
        }
    }

    public boolean isMissile() {
        return weaponKind == RVP_EnumWeaponKind.MISSILE;
    }

    public int getUpdateCount() {
        return updateCount;
    }

    public Map<Long, Integer> getRadiationPulseTickMap() {
        return radiationPulseTickMap;
    }

    public int getAntiRadiationNextScanTick() {
        return antiRadiationNextScanTick;
    }

    public void setAntiRadiationNextScanTick(int tick) {
        this.antiRadiationNextScanTick = tick;
    }

    public int getAntiRadiationMemoryLeftTick() {
        return antiRadiationMemoryLeftTick;
    }

    public void setAntiRadiationMemoryLeftTick(int tick) {
        this.antiRadiationMemoryLeftTick = Math.max(tick, 0);
    }

    public boolean isAntiRadiationLostPermanent() {
        return antiRadiationLostPermanent;
    }

    public void setAntiRadiationLostPermanent(boolean lostPermanent) {
        this.antiRadiationLostPermanent = lostPermanent;
    }

    public int getPreselectedVehicleId() {
        return preselectedVehicleId;
    }

    public int getPreselectedRadarIndex() {
        return preselectedRadarIndex;
    }

    public void setPreselectedTarget(int vehicleId, int radarIndex) {
        this.preselectedVehicleId = vehicleId;
        this.preselectedRadarIndex = radarIndex;
    }

    // ===== ARH 主动雷达 getters/setters =====

    public boolean isActiveRadarOn() {
        return activeRadarOn;
    }

    public boolean isActiveRadarCatch() {
        return activeRadarCatch;
    }

    public int getActiveRadarLostTargetTick() {
        return activeRadarLostTargetTick;
    }

    public double getActiveRadarActivationRange() {
        return activeRadarActivationRange;
    }

    public void setActiveRadarActivationRange(double range) {
        this.activeRadarActivationRange = range;
    }

    public int getGuidanceStageIndex() {
        return guidanceStageIndex;
    }

    public void setGuidanceStageIndex(int guidanceStageIndex) {
        this.guidanceStageIndex = guidanceStageIndex;
    }

    public int getGuidanceStageEnteredTick() {
        return guidanceStageEnteredTick;
    }

    public void setGuidanceStageEnteredTick(int guidanceStageEnteredTick) {
        this.guidanceStageEnteredTick = Math.max(guidanceStageEnteredTick, 0);
    }

    public int getGuidanceStageEnteredTick(int stageIndex) {
        Integer entered = guidanceStageEnteredTicks.get(stageIndex);
        return entered != null ? entered : 0;
    }

    public void markGuidanceStageEnteredIfAbsent(int stageIndex, int tick) {
        if (stageIndex < 0) {
            return;
        }
        guidanceStageEnteredTicks.putIfAbsent(stageIndex, Math.max(tick, 0));
    }

    public void clearGuidanceStageEnteredTick(int stageIndex) {
        guidanceStageEnteredTicks.remove(stageIndex);
    }

    public java.util.Set<Integer> getGuidanceStageEnteredTickIndices() {
        return guidanceStageEnteredTicks.keySet();
    }

    public int getGuidanceOverlapResolveIndex() {
        return guidanceOverlapResolveIndex;
    }

    public void setGuidanceOverlapResolveIndex(int guidanceOverlapResolveIndex) {
        this.guidanceOverlapResolveIndex = guidanceOverlapResolveIndex;
    }

    @Nullable
    public RVP_EnumGuidanceType getActiveSourceType() {
        return activeSourceType;
    }

    public void setActiveSourceType(@Nullable RVP_EnumGuidanceType activeSourceType) {
        this.activeSourceType = activeSourceType;
    }

    public void rvp$markGuidanceWireDirectApplied() {
        this.guidanceWireDirectApplied = true;
    }

    public boolean rvp$guidanceWireDirectApplied() {
        return guidanceWireDirectApplied;
    }

    @Nullable
    public String getActiveStageName() {
        return activeStageName;
    }

    public void setActiveStageName(@Nullable String activeStageName) {
        this.activeStageName = activeStageName;
    }

    public java.util.Set<Integer> getGuidanceStickyPhaseIndices() {
        return guidanceStickyPhaseIndices;
    }

    public void addGuidanceStickyPhaseIndex(int index) {
        if (index >= 0) {
            guidanceStickyPhaseIndices.add(index);
        }
    }

    /**
     * RVP integrates position manually ({@link RVP_ProjectileMotion} / {@link RVP_BulletEntity});
     * block vanilla {@link Entity#move} so {@link AmmoEntity#tickHit()} segments stay blocks-per-tick.
     */
    @Override
    public void move(MoverType type, Vec3 delta) {
        if (type == MoverType.SELF) {
            return;
        }
        super.move(type, delta);
    }

    @Override
    public void tick() {
        super.tick();
        if (this instanceof RVP_BulletEntity bullet) {
            bullet.tickBullet();
            return;
        }
        // Match {@link org.ywzj.vehicle.entity.weapon.MissileEntity}: motion server-only; client uses synced rot + AmmoEntity lerp.
        if (level().isClientSide()) {
            spawnTrailParticles();
            return;
        }

        if (rvpData == null) {
            discard();
            return;
        }

        updateCount++;
        if (tickDelayFuse()) {
            return;
        }
        if (!checkShooterValid()) {
            discard();
            return;
        }

        tickSubmunition();
        guidanceWireDirectApplied = false;
        tickGuidance();
        // 先碰撞检测再运动（对标本体 BulletEntity 顺序，修复直接命中丢失的 bug）
        tickHit();
        tickMotion();
        tickProgrammableAirburst();
        tickProximityFuse();
        if (tickBounceFuse()) {
            return;
        }
        broadcastTrailParticles();
        life--;
        if (life < 0) {
            if (rvpData.getFuseData().isDetonateOnLifeEnd()) {
                explodeAndDiscard(position());
            } else {
                discard();
            }
        }
    }

    protected void tickGuidance() {
        if (!level().isClientSide()) {
            org.ywzj.rvp.guidance.saclos.RVP_SaclosDesignation.tickUpdateLiveTarget(this);
            // Mid-course update: if we have a live targetEntity (from launch lock),
            // periodically update targetPos so IOG/coast phase tracks the moving target.
            // This mirrors the base MissileEntity behavior where targetEntity is a
            // live Java Entity reference updated every tick.
            if (targetEntity != null && targetEntity.isAlive()) {
                if (tickCount % 5 == 0) {
                    targetPos = aimPoint(targetEntity);
                    lastGuidancePos = targetPos;
                }
            } else if (targetEntity != null && !targetEntity.isAlive()) {
                // Target died, clear it
                targetEntity = null;
            }
        }
        RVP_GuidanceController.tick(this);
    }

    protected static boolean usesCannonBallistics(RVP_EnumWeaponKind kind) {
        return kind == RVP_EnumWeaponKind.MACHINEGUN;
    }

    protected boolean usesCannonBallistics() {
        return usesCannonBallistics(weaponKind);
    }

    /**
     * Same integration as {@link org.ywzj.vehicle.entity.weapon.BulletEntity#tick()}:
     * position += velocity; velocity *= (1 - friction); velocity.y -= gravity.
     */
    protected void tickMotion() {
        if (usesCannonBallistics()) {
            return;
        }
        if (rvpData != null && rvpData.usesPropulsion()) {
            RVP_ProjectileMotion.tickMissileMove(this);
            return;
        }
        tickBallisticMotion();
    }

    /** 发射后由 {@link org.ywzj.rvp.weapon.core.RVP_ProjectileSpawner} 在叠加载机速度后调用。 */
    public void finalizeSpawnOrientation(AimRot aim) {
        RVP_ProjectileMotion.finalizeSpawnOrientation(this, aim);
    }

    /** 无推力配置时的简化弹道（MCH 重力 + {@link #applyMchHorizontalDrag}，可选恒定速度）。 */
    protected void tickBallisticMotion() {
        Vec3 velocity = getDeltaMovement();
        if (!isInWater()) {
            velocity = velocity.add(0, rvpData.getGravity(), 0);
            velocity = applyMchHorizontalDrag(velocity, rvpData.getDragInAir());
        } else {
            velocity = velocity.add(0, rvpData.getGravityInWater(), 0);
            velocity = applyMchHorizontalDrag(velocity, rvpData.getDragInWater());
        }
        if (rvpData.getProjectileData().isConstantSpeed() && velocity.lengthSqr() > 1.0E-6) {
            velocity = velocity.normalize().scale(Math.max(flightSpeed, 0.01));
        }
        velocity = clampSpeed(velocity);
        setDeltaMovement(velocity);
        setPos(position().add(velocity));
        flightDistance += velocity.length();
        RVP_ProjectileMotion.applyRotationFromVelocity(this, velocity);
    }

    private Vec3 clampSpeed(Vec3 velocity) {
        return RVP_ProjectileMotion.clampSpeed(this, velocity, rvpData);
    }

    /**
     * MCH {@code DragInAir}: gravity already applied to Y; subtract {@code drag} along velocity direction
     * from X/Z only (see {@code MCH_EntityBaseBullet#onUpdate}).
     */
    static Vec3 applyMchHorizontalDrag(Vec3 velocity, float drag) {
        if (drag <= 0f) {
            return velocity;
        }
        double speed = velocity.length();
        if (speed <= 1.0E-6) {
            return velocity;
        }
        double dirX = velocity.x / speed;
        double dirZ = velocity.z / speed;
        return new Vec3(
                velocity.x - dirX * drag,
                velocity.y,
                velocity.z - dirZ * drag
        );
    }

    protected boolean tickDelayFuse() {
        int delay = rvpData.getDelayFuse();
        if (delay > 0 && updateCount >= delay) {
            detonateFuseAt(position(), FuseDetonation.NORMAL);
            return true;
        }
        return false;
    }

    /**
     * MCH {@code onUpdateAirburst}：沿弹道累计距离达到 {@code airburstDist + offset} 米时引爆。
     */
    protected void tickProgrammableAirburst() {
        if (rvpData == null || airburstTriggered) {
            return;
        }
        RVP_FuseData fuse = rvpData.getFuseData();
        if (!fuse.isProgrammableAirburst()) {
            return;
        }
        int measured = airburstDist;
        int min = fuse.getAirburstMeasureMin();
        int max = fuse.getAirburstMeasureMax();
        if (measured <= min || measured >= max) {
            return;
        }
        double targetDist = measured + fuse.getAirburstOffset();
        Vec3 motion = getDeltaMovement();
        double segLen = motion.length();
        if (segLen <= 0.0D) {
            return;
        }
        double newTravel = airburstTravelled + segLen;
        if (newTravel >= targetDist) {
            double remain = targetDist - airburstTravelled;
            double t = remain / segLen;
            Vec3 detonatePos = position().add(motion.scale(t));
            detonateFuseAt(detonatePos, FuseDetonation.AIRBURST);
            airburstTriggered = true;
            airburstTravelled = 0.0D;
        } else {
            airburstTravelled = newTravel;
        }
    }

    protected void tickProximityFuse() {
        if (rvpData == null) {
            return;
        }
        RVP_FuseData fuse = rvpData.getFuseData();
        int armTick = fuse.getProximityFuseTick();
        if (armTick >= 0 && updateCount <= armTick) {
            return;
        }
        float radius = rvpData.getProximityFuseDist();
        if (radius <= 0f) {
            return;
        }
        int fuseHeight = fuse.getProximityFuseHeight();
        if (targetEntity != null && targetEntity.isAlive()
                && !isProximityFuseTargetTooLow(targetEntity, fuseHeight)
                && distanceToSqr(targetEntity) < radius * radius) {
            detonateFuseAt(position(), FuseDetonation.PROXIMITY, targetEntity);
            return;
        }
        // 对标本体 AmmoEntity.tickHit：检测盒向后偏移，捕获刚飞过的目标
        Vec3 backward = getLookAngle().normalize().scale(-radius);
        AABB detectionBox = getBoundingBox().inflate(radius).move(backward);
        for (Entity entity : level().getEntities(this, detectionBox,
                e -> canDamageEntity(e) && !isProximityFuseTargetTooLow(e, fuseHeight))) {
            detonateFuseAt(position(), FuseDetonation.PROXIMITY, entity);
            return;
        }
    }

    /** MCH proximity fuse skips targets on/near ground within {@link RVP_FuseData#getProximityFuseHeight()}. */
    protected boolean isProximityFuseTargetTooLow(Entity entity, int fuseHeight) {
        return RVP_GuidanceMath.isEntityNearGroundBlocks(entity, fuseHeight);
    }

    protected void tickSubmunition() {
        if (submunitionRunner == null) {
            return;
        }
        if (submunitionRunner.tickInFlight(this)) {
            discard();
        }
    }

    /**
     * Hit-scan segment; matches {@link AmmoEntity#tickHit()} ({@code position} → {@code position + deltaMovement}).
     * {@link RVP_BulletEntity} overrides timing to run before integrating motion (like {@link org.ywzj.vehicle.entity.weapon.BulletEntity}).
     */
    protected Vec3 collisionSegmentStart() {
        return position();
    }

    protected Vec3 collisionSegmentEnd() {
        return position().add(getDeltaMovement());
    }

    /**
     * Same detection order as {@link AmmoEntity#tickHit()}: entity → proximity fuze → block.
     */
    @Override
    protected void tickHit() {
        performAmmoEntityTickHit(collisionSegmentStart(), collisionSegmentEnd());
    }

    protected void performAmmoEntityTickHit(Vec3 startVec, Vec3 endVec) {
        Vec3 step = endVec.subtract(startVec);
        if (step.lengthSqr() < 1.0E-12) {
            return;
        }

        BulletHitResult entityResult = findEntityOnPathForSegment(startVec, endVec, step);

        if (entityResult != null
                && entityResult.getEntity() != vehicle
                && (vehicle == null || !vehicle.getPassengers().contains(entityResult.getEntity()))
                && !piercedLivingIds.contains(entityResult.getEntity().getId())) {
            handleEntityImpact(entityResult);
            return;
        }

        if (explosion != null && explosion.proximityFuze && tickCount > 5 && entityResult == null) {
            if (tryAmmoProximityFuze()) {
                return;
            }
        }

        BlockHitResult blockResult = level().clip(
                new ClipContext(startVec, endVec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (blockResult.getType() != HitResult.Type.MISS) {
            onAmmoBlockHit(blockResult);
        }
    }

    /**
     * {@link EntityUtil#findEntityOnPath} expands AABB with {@link #getDeltaMovement()}; use this tick's travel vector.
     */
    @Nullable
    protected BulletHitResult findEntityOnPathForSegment(Vec3 startVec, Vec3 endVec, Vec3 step) {
        Vec3 saved = getDeltaMovement();
        setDeltaMovement(step);
        try {
            return EntityUtil.findEntityOnPath(this, startVec, endVec);
        } finally {
            setDeltaMovement(saved);
        }
    }

    /** {@link AmmoEntity#tickHit()} proximity branch; explosion uses {@link #resolveImpactDetonation}. */
    protected boolean tryAmmoProximityFuze() {
        if (explosion == null || !explosion.proximityFuze || vehicle == null) {
            return false;
        }
        AABB detectionBox = getBoundingBox().inflate(explosion.proximityRadius)
                .move(getLookAngle().normalize().scale(-explosion.proximityRadius));
        int fuseHeight = rvpData != null ? rvpData.getFuseData().getProximityFuseHeight() : 20;
        List<Entity> nearbyEntities = level().getEntities(this, detectionBox,
                entity -> entity != vehicle && !vehicle.getPassengers().contains(entity)
                        && !isProximityFuseTargetTooLow(entity, fuseHeight));
        if (nearbyEntities.isEmpty()) {
            return false;
        }
        resolveImpactDetonation(position(), null, false);
        if (explosion.explode) {
            triggerExplosion(position());
        }
        discard();
        return true;
    }

    protected void onAmmoBlockHit(BlockHitResult result) {
        if (handleBlockImpact(result)) {
            return;
        }
    }

    protected boolean handleBlockImpact(BlockHitResult firstHit) {
        Vec3 segmentEnd = collisionSegmentEnd();
        BlockHitResult result = firstHit;
        int guard = 0;
        while (result.getType() != HitResult.Type.MISS && guard++ < 32) {
            lastBlockHit = result;
            spawnAmmoBlockImpactEffects(result);
            Vec3 hit = result.getLocation();
            if (tryBounceFromBlockHit(result)) {
                return true;
            }
            BlockPos blockPos = result.getBlockPos();
            BlockState blockState = level().getBlockState(blockPos);
            if (RVP_WallPenetrationUtil.isPassThroughBlock(blockState)) {
                setPos(RVP_WallPenetrationUtil.positionPastBlockFace(hit, getDeltaMovement(), result));
                BlockHitResult next = clipBlockSegment(position(), segmentEnd);
                if (next.getType() == HitResult.Type.MISS) {
                    return false;
                }
                result = next;
                continue;
            }
            if (wallPenetrationLeft > 0) {
                if (RVP_WallPenetrationUtil.isImpenetrable(blockState, level(), blockPos)) {
                    break;
                }
                if (!RVP_WallPenetrationUtil.destroyForPenetration(level(), blockPos, blockState, getOwner())) {
                    break;
                }
                wallPenetrationLeft--;
                applyPenetrationSpeedDecay();
                penetrationEventCount++;
                setPos(RVP_WallPenetrationUtil.positionPastBlockFace(hit, getDeltaMovement(), result));
                BlockHitResult next = clipBlockSegment(position(), segmentEnd);
                if (next.getType() == HitResult.Type.MISS) {
                    return false;
                }
                result = next;
                continue;
            }
            break;
        }
        Vec3 hit = result.getLocation();
        rememberImpactIncidence(getDeltaMovement(), Vec3.atLowerCornerOf(result.getDirection().getNormal()));
        if (dispenserOnlyImpact()) {
            applyDispenserAt(hit, result);
            discard();
            return true;
        }
        if (trySubmunitionTrigger(RVP_EnumSubmunitionTrigger.ON_BLOCK_HIT)
                || trySubmunitionTrigger(RVP_EnumSubmunitionTrigger.ON_IMPACT)) {
            discard();
            return true;
        }
        resolveImpactDetonation(hit, result, true);
        discard();
        return true;
    }

    protected BlockHitResult clipBlockSegment(Vec3 from, Vec3 to) {
        return level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
    }

    private void handleEntityImpact(BulletHitResult result) {
        if (tryBounceFromEntityHit(result)) {
            return;
        }
        Entity entity = result.getEntity();
        Vec3 velocity = getDeltaMovement();
        Vec3 normal = RVP_BounceUtil.impactNormal(
                entity, collisionSegmentStart(), collisionSegmentEnd(), result.getLocation(), velocity);
        rememberImpactIncidence(velocity, normal);
        applyEntityHitDamage(entity, result);
        if (entity instanceof LivingEntity && livingPenetrationLeft > 0) {
            livingPenetrationLeft--;
            piercedLivingIds.add(entity.getId());
            applyPenetrationSpeedDecay();
            penetrationEventCount++;
            setPos(RVP_WallPenetrationUtil.positionPastEntityHit(result.getLocation(), getDeltaMovement()));
            return;
        }
        Vec3 hitPos = result.getLocation();
        if (trySubmunitionTrigger(RVP_EnumSubmunitionTrigger.ON_ENTITY_HIT)
                || trySubmunitionTrigger(RVP_EnumSubmunitionTrigger.ON_IMPACT)) {
            discard();
            return;
        }
        resolveImpactDetonation(hitPos, null, false);
        if (explosion != null && explosion.explode) {
            discard();
            return;
        }
        discard();
    }

    protected void applyEntityHitDamage(Entity entity, BulletHitResult result) {
        Entity owner = getOwner();
        boolean headshot = result.isHeadshot();
        float distanceMult = distanceDecayFactor();
        float incidenceMult = incidenceDecayFactor();
        float penetrationMult = penetrationDamageFactor();
        RVP_WeaponData config = resolveWeaponConfig();
        float vehicleMult = config != null ? config.getDirectDamageFactor().getFactor(entity) : 1f;
        float hitboxMult = 1f;
        RVP_VehicleHitboxFactorManager.HitboxDamageResult hitboxRes = null;
        if (entity instanceof AbstractVehicle targetVehicle) {
            hitboxRes = RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDamage(
                    targetVehicle, collisionSegmentStart(), collisionSegmentEnd());
            hitboxMult = hitboxRes.factor();
        }
        float preHitboxMult = distanceMult * incidenceMult * penetrationMult * vehicleMult;
        float totalMult = preHitboxMult * hitboxMult;
        float base = headshot ? damage * headShot : damage;
        float preHitboxDamage = base * preHitboxMult;
        float finalDamage = preHitboxDamage * hitboxMult;
        if (entity instanceof AbstractVehicle && level() instanceof ServerLevel serverLevel) {
            RVP_BulletHitDebugNetworking.notifyVehicleHit(
                    serverLevel,
                    result.getLocation(),
                    lastImpactIncidenceAngleDeg,
                    decaySampleDistanceM(),
                    totalMult,
                    distanceMult,
                    incidenceMult,
                    penetrationMult,
                    vehicleMult,
                    hitboxMult,
                    hitboxRes == null ? null : hitboxRes.hitBoneName());
        }
        DamageSource source = AllDamageTypes.Sources.bullet(level().registryAccess(), this, owner, result.getLocation());
        RVP_HitboxDamageContext.pushSkipGlobalVehicleHurtScaling();
        try {
            EntityUtil.hurt(source, entity, finalDamage);
        } finally {
            RVP_HitboxDamageContext.popSkipGlobalVehicleHurtScaling();
        }
        if (hitboxRes != null && entity instanceof AbstractVehicle targetVehicle && !level().isClientSide()) {
            // 记录直击命中的载具，用于 triggerExplosion 中区分 HE 直击与非直击
            directHitVehicleIds.add(targetVehicle.getId());
            // 区分 HE 弹与 AP 弹的 ERA 破坏路径
            if (explosion != null && explosion.explode && explosion.radius > 5f) {
                // HE 弹（爆炸半径 > 5）→ 机制二A（百分比破坏，按直击位置排序，至少 1 块保底）
                RVP_VehicleHitboxFactorManager.destroyEraByExplosionRadius(
                        targetVehicle, explosion.radius, result.getLocation(), true);
            } else {
                // AP 弹或小爆炸弹 → 机制一（OBB 单块）
                RVP_VehicleHitboxFactorManager.INSTANCE.tryTriggerEra(targetVehicle, hitboxRes, preHitboxDamage);
            }
        }
        if (hitboxRes != null && owner instanceof net.minecraft.world.entity.player.Player player && entity instanceof AbstractVehicle targetVehicle) {
            RVP_VehicleHitboxFactorManager.INSTANCE.maybeSendHitboxDebug(
                    player, targetVehicle, preHitboxDamage, finalDamage, hitboxRes,
                    Float.NaN, 1f
            );
        }
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.invulnerableTime = 0;
        }
    }

    protected float distanceDecayFactor() {
        List<RVP_DamageDecayRuleData> rules = damageDecayRules();
        if (rules.isEmpty()) {
            return 1f;
        }
        return RVP_DamageDecayUtil.distanceFactor(rules, decaySampleDistanceM());
    }

    protected float incidenceDecayFactor() {
        List<RVP_DamageDecayRuleData> rules = damageDecayRules();
        if (rules.isEmpty()) {
            return 1f;
        }
        return RVP_DamageDecayUtil.angleFactor(rules, lastImpactIncidenceAngleDeg);
    }

    protected void rememberImpactIncidence(Vec3 velocity, Vec3 surfaceNormal) {
        lastImpactIncidenceAngleDeg = RVP_BounceUtil.incidenceAngleDegrees(velocity, surfaceNormal);
    }

    protected float impactDecayFactor() {
        return distanceDecayFactor() * incidenceDecayFactor();
    }

    protected float computeDamage(boolean headshot) {
        float base = headshot ? damage * this.headShot : damage;
        return base * impactDecayFactor();
    }

    /** Damage scale from prior penetrations: mult^penetrationEventCount (first hit = full damage). */
    protected float penetrationDamageFactor() {
        if (penetrationDamageMultiplier >= 0.999f || penetrationEventCount <= 0) {
            return 1f;
        }
        return (float) Math.pow(penetrationDamageMultiplier, penetrationEventCount);
    }

    protected void applyPenetrationSpeedDecay() {
        if (penetrationSpeedMultiplier >= 0.999f) {
            return;
        }
        Vec3 velocity = getDeltaMovement().scale(penetrationSpeedMultiplier);
        setDeltaMovement(velocity);
        flightSpeed = Math.max(velocity.length(), 0.01);
        applyRotationFromVelocity(velocity);
    }

    /** Returns true if bounce fuse detonated/discarded this tick. */
    protected boolean tickBounceFuse() {
        if (bounceFuseCountdown <= 0) {
            return false;
        }
        bounceFuseCountdown--;
        if (bounceFuseCountdown == 0) {
            explodeAndDiscard(position());
            return true;
        }
        return false;
    }

    protected boolean isBounceEnabledForEntity(Entity entity) {
        if (!(entity instanceof AbstractVehicle)) {
            return false;
        }
        RVP_WeaponData config = resolveWeaponConfig();
        return config != null ? config.isBounceOnVehicle() : bounceOnVehicle;
    }

    /**
     * Restore bounce budget when spawn used stale weapon data but {@link #damageDecayRules()} later synced.
     */
    protected void syncBounceBudgetFromConfig() {
        if (bounceLeft > 0 || bouncesConsumed > 0) {
            return;
        }
        RVP_WeaponData config = resolveWeaponConfig();
        if (config == null || config.getBounce() <= 0) {
            return;
        }
        bounceLeft = config.getBounce();
        bounceStrength = config.getBounceStrength();
        bounceFuseTick = config.getBounceFuseTick();
        bounceIncidenceAngleMin = config.getBounceIncidenceAngle();
        bounceOnVehicle = config.isBounceOnVehicle();
    }

    /**
     * Apply ricochet when incidence angle and target type allow it. Safe on client for prediction.
     */
    protected boolean tryApplyBounce(Vec3 hitLocation, Vec3 surfaceNormal) {
        syncBounceBudgetFromConfig();
        if (bounceLeft <= 0 || getDeltaMovement().lengthSqr() <= 0.05) {
            return false;
        }
        RVP_WeaponData config = resolveWeaponConfig();
        float minIncidence = config != null ? config.getBounceIncidenceAngle() : bounceIncidenceAngleMin;
        float strength = config != null ? config.getBounceStrength() : bounceStrength;
        Vec3 velocity = getDeltaMovement();
        if (!RVP_BounceUtil.meetsIncidenceThreshold(velocity, surfaceNormal, minIncidence)) {
            return false;
        }
        Vec3 normal = surfaceNormal.normalize();
        Vec3 reflected = RVP_BounceUtil.reflect(velocity, normal, strength);
        bounceLeft--;
        bouncesConsumed++;
        if (!level().isClientSide() && bounceFuseTick > 0 && bounceFuseCountdown < 0) {
            bounceFuseCountdown = bounceFuseTick;
        }
        setPos(hitLocation.add(normal.scale(0.08)));
        setDeltaMovement(reflected);
        applyRotationFromVelocity(reflected);
        return true;
    }

    protected boolean tryBounceFromBlockHit(BlockHitResult result) {
        BlockPos blockPos = result.getBlockPos();
        BlockState blockState = level().getBlockState(blockPos);
        RVP_WeaponData config = resolveWeaponConfig();
        float minHardness = config != null
                ? config.getBounceMinBlockHardness()
                : RVP_CollisionData.DEFAULT_BOUNCE_MIN_BLOCK_HARDNESS;
        if (!RVP_WallPenetrationUtil.canBlockBounce(blockState, level(), blockPos, minHardness)) {
            return false;
        }
        Vec3 normal = Vec3.atLowerCornerOf(result.getDirection().getNormal());
        if (!tryApplyBounce(result.getLocation(), normal)) {
            return false;
        }
        onBounceApplied();
        return true;
    }

    protected boolean tryBounceFromEntityHit(BulletHitResult result) {
        Entity entity = result.getEntity();
        if (!isBounceEnabledForEntity(entity)) {
            return false;
        }
        Vec3 velocity = getDeltaMovement();
        Vec3 normal = RVP_BounceUtil.impactNormal(
                entity, collisionSegmentStart(), collisionSegmentEnd(), result.getLocation(), velocity);
        if (!tryApplyBounce(result.getLocation(), normal)) {
            return false;
        }
        onBounceApplied();
        return true;
    }

    /** Hook after a bounce; resets rotation lerp anchors on both sides. */
    protected void onBounceApplied() {
        yRotO = getYRot();
        xRotO = getXRot();
    }

    protected boolean canDamageEntity(Entity entity) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        if (entity == vehicle) {
            return false;
        }
        return vehicle == null || !vehicle.getPassengers().contains(entity);
    }

    protected boolean checkShooterValid() {
        if (shooterVehicle == null && getOwner() == null) {
            return false;
        }
        if (shooterVehicle != null && !shooterVehicle.isAlive()) {
            return false;
        }
        Entity shooter = getOwner() != null ? getOwner() : shooterVehicle;
        if (shooter == null) {
            return true;
        }
        double dx = getX() - shooter.getX();
        double dz = getZ() - shooter.getZ();
        return dx * dx + dz * dz < 3.38724E7D;
    }

    protected void applyDetonateAt(Vec3 pos, @org.jetbrains.annotations.Nullable BlockHitResult blockHit,
                                   boolean blockImpact) {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel serverLevel) || rvpData == null) {
            return;
        }
        if (!rvpData.getDetonateData().hasAnyEffect()) {
            return;
        }
        RVP_DetonateApplier.apply(serverLevel, pos, blockHit, rvpData.getDetonateData(),
                getOwner(), shooterVehicle, blockImpact);
    }

    /**
     * 按 {@link org.ywzj.rvp.weapon.data.RVP_DetonateData#isEffectsBeforeExplosion()} 顺序触发自定义落点效果与爆炸。
     */
    protected void resolveImpactDetonation(Vec3 pos, @org.jetbrains.annotations.Nullable BlockHitResult blockHit,
                                           boolean blockImpact) {
        if (rvpData == null) {
            triggerExplosion(pos);
            return;
        }
        org.ywzj.rvp.weapon.data.RVP_DetonateData detonate = rvpData.getDetonateData();
        boolean applyDispenser = shouldApplyDispenser() && !dispenserOnlyImpact();
        if (detonate.isEffectsBeforeExplosion()) {
            if (applyDispenser) {
                applyDispenserAt(pos, blockHit);
            }
            applyDetonateAt(pos, blockHit, blockImpact);
            triggerExplosion(pos);
        } else {
            triggerExplosion(pos);
            if (applyDispenser) {
                applyDispenserAt(pos, blockHit);
            }
            applyDetonateAt(pos, blockHit, blockImpact);
        }
    }

    protected enum FuseDetonation {
        NORMAL,
        AIRBURST,
        PROXIMITY
    }

    protected void triggerExplosion(Vec3 pos) {
        triggerExplosion(pos, FuseDetonation.NORMAL, null);
    }

    protected void triggerExplosion(Vec3 pos, FuseDetonation kind) {
        triggerExplosion(pos, kind, null);
    }

    /**
     * @param excludeEntity 如果非空，该实体将不会受到 {@link VehicleExplosion} 伤害（已通过近炸直伤扣血，避免重复）。
     */
    protected void triggerExplosion(Vec3 pos, FuseDetonation kind, @Nullable Entity excludeEntity) {
        if (explosion == null || !explosion.explode) {
            return;
        }
        float damage = explosion.damage;
        float radius = explosion.radius;
        if (rvpData != null) {
            damage = switch (kind) {
                case AIRBURST -> rvpData.resolveAirburstExplosionDamage();
                case PROXIMITY -> rvpData.resolveProximityFuseExplosionDamage();
                default -> explosion.damage;
            };
            if (kind == FuseDetonation.NORMAL && !Float.isNaN(lastImpactIncidenceAngleDeg)) {
                damage *= impactDecayFactor();
            }
            radius = switch (kind) {
                case AIRBURST -> rvpData.resolveAirburstExplosionRadius();
                case PROXIMITY -> rvpData.resolveProximityFuseExplosionRadius();
                default -> explosion.radius;
            };
        }
        VehicleExplosion ex = new VehicleExplosion(level(), getOwner(), vehicle, pos,
                radius, damage, explosion.destroyBlock);
        if (excludeEntity != null) {
            ex.explode(Collections.singletonList(excludeEntity));
        } else {
            ex.explode();
        }
        if (level() instanceof ServerLevel serverLevel && rvpData != null) {
            RVP_ProjectileParticleEffects.spawnExplosion(
                    serverLevel, pos, rvpData.getEffectsData(), radius);
        }
        // 机制二B：非直击爆炸 — 对爆炸范围内的其他载具按距离衰减破坏 ERA
        if (!level().isClientSide() && radius > 5f) {
            double half = radius;
            AABB eraBox = new AABB(
                    pos.x - half, pos.y - half, pos.z - half,
                    pos.x + half, pos.y + half, pos.z + half);
            for (AbstractVehicle v : level().getEntitiesOfClass(AbstractVehicle.class, eraBox)) {
                if (v.isDestroyed() || directHitVehicleIds.contains(v.getId())) {
                    continue;
                }
                RVP_VehicleHitboxFactorManager.destroyEraByExplosionRadius(
                        v, radius, pos, false);
            }
            directHitVehicleIds.clear();
        }
    }

    protected void detonateFuseAt(Vec3 pos, FuseDetonation kind) {
        detonateFuseAt(pos, kind, null);
    }

    protected void detonateFuseAt(Vec3 pos, FuseDetonation kind, @Nullable Entity proximityTarget) {
        if (trySubmunitionTrigger(RVP_EnumSubmunitionTrigger.ON_FUSE)) {
            discard();
            return;
        }
        boolean hadGuaranteedDamage = false;
        if (kind == FuseDetonation.PROXIMITY && proximityTarget != null && rvpData != null) {
            // 强制对触发近炸的目标造成全额爆炸伤害，不依赖 VehicleExplosion 距离衰减（修复高速目标炸不到的 bug）
            float guaranteed = rvpData.getProximityFuseDirectDamage();
            if (guaranteed <= 0f) {
                guaranteed = rvpData.resolveProximityFuseExplosionDamage();
            }
            if (guaranteed <= 0f && explosion != null) {
                guaranteed = explosion.damage;
            }
            if (guaranteed > 0f) {
                guaranteed = RVP_DamageApplier.applyScaled(guaranteed, proximityTarget, rvpData);
                DamageSource source = AllDamageTypes.Sources.explosion(
                        level().registryAccess(), this, getOwner(), pos);
                proximityTarget.hurt(source, guaranteed);
                hadGuaranteedDamage = true;
            }
        }
        // 已吃全额近炸的目标排除在 VehicleExplosion 之外，避免二次伤害
        Entity exclude = hadGuaranteedDamage ? proximityTarget : null;
        if (rvpData == null) {
            triggerExplosion(pos, FuseDetonation.NORMAL, exclude);
            discard();
            return;
        }
        org.ywzj.rvp.weapon.data.RVP_DetonateData detonate = rvpData.getDetonateData();
        boolean applyDispenser = shouldApplyDispenser() && !dispenserOnlyImpact();
        if (detonate.isEffectsBeforeExplosion()) {
            if (applyDispenser) {
                applyDispenserAt(pos, lastBlockHit);
            }
            applyDetonateAt(pos, null, false);
            triggerExplosion(pos, kind, exclude);
        } else {
            triggerExplosion(pos, kind, exclude);
            if (applyDispenser) {
                applyDispenserAt(pos, lastBlockHit);
            }
            applyDetonateAt(pos, null, false);
        }
        discard();
    }

    protected void explodeAndDiscard(Vec3 pos) {
        if (dispenserOnlyImpact()) {
            applyDispenserAt(pos, lastBlockHit);
            discard();
            return;
        }
        detonateFuseAt(pos, FuseDetonation.NORMAL);
    }

    protected boolean shouldApplyDispenser() {
        if (rvpData == null) {
            return false;
        }
        RVP_DispenserPayloadData payload = rvpData.getDispenserData();
        return payload.hasItem() && payload.isPlaceOnImpact();
    }

    /**
     * {@code rvp:dispenser} with {@code dispenser_data} places items only (no explosion/detonate chain).
     */
    protected boolean dispenserOnlyImpact() {
        return shouldApplyDispenser() && weaponKind == RVP_EnumWeaponKind.DISPENSER;
    }

    protected int applyDispenserAt(Vec3 pos, @Nullable BlockHitResult blockHit) {
        if (level().isClientSide() || rvpData == null || !shouldApplyDispenser()) {
            return 0;
        }
        if (!(level() instanceof ServerLevel serverLevel)) {
            return 0;
        }
        RVP_DispenserPayloadData payload = rvpData.getDispenserData();
        if (blockHit != null) {
            return RVP_DispenserPlacement.placeAtHit(serverLevel, blockHit, payload, getOwner());
        }
        return RVP_DispenserPlacement.placeAtPosition(serverLevel, pos, payload, getOwner(), lastBlockHit);
    }

    public void applyRotationFromVelocity(Vec3 velocity) {
        RVP_ProjectileMotion.applyRotationFromVelocity(this, velocity);
    }

    void applyCannonFacingFromVelocity(Vec3 velocity, boolean lerp) {
        if (velocity.lengthSqr() <= 1.0E-8) {
            return;
        }
        double horizontal = velocity.horizontalDistance();
        float targetYRot = (float) Math.toDegrees(Mth.atan2(velocity.x, velocity.z));
        float targetXRot = (float) Math.toDegrees(Mth.atan2(velocity.y, horizontal));
        if (!lerp) {
            setYRot(targetYRot);
            setXRot(targetXRot);
            return;
        }
        if (xRotO == 0.0F && yRotO == 0.0F) {
            yRotO = targetYRot;
            xRotO = targetXRot;
        }
        setYRot(targetYRot);
        setXRot(targetXRot);
        setXRot(lerpRotation(xRotO, getXRot()));
        setYRot(lerpRotation(yRotO, getYRot()));
    }

    void applySpawnAimRot(AimRot aim) {
        setYRot(aim.yRot());
        setXRot(aim.xRot());
    }

    protected Vec3 aimPoint(Entity entity) {
        return entity.position().add(0, entity.getBbHeight() * 0.5, 0);
    }

    protected void spawnTrailParticles() {
        boolean heavy = isHeavyProjectile();
        boolean motorBurning = isMotorBurning();
        // 熄火后不再产生尾焰轨迹（客户端 isMotorPropulsion 始终 false，但 motorBurning 通过同步值正确判断）
        if (!motorBurning) {
            return;
        }
        // 对标本体 MissileEntity.tickParticle：弹体后方 3 格，两帧间分段插值形成连续烟柱
        Vec3 pos = this.position().add(this.getLookAngle().scale(-3));
        if (particlePosO == null) {
            particlePosO = pos;
        }
        Vec3 step = pos.subtract(particlePosO);
        double dist = step.length();
        int segments = (int) (dist / 0.5);
        Vec3 dir = step.normalize();
        // 用户可以自由配置 trajectory_particle 选择烟的类型，设为 "none" 可关闭
        // rvpData 可能在客户端为 null，此时用 CAMPFIRE_SIGNAL_SMOKE 作为保底
        String configured = "";
        if (rvpData != null) {
            configured = rvpData.getEffectsData().getTrajectoryParticle();
        }
        ParticleOptions primary = resolveParticle(configured, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE);
        if (primary != null) {
            for (int i = 0; i <= segments; i++) {
                Vec3 particlePos = particlePosO.add(dir.scale(i * 0.5));
                level().addParticle(primary, true,
                        particlePos.x, particlePos.y, particlePos.z,
                        0.0D, 0.0D, 0.0D);
            }
        }
        // 火焰粒子（仅推进类弹体燃烧期产生，类比本体，但本体没有火焰）
        if (motorBurning && tickCount % 2 == 0) {
            level().addParticle(ParticleTypes.FLAME, true,
                    getX(), getY(), getZ(),
                    -getDeltaMovement().x * 0.02, -getDeltaMovement().y * 0.02, -getDeltaMovement().z * 0.02);
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true,
                    getX(), getY(), getZ(),
                    -getDeltaMovement().x * 0.01, 0.05, -getDeltaMovement().z * 0.01);
        }
        particlePosO = pos;
    }

    protected boolean isHeavyProjectile() {
        return this instanceof RVP_MissileEntity || this instanceof RVP_RocketEntity
                || this instanceof RVP_BombEntity || this instanceof RVP_DispensedEntity;
    }

    /** 是否有火箭推进发动机（推力弹道）。 */
    protected boolean isMotorPropulsion() {
        return rvpData != null && rvpData.usesPropulsion();
    }

    /** 发动机当前是否在燃烧期内（对标本体 MissileEntity.tickParticle）。非推进弹体始终返回 true。 */
    protected boolean isMotorBurning() {
        // 客户端 rvpData 为 null，用生成数据包同步的 motorBurnEndTick
        if (rvpData == null) {
            return tickCount <= motorBurnEndTick;
        }
        if (!isMotorPropulsion()) {
            return true;
        }
        int ignition = rvpData.getResolvedIgnitionDelayTick();
        float burnTime = rvpData.getResolvedMotorBurnTime();
        int motorTick = tickCount - ignition;
        return motorTick >= 0 && motorTick <= burnTime;
    }

    protected void broadcastTrailParticles() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        String configured = rvpData.getEffectsData().getTrajectoryParticle();
        boolean heavy = isHeavyProjectile();
        boolean motorBurning = isMotorBurning();
        // 轨迹粒子：推进类弹体仅在燃烧期发送
        if ((!heavy || motorBurning) || !isMotorPropulsion()) {
            ParticleOptions primary = resolveParticle(configured,
                    heavy ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE);
            if (primary != null) {
                double spread = heavy ? 0.1 : 0.05;
                int count = heavy ? 3 : 1;
                serverLevel.sendParticles(primary, getX(), getY(), getZ(), count, spread, spread, spread, 0.01);
            }
        }
        // 尾焰：仅燃烧期
        if (heavy && motorBurning) {
            serverLevel.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 1, 0.03, 0.03, 0.03, 0.002);
        }
    }

    /** Base block-hit VFX from {@link AmmoEntity#tickHit()} plus optional RVP impact particles. */
    protected void spawnAmmoBlockImpactEffects(BlockHitResult result) {
        if (level() instanceof ServerLevel serverLevel) {
            if (rvpData != null) {
                RVP_ProjectileParticleEffects.spawnBlockImpact(
                        serverLevel, result, rvpData.getEffectsData(), getBbWidth());
            }
            BlockPos hitPos = result.getBlockPos();
            BlockState hitBlock = level().getBlockState(hitPos);
            Vec3 normal = Vec3.atLowerCornerOf(result.getDirection().getNormal());
            Vec3 loc1 = result.getLocation().add(normal.scale(0.3));
            Vec3 loc2 = result.getLocation().add(normal.scale(0.01));
            BlockParticleOption option = new BlockParticleOption(ParticleTypes.BLOCK, hitBlock);
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceToSqr(loc1) < 128 * 128) {
                    serverLevel.sendParticles(player, option, true,
                            loc1.x, loc1.y, loc1.z,
                            5, 0, 0, 0, 0.1);
                    BulletHoleOption bulletHole = new BulletHoleOption(
                            result.getDirection(), hitPos, 1, 0, 0, getCaliber());
                    serverLevel.sendParticles(player, bulletHole, true,
                            loc2.x, loc2.y, loc2.z,
                            1, 0, 0, 0, 0);
                }
            }
        }
    }

    private static ParticleOptions resolveParticle(String id, ParticleOptions fallback) {
        if (id == null || id.isBlank()) {
            return fallback;
        }
        return switch (id) {
            case "none", "minecraft:none" -> null;
            case "flame", "minecraft:flame" -> ParticleTypes.FLAME;
            case "large_smoke", "minecraft:large_smoke" -> ParticleTypes.LARGE_SMOKE;
            case "cloud", "minecraft:cloud" -> ParticleTypes.CLOUD;
            case "lava", "minecraft:lava" -> ParticleTypes.LAVA;
            case "campfire_smoke", "minecraft:campfire_cosy_smoke" -> ParticleTypes.CAMPFIRE_COSY_SMOKE;
            case "campfire_signal_smoke", "minecraft:campfire_signal_smoke" -> ParticleTypes.CAMPFIRE_SIGNAL_SMOKE;
            case "explosion", "minecraft:explosion" -> ParticleTypes.EXPLOSION;
            case "explosion_emitter", "minecraft:explosion_emitter" -> ParticleTypes.EXPLOSION_EMITTER;
            case "smoke", "minecraft:smoke" -> ParticleTypes.SMOKE;
            case "block", "minecraft:block" -> fallback;
            default -> fallback;
        };
    }

    public record AimRot(float xRot, float yRot) {}

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        super.writeSpawnData(buffer);
        buffer.writeFloat(getXRot());
        buffer.writeFloat(getYRot());
        buffer.writeDouble(getDeltaMovement().x);
        buffer.writeDouble(getDeltaMovement().y);
        buffer.writeDouble(getDeltaMovement().z);
        buffer.writeDouble(flightSpeed);
        buffer.writeVarInt(motorBurnEndTick);
        buffer.writeVarInt(targetEntity != null ? targetEntity.getId() : 0);
        buffer.writeBoolean(targetPos != null);
        if (targetPos != null) {
            buffer.writeDouble(targetPos.x);
            buffer.writeDouble(targetPos.y);
            buffer.writeDouble(targetPos.z);
        }
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        super.readSpawnData(buffer);
        setXRot(buffer.readFloat());
        setYRot(buffer.readFloat());
        setDeltaMovement(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        this.flightSpeed = buffer.readDouble();
        this.motorBurnEndTick = buffer.readVarInt();
        yRotO = getYRot();
        xRotO = getXRot();
        int id = buffer.readVarInt();
        if (id > 0) {
            Entity e = level().getEntity(id);
            if (e != null) {
                this.targetEntity = e;
            }
        }
        if (buffer.readBoolean()) {
            this.targetPos = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            this.lastGuidancePos = this.targetPos;
        }
    }
}
