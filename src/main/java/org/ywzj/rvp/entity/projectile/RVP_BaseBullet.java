package org.ywzj.rvp.entity.projectile;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
import net.minecraftforge.entity.PartEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceController;
import org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug;
import org.ywzj.rvp.debug.RVP_TopAttackDebug;
import org.ywzj.rvp.weapon.core.RVP_ProjectileSpawner;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.vehicle.util.VehicleExplosion;
import org.ywzj.rvp.weapon.util.RVP_BounceUtil;
import org.ywzj.rvp.weapon.util.RVP_WallPenetrationUtil;
import org.ywzj.rvp.weapon.damage.RVP_DamageApplier;
import org.ywzj.rvp.network.RVP_BulletHitDebugNetworking;
import org.ywzj.rvp.physics.RVP_PhysicsOnlyCollisionHelper;
import org.ywzj.rvp.weapon.util.RVP_DamageDecayUtil;
import org.ywzj.rvp.weapon.damage.RVP_DecayContext;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxRuntimeAccess;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidancePhaseState;
import org.ywzj.rvp.guidance.RVP_GuidanceModelResolver;
import org.ywzj.rvp.weapon.data.RVP_CollisionData;
import org.ywzj.rvp.weapon.data.RVP_DamageDecayRuleData;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.data.RVP_FuseData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore;
import org.ywzj.rvp.weapon.data.RVP_DispenserPayloadData;
import org.ywzj.rvp.weapon.effects.RVP_DetonateApplier;
import org.ywzj.rvp.weapon.effects.RVP_DispenserPlacement;
import org.ywzj.rvp.weapon.effects.RVP_HbmEffectBridge;
import org.ywzj.rvp.weapon.effects.RVP_ExplosionVisualSuppression;
import org.ywzj.rvp.weapon.effects.RVP_ProjectileParticleEffects;
import org.ywzj.rvp.weapon.data.RVP_EnumSubmunitionTrigger;
import org.ywzj.rvp.weapon.submunition.RVP_SubmunitionRunner;
import org.ywzj.rvp.util.RVP_RadarContactHelper;
import org.ywzj.rvp.util.RVP_ChunkPathLoader;
import org.ywzj.rvp.util.RVP_ChunkPathLoadManager;
import org.ywzj.vehicle.all.AllDamageTypes;
import org.ywzj.vehicle.api.entity.RemoteTickEntity;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.AmmoEntity;
import org.ywzj.vehicle.particle.BulletHoleOption;
import org.ywzj.vehicle.util.BulletHitResult;
import org.ywzj.vehicle.util.EntityUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Common runtime base for all new RVP projectile entities.
 *
 * <p>It owns generic projectile mechanics: kinematics, fuses, proximity,
 * submunition timing, impact effects, penetration, ricochet and damage falloff.
 * Guidance-specific motion changes are delegated to {@link RVP_GuidanceController}.</p>
 */
public abstract class RVP_BaseBullet extends AmmoEntity implements RemoteTickEntity {

    private static final double PARTICLE_VIEW_DISTANCE = 512.0D;
    private static final double PARTICLE_VIEW_DISTANCE_SQ = PARTICLE_VIEW_DISTANCE * PARTICLE_VIEW_DISTANCE;
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 弹体每 Tick 请求的未来路径窗口。
     * distance = sqrt(motion.x² + motion.z²) * PROJECTILE_CHUNK_HORIZON_TICKS */
    private static final int PROJECTILE_CHUNK_HORIZON_TICKS = 5;
    /** 区块持续无法进入 entity-ticking 时的安全等待上限。 */
    private static final int MAX_PROJECTILE_CHUNK_WAIT_TICKS = 200;

    static final EntityDataAccessor<Integer> DATA_SECOND_PULSE_START_TICK =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Integer> DATA_SECOND_PULSE_BURN_TIME_TICK =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.INT);
    static final EntityDataAccessor<Boolean> DATA_ACTIVE_RADAR_ON =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.BOOLEAN);
    static final EntityDataAccessor<Boolean> DATA_ACTIVE_RADAR_CATCH =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.BOOLEAN);
    /** 服务端区块等待状态；同步给客户端以暂停尾迹和飞行时钟。 */
    static final EntityDataAccessor<Boolean> DATA_CHUNK_WAITING =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.BOOLEAN);
    /** 已暂停的飞行 Tick 总数；原生 tickCount 仍随世界 Tick 增长。 */
    static final EntityDataAccessor<Integer> DATA_CHUNK_WAIT_TOTAL =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.INT);

    /** 线导视觉线（effects_data.wire_link_enabled）同步字段，客户端渲染器读取。 */
    public static final EntityDataAccessor<Boolean> DATA_WIRE_ENABLED =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_X =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_Y =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_Z =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_PREV_X =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_PREV_Y =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_PREV_Z =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    /** 线导锚点所在载具的实体 ID（客户端据此找到渲染中的载具，重建锚点消除相位差抖动）。 */
    public static final EntityDataAccessor<Integer> DATA_WIRE_VEHICLE_ID =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.INT);
    /** 线导锚点在载具本地坐标系的偏移（当前/上一 tick），客户端配合载具渲染变换重建世界锚点。 */
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_LOCAL_X =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_LOCAL_Y =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_LOCAL_Z =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_LOCAL_PREV_X =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_LOCAL_PREV_Y =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> DATA_WIRE_PIVOT_LOCAL_PREV_Z =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Boolean> DATA_WIRE_ACTIVE =
            SynchedEntityData.defineId(RVP_BaseBullet.class, EntityDataSerializers.BOOLEAN);

    protected RVP_WeaponData rvpData;
    /** Snapshot of {@code collision_data.damage_decay} at spawn (decoupled from shared weapon index data). */
    private List<RVP_DamageDecayRuleData> damageDecayRules = List.of();
    protected RVP_EnumWeaponKind weaponKind = RVP_EnumWeaponKind.ROCKET;
    protected AbstractVehicle shooterVehicle;
    protected WeaponUnit shooterWeaponUnit;
    /** 实际发射单元（导弹所在的武器站部件），线导起点跟随其出膛管口。 */
    protected WeaponUnit launchWeaponUnit;
    /** 发射时所用的 bolt 索引（-1 表示未匹配），线缆固定在该发射管口，避免轮射后跳到另一管。 */
    protected int wireBoltIndex = -1;
    /** 线导视觉线的发射枢轴世界坐标（服务端每 tick 更新，随武器站枢轴移动）。 */
    protected Vec3 wirePivot = Vec3.ZERO;
    /** 线导视觉线上一 tick 的发射枢轴世界坐标（客户端渲染 partialTick 插值用，消除 20Hz 同步的跳变抖动）。 */
    protected Vec3 wirePivotPrev = Vec3.ZERO;
    /**
     * 匹配失败时的兜底：发射时刻出膛管口相对武器站枢轴的局部偏移。
     * 每 tick 用 枢轴 + 该偏移 跟随武器站，保证线起点稳定在这发导弹实际出膛的管口附近，
     * 绝不跳到另一根管（避免双线/扇面跳变）。
     */
    protected Vec3 wirePivotBase = Vec3.ZERO;
    /** 线导锚点在载具本地坐标系的偏移（服务端每 tick 更新），客户端配合渲染中的载具变换重建锚点，消除运动相位差抖动。 */
    protected Vec3 wirePivotLocal = Vec3.ZERO;
    /** 线导锚点本地偏移上一 tick 值（客户端 partialTick 插值用）。 */
    protected Vec3 wirePivotLocalPrev = Vec3.ZERO;
    protected int coldLaunchTimeTick;
    protected Vec3 coldLaunchVelocity = new Vec3(0, -1, 0);
    protected double flightSpeed;
    /** Official cannon-style linear friction (machinegun only). */
    protected float cannonFriction = 0.01f;
    /** Official cannon-style positive-down gravity per tick (machinegun only). */
    protected float cannonGravity;
    protected int updateCount;
    /** 攻顶引信已探测到目标并进入延时的 tick（updateCount）；-1 = 未触发。 */
    protected int topAttackTriggerTick = -1;
    /** 攻顶引信触发的子母弹生成位置覆盖（目标正上方，探测时刻导弹高度）；null = 用导弹当前位置。 */
    @Nullable
    protected Vec3 topAttackSpawnPosition;
    /** 可编程空爆测距（米），来自 MCH {@code airburstDist}。 */
    protected int airburstDist;
    protected double airburstTravelled;
    protected boolean airburstTriggered;
    /** Segment used by this tick's hit-scan before motion; programmable airburst must use the same segment. */
    protected Vec3 programmableAirburstSegmentStart = Vec3.ZERO;
    protected Vec3 programmableAirburstSegmentEnd = Vec3.ZERO;
  @Nullable
    protected RVP_SubmunitionRunner submunitionRunner;
    /** Child projectiles increment depth; blocks chains beyond {@link org.ywzj.rvp.weapon.submunition.RVP_SubmunitionSpawner#MAX_DEPTH}. */
    protected int submunitionDepth;
    protected int livingPenetrationLeft;
    protected int wallPenetrationLeft;
    protected final Set<Integer> piercedLivingIds = new HashSet<>();
    /** 直击命中的目标 ID（近炸与直击互斥：同一目标二选一，避免双倍伤害）。 */
    protected final Set<Integer> directHitIds = new HashSet<>();
    /** 已吃近炸全额伤害的目标 ID（近炸触发即全额，即使目标已逃出范围；且不重复触发/叠加）。 */
    protected final Set<Integer> proximityDamagedIds = new HashSet<>();
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
    /** True when launch captured an entity lock snapshot; prevents accidental post-launch retargeting. */
    protected boolean launchTargetSnapshot;
    /** IR seeker temporary retain window for brief off-axis loss. */
    protected int irSeekerGraceUntilTick = Integer.MIN_VALUE;
    private boolean irSeekerLossGraceStarted;
    private boolean terminalIrTargetAcquired;
    protected final Map<Long, Integer> radiationPulseTickMap = new HashMap<>();
    protected int antiRadiationNextScanTick;
    protected int antiRadiationMemoryLeftTick;
    protected boolean antiRadiationLostPermanent;
    protected boolean antiRadiationSignalAcquired;

    /** 发动机熄火的 tick 数（服务端计算，通过生成数据包同步到客户端，解决 rvpData null 时持续出烟的问题）。 */
    protected int motorBurnEndTick = Integer.MAX_VALUE;
    protected int secondPulseStartTick = -1;
    /** 是否在 HUD 显示 MSL 指示器，从 weapon data 同步到客户端。 */
    protected boolean showMslIndicator;

    /** 信号尺寸：0=不可被雷达/红外探测；>0 替代碰撞箱尺寸用于探测过滤及 RCS 倍率。 */
    protected float signatureSize = 0f;

    /** 本 tick 内直击命中的载具 ID 集合，用于区分 HE 直击与非直击爆炸的 ERA 破坏。 */
    protected final java.util.Set<Integer> directHitVehicleIds = new java.util.HashSet<>();

    /** 尾焰粒子上帧位置（对标本体 MissileEntity.particlePosO）。 */
    @Nullable
    protected Vec3 particlePosO;
    protected int trailParticleTickO = Integer.MIN_VALUE;
    protected boolean trailMotorBurningO;

    /** 本弹体已广播过轨迹粒子的次数（命中补渲开关判断是否从未出过轨迹）。 */
    protected int trailBroadcastCount;

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
    @Nullable
    private ResourceLocation remoteWeaponId;
    private int remoteOwnerId = -1;
    private int remoteShooterVehicleId = -1;
    @Nullable
    private Vec3 gpsTargetOffset;
    private boolean gpsTargetOffsetResolved;
    private boolean gpsCruiseVerticalResetApplied;
    @Nullable
    private Vec3 topAttackLaunchPos;
    @Nullable
    private Vec3 topAttackInitialTargetPos;
    @Nullable
    private Vec3 topAttackApexPos;
    private boolean topAttackApexReached;

    protected int guidanceStageIndex = -1;
    protected int guidanceStageEnteredTick;
    protected final java.util.Map<Integer, Integer> guidanceStageEnteredTicks = new java.util.HashMap<>();
    protected int guidanceOverlapResolveIndex = -1;
    protected final java.util.Set<Integer> guidanceStickyPhaseIndices = new java.util.HashSet<>();
    protected final RVP_GuidancePhaseState guidancePhaseState = new RVP_GuidancePhaseState();

    /** ===== SACLOS 半自动修正状态（世界坐标 3D 向量，避免 perp 基旋转导致画圆） ===== */
    public Vec3 saclosOffsetVec = Vec3.ZERO;
    public Vec3 saclosVelVec = Vec3.ZERO;
    public Vec3 saclosLastPhysVec = Vec3.ZERO;

    @Nullable
    protected RVP_EnumGuidanceType activeSourceType;
    @Nullable
    protected String activeStageName;
    /** Set when MCLOS {@code take_over_motion} applied wire-direct steering this tick. */
    private boolean guidanceWireDirectApplied;
    /** 当前一次连续区块等待已经实际暂停的 Tick 数。 */
    private int chunkWaitTicks;

    public RVP_BaseBullet(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
        this.keepChunkLoaded = false;
    }

    public RVP_BaseBullet(EntityType<? extends Projectile> type, Level level) {
        this(type, level, null);
    }

    @Override
    public void writeData(CompoundTag data) {
        ResourceLocation weaponId = getWeaponId();
        if (weaponId != null) {
            data.putString("weaponId", weaponId.toString());
        }
        data.putString("weaponKind", getWeaponKind().name());
        if (getOwner() != null) {
            data.putInt("ownerId", getOwner().getId());
        }
        if (shooterVehicle != null) {
            data.putInt("shooterVehicleId", shooterVehicle.getId());
        }
        writeRemoteVec3(data, "targetPos", targetPos);
        writeRemoteVec3(data, "lastGuidancePos", lastGuidancePos);
        writeRemoteVec3(data, "gpsTargetOffset", gpsTargetOffset);
        writeRemoteVec3(data, "topAttackLaunchPos", topAttackLaunchPos);
        writeRemoteVec3(data, "topAttackInitialTargetPos", topAttackInitialTargetPos);
        writeRemoteVec3(data, "topAttackApexPos", topAttackApexPos);
        data.putBoolean("topAttackApexReached", topAttackApexReached);
        data.putInt("coldLaunchTimeTick", coldLaunchTimeTick);
        writeRemoteVec3(data, "coldLaunchVelocity", coldLaunchVelocity);
        data.putBoolean("launchTargetSnapshot", launchTargetSnapshot);
        data.putInt("irSeekerGraceUntilTick", irSeekerGraceUntilTick);
        data.putBoolean("terminalIrTargetAcquired", terminalIrTargetAcquired);
    }

    @Override
    public void readData(CompoundTag data) {
        if (data.contains("weaponId")) {
            ResourceLocation weaponId = ResourceLocation.tryParse(data.getString("weaponId"));
            if (weaponId != null) {
                remoteWeaponId = weaponId;
            }
        }
        if (data.contains("weaponKind")) {
            try {
                this.weaponKind = RVP_EnumWeaponKind.valueOf(data.getString("weaponKind"));
            } catch (IllegalArgumentException ignored) {
                this.weaponKind = RVP_EnumWeaponKind.ROCKET;
            }
        }
        remoteOwnerId = data.contains("ownerId") ? data.getInt("ownerId") : -1;
        remoteShooterVehicleId = data.contains("shooterVehicleId") ? data.getInt("shooterVehicleId") : -1;
        targetPos = readRemoteVec3(data, "targetPos");
        lastGuidancePos = readRemoteVec3(data, "lastGuidancePos");
        gpsTargetOffset = readRemoteVec3(data, "gpsTargetOffset");
        topAttackLaunchPos = readRemoteVec3(data, "topAttackLaunchPos");
        topAttackInitialTargetPos = readRemoteVec3(data, "topAttackInitialTargetPos");
        topAttackApexPos = readRemoteVec3(data, "topAttackApexPos");
        topAttackApexReached = data.getBoolean("topAttackApexReached");
        coldLaunchTimeTick = Math.max(data.getInt("coldLaunchTimeTick"), 0);
        Vec3 readColdLaunchVelocity = readRemoteVec3(data, "coldLaunchVelocity");
        coldLaunchVelocity = readColdLaunchVelocity != null ? readColdLaunchVelocity : new Vec3(0, -1, 0);
        gpsTargetOffsetResolved = gpsTargetOffset != null;
        launchTargetSnapshot = data.getBoolean("launchTargetSnapshot");
        irSeekerGraceUntilTick = data.contains("irSeekerGraceUntilTick") ? data.getInt("irSeekerGraceUntilTick") : Integer.MIN_VALUE;
        terminalIrTargetAcquired = data.getBoolean("terminalIrTargetAcquired");
        resolveRemoteRefs();
    }

    @Override
    public void remoteTick() {
        resolveRemoteRefs();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SECOND_PULSE_START_TICK, -1);
        this.entityData.define(DATA_SECOND_PULSE_BURN_TIME_TICK, 0);
        this.entityData.define(DATA_ACTIVE_RADAR_ON, false);
        this.entityData.define(DATA_ACTIVE_RADAR_CATCH, false);
        this.entityData.define(DATA_CHUNK_WAITING, false);
        this.entityData.define(DATA_CHUNK_WAIT_TOTAL, 0);
        this.entityData.define(DATA_WIRE_ENABLED, false);
        this.entityData.define(DATA_WIRE_PIVOT_X, 0.0f);
        this.entityData.define(DATA_WIRE_PIVOT_Y, 0.0f);
        this.entityData.define(DATA_WIRE_PIVOT_Z, 0.0f);
        this.entityData.define(DATA_WIRE_PIVOT_PREV_X, 0.0f);
        this.entityData.define(DATA_WIRE_PIVOT_PREV_Y, 0.0f);
        this.entityData.define(DATA_WIRE_PIVOT_PREV_Z, 0.0f);
        this.entityData.define(DATA_WIRE_VEHICLE_ID, -1);
        this.entityData.define(DATA_WIRE_PIVOT_LOCAL_X, 0.0f);
        this.entityData.define(DATA_WIRE_PIVOT_LOCAL_Y, 0.0f);
        this.entityData.define(DATA_WIRE_PIVOT_LOCAL_Z, 0.0f);
        this.entityData.define(DATA_WIRE_PIVOT_LOCAL_PREV_X, 0.0f);
        this.entityData.define(DATA_WIRE_PIVOT_LOCAL_PREV_Y, 0.0f);
        this.entityData.define(DATA_WIRE_PIVOT_LOCAL_PREV_Z, 0.0f);
        this.entityData.define(DATA_WIRE_ACTIVE, false);
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
        refreshMotorBurnWindow();
        this.secondPulseStartTick = -1;
        this.entityData.set(DATA_SECOND_PULSE_START_TICK, -1);
        this.entityData.set(DATA_SECOND_PULSE_BURN_TIME_TICK, 0);
        this.showMslIndicator = data.isShowMslIndicator();
        boolean wireEnabled = data.getEffectsData().isWireLinkEnabled();
        this.entityData.set(DATA_WIRE_ENABLED, wireEnabled);
        if (wireEnabled) {
            this.wirePivot = spawnPos;
            this.wirePivotPrev = spawnPos;
            this.entityData.set(DATA_WIRE_PIVOT_X, (float) spawnPos.x);
            this.entityData.set(DATA_WIRE_PIVOT_Y, (float) spawnPos.y);
            this.entityData.set(DATA_WIRE_PIVOT_Z, (float) spawnPos.z);
            this.entityData.set(DATA_WIRE_PIVOT_PREV_X, (float) spawnPos.x);
            this.entityData.set(DATA_WIRE_PIVOT_PREV_Y, (float) spawnPos.y);
            this.entityData.set(DATA_WIRE_PIVOT_PREV_Z, (float) spawnPos.z);
            this.entityData.set(DATA_WIRE_ACTIVE, false);
            if (vehicle != null) {
                // 初始化本地坐标系偏移：客户端据此配合渲染中的载具变换重建锚点
                this.entityData.set(DATA_WIRE_VEHICLE_ID, vehicle.getId());
                Vector3f local = new Vector3f(
                        (float) (spawnPos.x - vehicle.position().x),
                        (float) (spawnPos.y - vehicle.position().y),
                        (float) (spawnPos.z - vehicle.position().z));
                vehicle.rotYXZ().invert().transform(local);
                this.wirePivotLocal = new Vec3(local.x, local.y, local.z);
                this.wirePivotLocalPrev = this.wirePivotLocal;
                this.entityData.set(DATA_WIRE_PIVOT_LOCAL_X, local.x);
                this.entityData.set(DATA_WIRE_PIVOT_LOCAL_Y, local.y);
                this.entityData.set(DATA_WIRE_PIVOT_LOCAL_Z, local.z);
                this.entityData.set(DATA_WIRE_PIVOT_LOCAL_PREV_X, local.x);
                this.entityData.set(DATA_WIRE_PIVOT_LOCAL_PREV_Y, local.y);
                this.entityData.set(DATA_WIRE_PIVOT_LOCAL_PREV_Z, local.z);
            }
        }
        this.signatureSize = data.resolveSignalIntensityFactorOnRadar(0f);
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
        RVP_ProjectileLifecycleDebug.noteInitialized(this);
    }

    public int getSubmunitionDepth() {
        return submunitionDepth;
    }

    public void setSubmunitionDepth(int depth) {
        this.submunitionDepth = Math.max(depth, 0);
        RVP_ProjectileLifecycleDebug.noteEvent(this,
                RVP_ProjectileLifecycleDebug.Event.SUBMUNITION_TRIGGER,
                () -> "action=set_depth depth=" + submunitionDepth);
    }

    public void disableSubmunitionReleases() {
        if (submunitionRunner != null) {
            submunitionRunner.disableAll();
        }
    }

    protected boolean trySubmunitionTrigger(RVP_EnumSubmunitionTrigger trigger) {
        if (submunitionRunner == null || level().isClientSide()) {
            RVP_TopAttackDebug.noteSpawn(this, "SUBMUN skip runner=nullOrClient runner="
                    + (submunitionRunner != null) + " trigger=" + trigger);
            return false;
        }
        boolean fired = submunitionRunner.fireTrigger(this, trigger);
        RVP_TopAttackDebug.noteSpawn(this, "SUBMUN trigger=" + trigger + " fired=" + fired);
        if (fired) {
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.SUBMUNITION_TRIGGER,
                    () -> "trigger=" + trigger + " action=parent_discard");
        }
        return fired;
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

    /** 获取信号尺寸；0 表示不可被雷达/红外探测。 */
    public float getSignatureSize() {
        return signatureSize;
    }

    public boolean isRadarDetectableAmmo() {
        return signatureSize > 0f && weaponKind != RVP_EnumWeaponKind.MACHINEGUN;
    }

    public int getProgrammedAirburstDistance() {
        return airburstDist;
    }

    /** True while the active guidance phase uses live laser or HITL designation. */
    public boolean rvp$isInSaclosGuidanceStage() {
        RVP_WeaponData data = resolveWeaponConfig();
        if (data == null) {
            return false;
        }
        RVP_GuidanceActiveConfig config = RVP_GuidanceModelResolver.resolveActive(
                data, guidancePhaseState.phase());
        if (config.tickRange() != null && !config.tickRange().contains(getFlightTickCount())) {
            return false;
        }
        RVP_EnumGuidanceType active = config.guidanceType();
        return active == RVP_EnumGuidanceType.LH
                || active == RVP_EnumGuidanceType.SALH
                || active == RVP_EnumGuidanceType.HITL_TV;
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

    @Override
    public ResourceLocation getWeaponId() {
        ResourceLocation weaponId = super.getWeaponId();
        return weaponId != null ? weaponId : remoteWeaponId;
    }

    /** Distance in meters for decay sampling (includes the current tick segment before motion integration). */
    protected float decaySampleDistanceM() {
        Vec3 start = collisionSegmentStart();
        Vec3 end = collisionSegmentEnd();
        return (float) (flightDistance + start.distanceTo(end));
    }

    public RVP_EnumWeaponKind getWeaponKind() {
        if (rvpData != null) {
            return rvpData.getWeaponKind();
        }
        if (remoteWeaponId != null || super.getWeaponId() != null) {
            RVP_WeaponData config = resolveWeaponConfig();
            if (config != null) {
                this.weaponKind = config.getWeaponKind();
            }
        }
        return weaponKind;
    }

    public double getFlightSpeed() {
        return flightSpeed;
    }

    public double getCurrentSpeed() {
        return getDeltaMovement().length();
    }

    private boolean usesGpsCruiseGuidance() {
        if (rvpData == null || !rvpData.usesGuidanceType(RVP_EnumGuidanceType.GPS)) {
            return false;
        }
        RVP_GuidanceActiveConfig active = resolveActiveGuidanceConfig();
        return active.guidanceType() == RVP_EnumGuidanceType.GPS
                && active.cruiseStartTick() != null;
    }

    public boolean isGpsCruisePhaseActive() {
        if (!usesGpsCruiseGuidance() || targetPos == null) {
            return false;
        }
        RVP_GuidanceActiveConfig active = resolveActiveGuidanceConfig();
        return getFlightTickCount() >= active.cruiseStartTick()
                && horizontalDistanceTo(targetPos) > active.cruiseEndHorizontalDist();
    }

    public boolean isGpsCruiseTerminalPhaseActive() {
        if (!usesGpsCruiseGuidance() || targetPos == null) {
            return false;
        }
        RVP_GuidanceActiveConfig active = resolveActiveGuidanceConfig();
        return getFlightTickCount() >= active.cruiseStartTick()
                && horizontalDistanceTo(targetPos) <= active.cruiseEndHorizontalDist();
    }

    private float resolveGpsCruiseGravityScale() {
        return rvpData == null ? 1f : resolveActiveGuidanceConfig().cruiseGravityScale();
    }

    private RVP_GuidanceActiveConfig resolveActiveGuidanceConfig() {
        return RVP_GuidanceModelResolver.resolveActive(rvpData, guidancePhaseState.phase());
    }

    public double horizontalDistanceTo(Vec3 pos) {
        if (pos == null) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = pos.x - getX();
        double dz = pos.z - getZ();
        return Math.sqrt(dx * dx + dz * dz);
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

    public void initColdLaunch(@Nullable WeaponUnit weaponUnit) {
        this.launchWeaponUnit = weaponUnit;
        if (weaponUnit == null) {
            return;
        }
        coldLaunchTimeTick = Math.max(weaponUnit.getColdLaunchTimeTick(), 0);
        Vec3 configuredVelocity = weaponUnit.getColdLaunchVelocity();
        if (configuredVelocity != null) {
            coldLaunchVelocity = configuredVelocity;
        }
        refreshMotorBurnWindow();
    }

    public int getColdLaunchTimeTick() {
        return coldLaunchTimeTick;
    }

    /**
     * 绑定线导的发射单元与发射时所用的 bolt：线缆固定在该 bolt 的管口，
     * 后续轮射切换 currentBolt 时不会跳管，起点始终是这发导弹实际出膛的那根管口。
     */
    public void setWireLaunchUnit(@Nullable WeaponUnit unit, @Nullable AimContext launchAim) {
        this.launchWeaponUnit = unit;
        this.wireBoltIndex = -1;
        this.wirePivotBase = Vec3.ZERO;
        if (unit == null || launchAim == null) {
            return;
        }
        // 客户端发送前会把 from 加上车辆速度作为预测提前量，先减掉以对齐服务器坐标。
        Vec3 vehicleDelta = unit.getVehicle() == null ? Vec3.ZERO : unit.getVehicle().getDeltaMovement();
        Vec3 launchFrom = launchAim.from.subtract(vehicleDelta);
        Vec3 pivotNow = unit.worldPivotPosition();
        Vec3 launchOffset = launchFrom.subtract(pivotNow);
        // 兜底基准始终用这发导弹实际出膛点（预测修正后）相对枢轴的偏移，而不是"最近管口"：
        // 客户端预测残差（clientDelta - serverDelta）在车辆机动/网络延迟下可能落在管口间距的
        // 模糊带内，若兜底取最近管口就可能落到错误管口，造成"右边出弹、线从左边拉"。
        // 从真实出膛点拉线，残差再大也只停留在正确管口附近，绝不会跳到另一根管。
        wirePivotBase = launchOffset;
        List<AimContext> aims = unit.aimContexts();
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < aims.size(); i++) {
            Vec3 aimOffset = aims.get(i).from.subtract(pivotNow);
            double d = aimOffset.distanceToSqr(launchOffset);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        // 仅当残差充分小（远小于管口间距的一半）才锁定该管口：锁定时每 tick 从 aimContexts
        // 取最新管口位置，随武器站旋转精确跟随；残差落在模糊带内则放弃锁定，靠上面的兜底
        // 从真实出膛点拉线，避免把起点锁到错误管口（残差略大时最近管口可能并不是本弹管口）。
        if (best >= 0 && bestDist < 0.0625) {
            wireBoltIndex = best;
        }
    }

    public Vec3 getColdLaunchVelocity() {
        return coldLaunchVelocity;
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
        Vec3 resolved = applyGpsTargetDispersion(targetPos);
        this.targetPos = resolved;
        this.gpsCruiseVerticalResetApplied = false;
        if (resolved != null) {
            this.lastGuidancePos = resolved;
        }
    }

    public void setGuidanceTargetPos(@Nullable Vec3 targetPos) {
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
        this.gpsCruiseVerticalResetApplied = false;
        resetIrSeekerGrace();
    }

    public void markLaunchTargetSnapshot() {
        this.launchTargetSnapshot = true;
    }

    public boolean hasLaunchTargetSnapshot() {
        return launchTargetSnapshot;
    }

    public void beginIrSeekerGrace(int ticks) {
        irSeekerGraceUntilTick = Math.max(irSeekerGraceUntilTick, getFlightTickCount() + Math.max(ticks, 0));
    }

    public void beginIrSeekerLossGrace(int ticks) {
        if (irSeekerLossGraceStarted) {
            return;
        }
        irSeekerLossGraceStarted = true;
        beginIrSeekerGrace(ticks);
    }

    public boolean hasIrSeekerGrace() {
        return getFlightTickCount() <= irSeekerGraceUntilTick;
    }

    public void resetIrSeekerGrace() {
        irSeekerGraceUntilTick = Integer.MIN_VALUE;
        irSeekerLossGraceStarted = false;
    }

    public boolean hasTerminalIrTargetAcquired() {
        return terminalIrTargetAcquired;
    }

    public void markTerminalIrTargetAcquired() {
        terminalIrTargetAcquired = true;
    }

    public boolean consumeGpsCruiseVerticalResetPending() {
        if (gpsCruiseVerticalResetApplied) {
            return false;
        }
        gpsCruiseVerticalResetApplied = true;
        return true;
    }

    public void rememberGuidancePos(@Nullable Vec3 pos) {
        if (pos != null) {
            this.lastGuidancePos = pos;
        }
    }

    public void initializeTopAttackProfile(Vec3 launchPos, Vec3 initialTargetPos, Vec3 apexPos) {
        if (topAttackApexPos != null || launchPos == null || initialTargetPos == null || apexPos == null) {
            return;
        }
        topAttackLaunchPos = launchPos;
        topAttackInitialTargetPos = initialTargetPos;
        topAttackApexPos = apexPos;
        topAttackApexReached = false;
    }

    @Nullable
    public Vec3 getTopAttackLaunchPos() {
        return topAttackLaunchPos;
    }

    @Nullable
    public Vec3 getTopAttackInitialTargetPos() {
        return topAttackInitialTargetPos;
    }

    @Nullable
    public Vec3 getTopAttackApexPos() {
        return topAttackApexPos;
    }

    public boolean hasReachedTopAttackApex() {
        return topAttackApexReached;
    }

    public void markTopAttackApexReached() {
        topAttackApexReached = true;
    }

    @Nullable
    private Vec3 applyGpsTargetDispersion(@Nullable Vec3 targetPos) {
        if (targetPos == null || !usesGpsTargetSpread()) {
            return targetPos;
        }
        Vec3 offset = ensureGpsTargetOffset();
        return offset == null ? targetPos : targetPos.add(offset);
    }

    private boolean usesGpsTargetSpread() {
        return weaponKind == RVP_EnumWeaponKind.BOMB
                && rvpData != null
                && rvpData.usesGuidanceType(RVP_EnumGuidanceType.GPS)
                && resolveGpsTargetSpreadRadius() > 0f;
    }

    @Nullable
    private Vec3 ensureGpsTargetOffset() {
        if (!usesGpsTargetSpread()) {
            return null;
        }
        if (gpsTargetOffsetResolved) {
            return gpsTargetOffset;
        }
        gpsTargetOffsetResolved = true;
        float spreadRadius = resolveGpsTargetSpreadRadius();
        if (spreadRadius <= 0f) {
            gpsTargetOffset = null;
            return null;
        }
        gpsTargetOffset = sampleGpsTargetOffset(level().random, spreadRadius);
        return gpsTargetOffset;
    }

    private float resolveGpsTargetSpreadRadius() {
        if (rvpData == null) {
            return 0f;
        }
        return resolveActiveGuidanceConfig().gpsSpreadRadius();
    }

    private static Vec3 sampleGpsTargetOffset(RandomSource random, float spreadRadius) {
        // 2D isotropic Gaussian: radius enclosing 50% impacts equals sigma * sqrt(2 ln 2).
        double sigma = spreadRadius / Math.sqrt(2.0D * Math.log(2.0D));
        double dx = random.nextGaussian() * sigma;
        double dz = random.nextGaussian() * sigma;
        return new Vec3(dx, 0.0D, dz);
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

    public boolean hasAntiRadiationSignalAcquired() {
        return antiRadiationSignalAcquired;
    }

    public void setAntiRadiationSignalAcquired(boolean acquired) {
        this.antiRadiationSignalAcquired = acquired;
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
        return this.entityData.get(DATA_ACTIVE_RADAR_ON);
    }

    public boolean isAutonomousSeekerOn() {
        return isActiveRadarOn();
    }

    public void setAutonomousSeekerOn(boolean enabled) {
        this.activeRadarOn = enabled;
        this.entityData.set(DATA_ACTIVE_RADAR_ON, enabled);
    }

    public boolean hasAutonomousSeekerCatch() {
        return this.entityData.get(DATA_ACTIVE_RADAR_CATCH);
    }

    public void markAutonomousSeekerCatch() {
        this.activeRadarCatch = true;
        this.entityData.set(DATA_ACTIVE_RADAR_CATCH, true);
        this.activeRadarLostTargetTick = 0;
    }

    public void incrementAutonomousSeekerLostTargetTick() {
        this.activeRadarLostTargetTick++;
    }

    public int getAutonomousSeekerLostTargetTick() {
        return activeRadarLostTargetTick;
    }

    public boolean isActiveRadarCatch() {
        return hasAutonomousSeekerCatch();
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

    public RVP_GuidancePhaseState getGuidancePhaseState() {
        return guidancePhaseState;
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

    /**
     * 弹体每tick行为
     */
    @Override
    public void tick() {
        boolean traceLifecycle = RVP_ProjectileLifecycleDebug.isEnabled() && !level().isClientSide();
        long tickStartNanos = traceLifecycle ? System.nanoTime() : 0L;
        super.tick();
        long superTickNanos = traceLifecycle ? System.nanoTime() - tickStartNanos : 0L;
        long rvpTickStartNanos = traceLifecycle ? System.nanoTime() : 0L;
        try {
            if (this instanceof RVP_BulletEntity bullet) {
                bullet.tickBullet();
                return;
            }
            // Match {@link org.ywzj.vehicle.entity.weapon.MissileEntity}: motion server-only; client uses synced rot + AmmoEntity lerp.
            if (level().isClientSide()) {
                if (!isWaitingForChunk()) {
                    spawnTrailParticles();
                }
                return;
            }

            if (rvpData == null) {
                RVP_ProjectileLifecycleDebug.noteEvent(this,
                        RVP_ProjectileLifecycleDebug.Event.CONFIG_MISSING,
                        () -> "action=discard");
                discard();
                return;
            }

            if (!checkShooterValid()) {
                RVP_ProjectileLifecycleDebug.noteEvent(this,
                        RVP_ProjectileLifecycleDebug.Event.SHOOTER_INVALID,
                        () -> "owner=" + RVP_ProjectileLifecycleDebug.formatEntity(getOwner())
                                + " shooterVehicle=" + RVP_ProjectileLifecycleDebug.formatEntity(shooterVehicle)
                                + " action=discard");
                discard();
                return;
            }

            // 等待态必须先复查同一条运动路径；未就绪时不推进任何飞行状态。
            if (tickChunkWaitGate()) {
                return;
            }

            // 先用上一 Tick 已确定的速度执行就绪门，避免在明确不可移动时推进引信、制导和发动机时钟。
            RVP_ChunkPathLoader.PathLoadResult preGuidancePath = requestDynamicChunkPath(
                    RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE);
            if (!preGuidancePath.currentTickPathReady()) {
                enterChunkWait(preGuidancePath, false);
                return;
            }

            updateCount++;
            if (tickDelayFuse()) {
                return;
            }

            tickSubmunition();
            if (RVP_ProjectileLifecycleDebug.noteNotAliveTickExit(
                    this,
                    RVP_ProjectileLifecycleDebug.NotAliveCheckpoint.AFTER_SUBMUNITION)) {
                return;
            }
            guidanceWireDirectApplied = false;
            tickGuidance();
            RVP_ChunkPathLoader.PathLoadResult pathLoadResult = requestDynamicChunkPath(
                    RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE);
            if (!pathLoadResult.currentTickPathReady()) {
                enterChunkWait(pathLoadResult, true);
                return;
            }
            // 先碰撞检测再运动（对标本体 BulletEntity 顺序，修复直接命中丢失的 bug）
            tickHit();
            if (RVP_ProjectileLifecycleDebug.noteNotAliveTickExit(
                    this,
                    RVP_ProjectileLifecycleDebug.NotAliveCheckpoint.AFTER_HIT)) {
                return;
            }
            tickMotion();
            if (RVP_ProjectileLifecycleDebug.noteNotAliveTickExit(
                    this,
                    RVP_ProjectileLifecycleDebug.NotAliveCheckpoint.AFTER_MOTION)) {
                return;
            }
            // 从运动后的新位置刷新滚动窗口，为下一 Tick 的管理器预算分配提前提交路径。
            requestDynamicChunkPath(RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE);
            //弹体完成移动后的驻留状态
            RVP_ChunkPathLoadManager.recordPostMoveObservation(this);
            tickProgrammableAirburst();
            tickProximityFuse();
            tickTopAttackFuse();
            if (tickBounceFuse()) {
                return;
            }
            broadcastTrailParticles();
            // 线导枢轴只由服务端更新并同步，客户端实体不执行（launchWeaponUnit 为 null，
            // 若执行会以本地出膛点覆盖服务端同步的管口坐标，导致起点跳变/双点）
            if (!level().isClientSide()) {
                tickWireLink();
            }
            life--;
            if (life < 0) {
                boolean detonate = rvpData.getFuseData().isDetonateOnLifeEnd();
                RVP_ProjectileLifecycleDebug.noteEvent(this,
                        RVP_ProjectileLifecycleDebug.Event.LIFE_END,
                        () -> "detonate=" + detonate + " position="
                                + RVP_ProjectileLifecycleDebug.formatVec(position()));
                if (detonate) {
                    explodeAndDiscard(position());
                } else {
                    discard();
                }
            }
        } finally {
            if (traceLifecycle) {
                long rvpTickNanos = System.nanoTime() - rvpTickStartNanos;
                RVP_ProjectileLifecycleDebug.noteTick(
                        this,
                        superTickNanos,
                        rvpTickNanos,
                        superTickNanos + rvpTickNanos);
            }
        }
    }

    /* ============新弹体chunk路径规划============ */

    @Override
    public void remove(Entity.RemovalReason reason) {
        RVP_ChunkPathLoadManager.releaseEntity(this);
        RVP_ProjectileLifecycleDebug.noteRemoved(this, reason);
        super.remove(reason);
    }

    /**
     * 是否为该 RVP 弹体启用动态区块路径保护。
     * 普通 Bullet 覆盖为 false；按实体类型分流，不读取武器 ID。
     */
    protected boolean shouldKeepDynamicChunkPathLoaded() {
        return true;
    }

    /**
     * 弹体成功加入世界后提交首个路径窗口；Ticket 仍由下一 ServerTick START 统一分配。
     */
    public final void primeDynamicChunkPath() {
        if (!level().isClientSide() && isAlive() && shouldKeepDynamicChunkPathLoaded()) {
            requestDynamicChunkPath(RVP_ChunkPathLoadManager.RequestPriority.ACTIVE_PROJECTILE);
        }
    }

    /** @return 扣除区块等待暂停时间后的飞行 Tick。 */
    public final int getFlightTickCount() {
        return Math.max(0, tickCount - entityData.get(DATA_CHUNK_WAIT_TOTAL));
    }

    /** @return 当前是否因运动路径尚未就绪而等待。 */
    public final boolean isWaitingForChunk() {
        return entityData.get(DATA_CHUNK_WAITING);
    }

    /** 等待态优先刷新路径并复查；返回 true 表示本 Tick 必须保持当前位置。 */
    private boolean tickChunkWaitGate() {
        if (!isWaitingForChunk()) {
            return false;
        }
        RVP_ChunkPathLoader.PathLoadResult result = requestDynamicChunkPath(
                RVP_ChunkPathLoadManager.RequestPriority.WAITING_PROJECTILE);
        if (result.currentTickPathReady()) {
            int waited = chunkWaitTicks;
            chunkWaitTicks = 0;
            entityData.set(DATA_CHUNK_WAITING, false);
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.CHUNK_READY_RESUME,
                    () -> formatChunkPathState(result, waited));
            return false;
        }

        chunkWaitTicks++;
        entityData.set(DATA_CHUNK_WAIT_TOTAL, entityData.get(DATA_CHUNK_WAIT_TOTAL) + 1);
        if (chunkWaitTicks >= MAX_PROJECTILE_CHUNK_WAIT_TICKS) {
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.CHUNK_WAIT_TIMEOUT,
                    () -> formatChunkPathState(result, chunkWaitTicks) + " action=discard");
            RVP_ChunkPathLoadManager.releaseEntity(this);
            discard();
        }
        return true;
    }

    /** 从活动飞行切换为等待；仅在尚未推进飞行状态时把转换 Tick 计入暂停时钟。 */
    private void enterChunkWait(
            RVP_ChunkPathLoader.PathLoadResult result,
            boolean flightStateAdvanced) {
        if (isWaitingForChunk()) {
            return;
        }
        chunkWaitTicks = flightStateAdvanced ? 0 : 1;
        if (!flightStateAdvanced) {
            entityData.set(DATA_CHUNK_WAIT_TOTAL, entityData.get(DATA_CHUNK_WAIT_TOTAL) + 1);
        }
        entityData.set(DATA_CHUNK_WAITING, true);
        RVP_ProjectileLifecycleDebug.noteEvent(this,
                RVP_ProjectileLifecycleDebug.Event.WAITING_FOR_CHUNK,
                () -> formatChunkPathState(result, chunkWaitTicks));
    }

    /** 向纯路径加载器提交当前速度窗口。 */
    private RVP_ChunkPathLoader.PathLoadResult requestDynamicChunkPath(
            RVP_ChunkPathLoadManager.RequestPriority priority) {
        if (!shouldKeepDynamicChunkPathLoaded()) {
            return new RVP_ChunkPathLoader.PathLoadResult(
                    0, 0, 0, null, RVP_ChunkPathLoader.ChunkReadiness.READY,
                    false, false, true);
        }
        return RVP_ChunkPathLoader.requestProjectedPath(
                this, position(), getDeltaMovement(), PROJECTILE_CHUNK_HORIZON_TICKS, priority);
    }

    /** 生命周期转换日志的统一字段，便于直接定位预算、截断或区块就绪问题。 */
    private static String formatChunkPathState(RVP_ChunkPathLoader.PathLoadResult result, int waitTicks) {
        String first = result.firstUnreadyChunk() == null
                ? "<null>"
                : "(" + result.firstUnreadyChunk().x + "," + result.firstUnreadyChunk().z + ")";
        return "plannedChunkCount=" + result.plannedChunkCount()
                + " requestedChunkCount=" + result.requestedChunkCount()
                + " readyChunkCount=" + result.readyChunkCount()
                + " firstUnreadyChunk=" + first
                + " firstUnreadyState=" + result.firstUnreadyState()
                + " chunkWaitTicks=" + waitTicks
                + " budgetExhausted=" + result.budgetExhausted()
                + " projectedPathTruncated=" + result.projectedPathTruncated();
    }

    /* ============新弹体chunk路径规划 END============ */

    /**
     * 线导视觉线服务端逻辑：更新发射枢轴世界坐标（随武器站枢轴移动）并同步线缆激活状态到实体数据。
     */
    protected void tickWireLink() {
        if (!entityData.get(DATA_WIRE_ENABLED)) {
            if (entityData.get(DATA_WIRE_ACTIVE)) {
                entityData.set(DATA_WIRE_ACTIVE, false);
            }
            return;
        }
        // 保存上一 tick 的枢轴，供客户端渲染 partialTick 插值，消除 20Hz 同步的跳变抖动
        wirePivotPrev = wirePivot;
        wirePivotLocalPrev = wirePivotLocal;
        if (launchWeaponUnit != null) {
            // 起点固定在这发导弹实际出膛的那根管口（bolt 索引），并随武器站旋转/移动而更新
            Vec3 pivot = null;
            if (wireBoltIndex >= 0) {
                List<AimContext> aims = launchWeaponUnit.aimContexts();
                if (wireBoltIndex < aims.size()) {
                    pivot = aims.get(wireBoltIndex).from;
                }
            }
            if (pivot == null) {
                // 兜底：枢轴 + 发射时刻记录的管口局部偏移（不跳管，避免双线/扇面）。
                // 绝不能 fallback 到 aimContext()：服务端 currentBolt 恒为 0，轮射时会把
                // 起点跳到另一根管，客户端插值后看起来像"一根导弹接两根线"。
                pivot = launchWeaponUnit.worldPivotPosition().add(wirePivotBase);
            }
            if (pivot != null) {
                wirePivot = pivot;
            }
        } else if (shooterWeaponUnit != null) {
            // 起点跟随当前 bolt 的出膛位置（管口），随武器站旋转/移动而更新
            AimContext aim = shooterWeaponUnit.aimContext();
            Vec3 pivot = aim != null ? aim.from : shooterWeaponUnit.worldPivotPosition();
            if (pivot != null) {
                wirePivot = pivot;
            }
        }
        // 同步锚点在载具本地坐标系的偏移：客户端用"渲染中的载具变换"重建锚点，
        // 锚点始终贴住玩家看到的管口，消除客户端载具速度外推/网络延迟与服务端枢轴之间的相位差（运动抖动）。
        AbstractVehicle wireVehicle = launchWeaponUnit != null ? launchWeaponUnit.getVehicle()
                : shooterWeaponUnit != null ? shooterWeaponUnit.getVehicle() : null;
        if (wireVehicle != null) {
            Vector3f local = new Vector3f(
                    (float) (wirePivot.x - wireVehicle.position().x),
                    (float) (wirePivot.y - wireVehicle.position().y),
                    (float) (wirePivot.z - wireVehicle.position().z));
            wireVehicle.rotYXZ().invert().transform(local);
            wirePivotLocal = new Vec3(local.x, local.y, local.z);
            entityData.set(DATA_WIRE_VEHICLE_ID, wireVehicle.getId());
            entityData.set(DATA_WIRE_PIVOT_LOCAL_X, local.x);
            entityData.set(DATA_WIRE_PIVOT_LOCAL_Y, local.y);
            entityData.set(DATA_WIRE_PIVOT_LOCAL_Z, local.z);
            entityData.set(DATA_WIRE_PIVOT_LOCAL_PREV_X, (float) wirePivotLocalPrev.x);
            entityData.set(DATA_WIRE_PIVOT_LOCAL_PREV_Y, (float) wirePivotLocalPrev.y);
            entityData.set(DATA_WIRE_PIVOT_LOCAL_PREV_Z, (float) wirePivotLocalPrev.z);
        } else {
            entityData.set(DATA_WIRE_VEHICLE_ID, -1);
        }
        boolean active = rvp$isWireActive();
        entityData.set(DATA_WIRE_PIVOT_X, (float) wirePivot.x);
        entityData.set(DATA_WIRE_PIVOT_Y, (float) wirePivot.y);
        entityData.set(DATA_WIRE_PIVOT_Z, (float) wirePivot.z);
        entityData.set(DATA_WIRE_PIVOT_PREV_X, (float) wirePivotPrev.x);
        entityData.set(DATA_WIRE_PIVOT_PREV_Y, (float) wirePivotPrev.y);
        entityData.set(DATA_WIRE_PIVOT_PREV_Z, (float) wirePivotPrev.z);
        entityData.set(DATA_WIRE_ACTIVE, active);
    }

    /** 线缆当前是否应显示（服务端判定后经 {@link #DATA_WIRE_ACTIVE} 同步给客户端）。 */
    public boolean rvp$isWireActive() {
        if (!entityData.get(DATA_WIRE_ENABLED)) {
            return false;
        }
        return rvp$computeWireActive();
    }

    /** 制导有效判定：仍处于激活引导段（引导源非 NONE）。HITL/MCLOS 线导可重写。 */
    protected boolean rvp$computeWireActive() {
        if (rvpData == null) {
            return true;
        }
        RVP_GuidanceActiveConfig active = RVP_GuidanceModelResolver.resolveActive(
                rvpData.getGuidanceData(), getGuidancePhaseState().phase());
        return active != null && active.guidanceType() != RVP_EnumGuidanceType.NONE;
    }

    protected void tickGuidance() {
        if (!level().isClientSide()) {
            boolean allowEntityTracking = rvpData == null
                    || !rvpData.isVehicleLaserGuided()
                    || rvpData.isSaclosTvGuided();
            if (!allowEntityTracking && targetEntity != null) {
                targetEntity = null;
            }
            org.ywzj.rvp.guidance.saclos.RVP_SaclosDesignation.tickUpdateLiveTarget(this);
            // Mid-course update: if we have a live targetEntity (from launch lock),
            // periodically update targetPos so IOG/coast phase tracks the moving target.
            // This mirrors the base MissileEntity behavior where targetEntity is a
            // live Java Entity reference updated every tick.
            if (allowEntityTracking && targetEntity != null && targetEntity.isAlive()) {
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
            float dragInAir = rvpData.getDragInAir();
            if (isMissile()) {
                dragInAir *= RVP_ProjectileMotion.resolveMissileAltitudeDragFactor(this, rvpData);
            }
            float gravity = rvpData.getGravity();
            if (isGpsCruisePhaseActive()) {
                gravity *= resolveGpsCruiseGravityScale();
            }
            velocity = velocity.add(0, gravity, 0);
            velocity = applyMchHorizontalDrag(velocity, dragInAir);
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
        flightSpeed = Math.max(velocity.length(), 0.01);
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
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.FUSE,
                    () -> "type=DELAY delay=" + delay + " update=" + updateCount
                            + " position=" + RVP_ProjectileLifecycleDebug.formatVec(position()));
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
        Vec3 segmentStart = programmableAirburstSegmentStart;
        Vec3 segmentEnd = programmableAirburstSegmentEnd;
        Vec3 motion = segmentEnd.subtract(segmentStart);
        double segLen = motion.length();
        if (segLen <= 0.0D) {
            return;
        }
        double newTravel = airburstTravelled + segLen;
        if (newTravel >= targetDist) {
            double remain = targetDist - airburstTravelled;
            double t = remain / segLen;
            Vec3 detonatePos = segmentStart.add(motion.scale(t));
            if (shouldSuppressAheadAirburstAt(detonatePos)) {
                RVP_ProjectileLifecycleDebug.noteEvent(this,
                        RVP_ProjectileLifecycleDebug.Event.AIRBURST_SUPPRESSED,
                        () -> "measured=" + measured + " targetDistance="
                                + RVP_ProjectileLifecycleDebug.decimal(targetDist)
                                + " position=" + RVP_ProjectileLifecycleDebug.formatVec(detonatePos));
                airburstTriggered = true;
                airburstTravelled = 0.0D;
                return;
            }
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.FUSE,
                    () -> "type=AIRBURST measured=" + measured + " targetDistance="
                            + RVP_ProjectileLifecycleDebug.decimal(targetDist)
                            + " position=" + RVP_ProjectileLifecycleDebug.formatVec(detonatePos));
            detonateFuseAt(detonatePos, FuseDetonation.AIRBURST);
            airburstTriggered = true;
            airburstTravelled = 0.0D;
        } else {
            airburstTravelled = newTravel;
        }
    }

    protected boolean shouldSuppressAheadAirburstAt(Vec3 detonatePos) {
        if (rvpData == null || !rvpData.getFuseData().isAheadEnabled()) {
            return false;
        }
        float minGroundClearance = rvpData.getFuseData().getAheadMinGroundClearance();
        if (minGroundClearance <= 0f) {
            return false;
        }
        int groundY = level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                Mth.floor(detonatePos.x), Mth.floor(detonatePos.z));
        return detonatePos.y - groundY < minGroundClearance;
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
                && (!rvpData.isAntiRadiationMissile() || hasActiveRadar(targetEntity))
                && !isProximityDamageImmune(targetEntity)
                && targetEntity.getBoundingBox().inflate(radius).contains(position())) {
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.FUSE,
                    () -> "type=PROXIMITY_POST_MOTION source=locked_target radius="
                            + RVP_ProjectileLifecycleDebug.decimal(radius)
                            + " target=" + RVP_ProjectileLifecycleDebug.formatEntity(targetEntity));
            detonateFuseAt(position(), FuseDetonation.PROXIMITY, targetEntity);
            return;
        }
        // 对标本体 AmmoEntity.tickHit：检测盒向后偏移，捕获刚飞过的目标
        Vec3 backward = getLookAngle().normalize().scale(-radius);
        AABB detectionBox = getBoundingBox().inflate(radius).move(backward);
        for (Entity entity : level().getEntities(this, detectionBox,
                e -> canDamageEntity(e) && !isProximityFuseTargetTooLow(e, fuseHeight)
                        && (!rvpData.isAntiRadiationMissile() || hasActiveRadar(e))
                        && !isProximityDamageImmune(e))) {
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.FUSE,
                    () -> "type=PROXIMITY_POST_MOTION source=detection_box radius="
                            + RVP_ProjectileLifecycleDebug.decimal(radius)
                            + " target=" + RVP_ProjectileLifecycleDebug.formatEntity(entity));
            detonateFuseAt(position(), FuseDetonation.PROXIMITY, entity);
            return;
        }
    }

    /**
     * 攻顶引信：检测弹体正下方（世界系绝对 -Y 轴，不随弹体姿态变化）半锥角内的实体。
     * 探测到后触发引信（复用近炸全额伤害与 {@code on_fuse} 子母弹链路）；可配延时起爆。
     */
    protected void tickTopAttackFuse() {
        if (rvpData == null || level().isClientSide()) {
            return;
        }
        RVP_FuseData fuse = rvpData.getFuseData();
        RVP_TopAttackDebug.noteTick(this, "enter enabled=" + fuse.isTopAttackFuseEnabled()
                + " dist=" + fuse.getTopAttackFuseDistance()
                + " fov=" + fuse.getTopAttackFuseFov()
                + " delay=" + fuse.getTopAttackFuseDelayTick()
                + " arm=" + fuse.getTopAttackFuseArmTick()
                + " triggerTick=" + topAttackTriggerTick
                + " delta=" + RVP_ProjectileLifecycleDebug.formatVec(getDeltaMovement()));
        if (!fuse.isTopAttackFuseEnabled()) {
            return;
        }
        int armTick = fuse.getTopAttackFuseArmTick();
        if (armTick > 0 && updateCount <= armTick) {
            return;
        }
        // 已探测到目标：倒计时延时起爆（到点后无论目标是否仍在锥内都炸）
        if (topAttackTriggerTick >= 0) {
            if (updateCount - topAttackTriggerTick >= fuse.getTopAttackFuseDelayTick()) {
                RVP_TopAttackDebug.noteTick(this, "DETONATE delayed armedAt=" + topAttackTriggerTick
                        + " now=" + updateCount + " delay=" + fuse.getTopAttackFuseDelayTick());
                RVP_ProjectileLifecycleDebug.noteEvent(this,
                        RVP_ProjectileLifecycleDebug.Event.FUSE,
                        () -> "type=TOP_ATTACK source=delayed position="
                                + RVP_ProjectileLifecycleDebug.formatVec(position()));
                topAttackTriggerTick = -1;
                detonateFuseAt(position(), FuseDetonation.PROXIMITY, null);
            }
            return;
        }
        float distance = fuse.getTopAttackFuseDistance();
        float fov = fuse.getTopAttackFuseFov();
        if (distance <= 0f) {
            return;
        }
        Vec3 pos = position();
        // 粗筛：覆盖锥形可达范围（水平 ±distance，向下 distance）
        AABB detectionBox = getBoundingBox().expandTowards(0, -distance, 0).inflate(distance);
        double cosLimit = Math.cos(Math.toRadians(fov));
        Vec3 down = new Vec3(0, -1, 0);
        for (Entity entity : level().getEntities(this, detectionBox, this::canDamageEntity)) {
            // 精筛：目标包围盒中心点须位于导弹正下方的锥形内
            Vec3 offset = entity.getBoundingBox().getCenter().subtract(pos);
            if (offset.y >= 0 || offset.lengthSqr() > (double) distance * distance) {
                continue;
            }
            if (offset.normalize().dot(down) < cosLimit) {
                continue;
            }
            RVP_TopAttackDebug.noteTick(this, "DETECT below_cone dist="
                    + RVP_ProjectileLifecycleDebug.decimal(offset.length())
                    + " fov=" + fov
                    + " target=" + RVP_ProjectileLifecycleDebug.formatEntity(entity)
                    + " entities=" + RVP_ProjectileLifecycleDebug.formatVec(pos));
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.FUSE,
                    () -> "type=TOP_ATTACK source=below_cone distance="
                            + RVP_ProjectileLifecycleDebug.decimal(offset.length())
                            + " target=" + RVP_ProjectileLifecycleDebug.formatEntity(entity));
            int delay = fuse.getTopAttackFuseDelayTick();
            if (delay <= 0) {
                RVP_TopAttackDebug.noteTick(this, "DETONATE immediate delay=" + delay);
                detonateFuseAt(position(), FuseDetonation.PROXIMITY, entity);
                return;
            }
            topAttackTriggerTick = updateCount;
            return;
        }
    }

    /** MCH proximity fuse skips targets on/near ground within {@link RVP_FuseData#getProximityFuseHeight()}. */
    protected boolean isProximityFuseTargetTooLow(Entity entity, int fuseHeight) {
        return RVP_GuidanceMath.isEntityNearGroundBlocks(entity, fuseHeight);
    }

    /** 目标已直击或已吃近炸全额伤害时，近炸不再对其触发/叠加（直击与近炸互斥）。 */
    private boolean isProximityDamageImmune(Entity entity) {
        Entity root = ywzj_rvp$resolveCollisionRoot(entity);
        if (root == null) {
            return false;
        }
        return directHitIds.contains(root.getId()) || proximityDamagedIds.contains(root.getId());
    }

    /**
     * ARM 导弹近炸引信只对有活跃雷达的目标生效。
     * 检查目标实体是否为载具且拥有至少一个开启的雷达单元。
     */
    private static boolean hasActiveRadar(Entity entity) {
        if (!(entity instanceof AbstractVehicle vehicle)) {
            return false;
        }
        if (vehicle.isDestroyed()) {
            return false;
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof RadarUnit radar && radar.isOn()) {
                return true;
            }
        }
        return false;
    }

    protected void tickSubmunition() {
        if (submunitionRunner == null) {
            return;
        }
        if (submunitionRunner.tickInFlight(this)) {
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.SUBMUNITION_TRIGGER,
                    () -> "trigger=IN_FLIGHT action=parent_discard");
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
        programmableAirburstSegmentStart = startVec;
        programmableAirburstSegmentEnd = endVec;
        Vec3 step = endVec.subtract(startVec);
        if (step.lengthSqr() < 1.0E-12) {
            return;
        }

        boolean entityCollisionSafetyActive = isEntityCollisionSafetyActive();
        BulletHitResult entityResult = entityCollisionSafetyActive ? null : findEntityOnPathForSegment(startVec, endVec, step);

        if (entityResult != null
                && entityResult.getEntity() != vehicle
                && (vehicle == null || !vehicle.getPassengers().contains(entityResult.getEntity()))
                && !piercedLivingIds.contains(entityResult.getEntity().getId())) {
            handleEntityImpact(entityResult);
            return;
        }

        if (!entityCollisionSafetyActive && getFlightTickCount() > 5 && entityResult == null) {
            if (tryAmmoProximityFuze(startVec, endVec)) {
                return;
            }
        }

        if (!isBlockCollisionSafetyActive()) {
            BlockHitResult blockResult = level().clip(
                    new ClipContext(startVec, endVec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (blockResult.getType() != HitResult.Type.MISS) {
                onAmmoBlockHit(blockResult);
            }
        }
    }

    protected boolean isEntityCollisionSafetyActive() {
        return getFlightTickCount() < resolveEntityCollisionSafeTick();
    }

    protected int resolveEntityCollisionSafeTick() {
        RVP_WeaponData config = resolveWeaponConfig();
        if (config != null && config.getFuseData().hasEntityCollisionSafeTickOverride()) {
            return config.getFuseData().getEntityCollisionSafeTick();
        }
        // Gunner 垂发载具：前 10 tick 跳过实体碰撞，避免导弹撞到自身载具
        if (getOwner() instanceof org.ywzj.rvp.entity.gunner.GunnerEntity && shooterVehicle != null
                && org.ywzj.rvp.entity.gunner.ai.GunnerBrain.hasLauncherDeployConfig(shooterVehicle)) {
            return Math.max(10, this instanceof RVP_BombEntity ? 20 : 3);
        }
        if (this instanceof RVP_MissileEntity) {
            return 3;
        }
        if (this instanceof RVP_BombEntity) {
            return 20;
        }
        return 0;
    }

    /** 方块碰撞安全期：生效 tick 内跳过方块碰撞检测。 */
    protected boolean isBlockCollisionSafetyActive() {
        return getFlightTickCount() < resolveBlockCollisionSafeTick();
    }

    protected int resolveBlockCollisionSafeTick() {
        // Gunner 垂发载具：前 10 tick 跳过方块碰撞，防止导弹刚发射就撞到地面/载具
        if (getOwner() instanceof org.ywzj.rvp.entity.gunner.GunnerEntity && shooterVehicle != null
                && org.ywzj.rvp.entity.gunner.ai.GunnerBrain.hasLauncherDeployConfig(shooterVehicle)) {
            return 10;
        }
        return 0;
    }

    /**
     * {@link EntityUtil#findEntityOnPath} expands AABB with {@link #getDeltaMovement()}; use this tick's travel vector.
     */
    @Nullable
    protected BulletHitResult findEntityOnPathForSegment(Vec3 startVec, Vec3 endVec, Vec3 step) {
        Vec3 saved = getDeltaMovement();
        setDeltaMovement(step);
        try {
            Entity owner = getOwner();
            List<Entity> entities = level().getEntities(
                    this,
                    getBoundingBox().expandTowards(step).inflate(1.0),
                    entity -> entity != null && entity.isPickable() && !entity.isSpectator()
            );
            BulletHitResult closestResult = null;
            double closestDistance = Double.MAX_VALUE;
            for (Entity entity : entities) {
                if (entity == owner || !canDamageEntity(entity)) {
                    continue;
                }
                BulletHitResult rawResult = EntityUtil.getHitResult(this, entity, startVec, endVec);
                if (rawResult == null) {
                    continue;
                }
                BulletHitResult normalizedResult = ywzj_rvp$normalizeBulletHitResult(entity, rawResult);
                if (normalizedResult == null) {
                    continue;
                }
                if (normalizedResult.getEntity() instanceof AbstractVehicle targetVehicle) {
                    Vec3 nonPhysicsHit = RVP_PhysicsOnlyCollisionHelper.closestNonPhysicsOnlyHitPosition(targetVehicle, startVec, endVec);
                    if (nonPhysicsHit == null) {
                        continue;
                    }
                    normalizedResult = new BulletHitResult(targetVehicle, nonPhysicsHit, normalizedResult.isHeadshot());
                }
                double hitDistance = startVec.distanceToSqr(normalizedResult.getLocation());
                if (hitDistance < closestDistance) {
                    closestDistance = hitDistance;
                    closestResult = normalizedResult;
                }
            }
            return closestResult;
        } finally {
            setDeltaMovement(saved);
        }
    }

    /** {@link AmmoEntity#tickHit()} proximity branch; explosion uses {@link #resolveImpactDetonation}. */
    protected boolean tryAmmoProximityFuze(Vec3 startVec, Vec3 endVec) {
        if (rvpData == null) {
            return false;
        }
        float radius = rvpData.getProximityFuseDist();
        if (radius <= 0f && explosion != null && explosion.proximityFuze && explosion.proximityRadius > 0f) {
            radius = explosion.proximityRadius;
        }
        if (radius <= 0f) {
            return false;
        }
        int fuseHeight = rvpData.getFuseData().getProximityFuseHeight();
        Entity target = findProximityTargetOnSegment(startVec, endVec, radius, fuseHeight);
        if (target == null) {
            return false;
        }
        float resolvedRadius = radius;
        RVP_ProjectileLifecycleDebug.noteEvent(this,
                RVP_ProjectileLifecycleDebug.Event.FUSE,
                () -> "type=PROXIMITY_SWEPT radius="
                        + RVP_ProjectileLifecycleDebug.decimal(resolvedRadius)
                        + " target=" + RVP_ProjectileLifecycleDebug.formatEntity(target)
                        + " segment=" + RVP_ProjectileLifecycleDebug.formatVec(startVec)
                        + "->" + RVP_ProjectileLifecycleDebug.formatVec(endVec));
        detonateFuseAt(position(), FuseDetonation.PROXIMITY, target);
        return true;
    }

    @Nullable
    protected Entity findProximityTargetOnSegment(Vec3 startVec, Vec3 endVec, float radius, int fuseHeight) {
        Vec3 step = endVec.subtract(startVec);
        if (step.lengthSqr() < 1.0E-12 || radius <= 0f) {
            return null;
        }
        AABB detectionBox = getBoundingBox().expandTowards(step).inflate(radius);
        List<Entity> nearbyEntities = level().getEntities(this, detectionBox,
                entity -> canDamageEntity(entity) && !isProximityFuseTargetTooLow(entity, fuseHeight)
                        && (!rvpData.isAntiRadiationMissile() || hasActiveRadar(entity))
                        && !isProximityDamageImmune(entity));
        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : nearbyEntities) {
            AABB inflated = entity.getBoundingBox().inflate(radius);
            Vec3 hitPoint = null;
            if (inflated.contains(startVec)) {
                hitPoint = startVec;
            } else if (inflated.contains(endVec)) {
                hitPoint = endVec;
            } else {
                Optional<Vec3> clip = inflated.clip(startVec, endVec);
                if (clip.isPresent()) {
                    hitPoint = clip.get();
                }
            }
            if (hitPoint == null) {
                continue;
            }
            double hitDistance = startVec.distanceToSqr(hitPoint);
            if (hitDistance < closestDistance) {
                closestDistance = hitDistance;
                closest = entity;
            }
        }
        return closest;
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
            BlockHitResult debugHit = result;
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.BLOCK_HIT,
                    () -> "block=" + debugHit.getBlockPos()
                            + " face=" + debugHit.getDirection()
                            + " position=" + RVP_ProjectileLifecycleDebug.formatVec(debugHit.getLocation())
                            + " remainingWallPenetration=" + wallPenetrationLeft);
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
                BlockPos penetratedPos = blockPos;
                RVP_ProjectileLifecycleDebug.noteEvent(this,
                        RVP_ProjectileLifecycleDebug.Event.WALL_PENETRATION,
                        () -> "block=" + penetratedPos
                                + " remaining=" + wallPenetrationLeft
                                + " penetrationEvents=" + penetrationEventCount
                                + " velocity=" + RVP_ProjectileLifecycleDebug.formatVec(getDeltaMovement()));
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
            compensateImpactTrail(hit);
            discard();
            return true;
        }
        if (trySubmunitionTrigger(RVP_EnumSubmunitionTrigger.ON_BLOCK_HIT)
                || trySubmunitionTrigger(RVP_EnumSubmunitionTrigger.ON_IMPACT)) {
            compensateImpactTrail(hit);
            discard();
            return true;
        }
        resolveImpactDetonation(hit, result, true);
        compensateImpactTrail(hit);
        discard();
        return true;
    }

    protected BlockHitResult clipBlockSegment(Vec3 from, Vec3 to) {
        return level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
    }

    private void handleEntityImpact(BulletHitResult result) {
        Entity entity = result.getEntity();
        RVP_ProjectileLifecycleDebug.noteEvent(this,
                RVP_ProjectileLifecycleDebug.Event.ENTITY_HIT,
                () -> "target=" + RVP_ProjectileLifecycleDebug.formatEntity(entity)
                        + " position=" + RVP_ProjectileLifecycleDebug.formatVec(result.getLocation())
                        + " headshot=" + result.isHeadshot()
                        + " remainingLivingPenetration=" + livingPenetrationLeft);
        if (entity instanceof AbstractVehicle targetVehicle
                && ywzj_rvp$isOnlyPhysicsOnlyVehicleHit(targetVehicle, collisionSegmentStart(), collisionSegmentEnd())) {
            setPos(RVP_WallPenetrationUtil.positionPastEntityHit(result.getLocation(), getDeltaMovement()));
            return;
        }
        if (tryBounceFromEntityHit(result)) {
            return;
        }
        Vec3 velocity = getDeltaMovement();
        Vec3 normal = RVP_BounceUtil.impactNormal(
                entity, collisionSegmentStart(), collisionSegmentEnd(), result.getLocation(), velocity);
        rememberImpactIncidence(velocity, normal);
        boolean hbmFuseTriggered = RVP_RadarContactHelper.triggerHbmMissileFuze(entity, result.getLocation());
        if (!hbmFuseTriggered) {
            applyEntityHitDamage(entity, result);
        }
        if (entity instanceof LivingEntity && livingPenetrationLeft > 0) {
            livingPenetrationLeft--;
            piercedLivingIds.add(entity.getId());
            applyPenetrationSpeedDecay();
            penetrationEventCount++;
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.LIVING_PENETRATION,
                    () -> "target=" + RVP_ProjectileLifecycleDebug.formatEntity(entity)
                            + " remaining=" + livingPenetrationLeft
                            + " penetrationEvents=" + penetrationEventCount
                            + " velocity=" + RVP_ProjectileLifecycleDebug.formatVec(getDeltaMovement()));
            setPos(RVP_WallPenetrationUtil.positionPastEntityHit(result.getLocation(), getDeltaMovement()));
            return;
        }
        Vec3 hitPos = result.getLocation();
        if (trySubmunitionTrigger(RVP_EnumSubmunitionTrigger.ON_ENTITY_HIT)
                || trySubmunitionTrigger(RVP_EnumSubmunitionTrigger.ON_IMPACT)) {
            compensateImpactTrail(hitPos);
            discard();
            return;
        }
        resolveImpactDetonation(hitPos, null, false, hbmFuseTriggered ? entity : null);
        if (explosion != null && explosion.explode) {
            compensateImpactTrail(hitPos);
            discard();
            return;
        }
        compensateImpactTrail(hitPos);
        discard();
    }

    protected void applyEntityHitDamage(Entity entity, BulletHitResult result) {
        Entity hitRoot = ywzj_rvp$resolveCollisionRoot(entity);
        if (hitRoot != null) {
            if (proximityDamagedIds.contains(hitRoot.getId())) {
                // 该目标已吃近炸全额伤害：直击不再叠加（互斥）
                return;
            }
            directHitIds.add(hitRoot.getId());
        }
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
        float resolvedHitboxMult = hitboxMult;
        RVP_ProjectileLifecycleDebug.noteEvent(this,
                RVP_ProjectileLifecycleDebug.Event.DIRECT_DAMAGE,
                () -> "target=" + RVP_ProjectileLifecycleDebug.formatEntity(entity)
                        + " headshot=" + headshot
                        + " base=" + RVP_ProjectileLifecycleDebug.decimal(base)
                        + " final=" + RVP_ProjectileLifecycleDebug.decimal(finalDamage)
                        + " distanceMult=" + RVP_ProjectileLifecycleDebug.decimal(distanceMult)
                        + " incidenceMult=" + RVP_ProjectileLifecycleDebug.decimal(incidenceMult)
                        + " penetrationMult=" + RVP_ProjectileLifecycleDebug.decimal(penetrationMult)
                        + " vehicleMult=" + RVP_ProjectileLifecycleDebug.decimal(vehicleMult)
                        + " hitboxMult=" + RVP_ProjectileLifecycleDebug.decimal(resolvedHitboxMult));
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
        if (entity instanceof AbstractVehicle targetVehicleForHurt
                && targetVehicleForHurt instanceof RVP_VehicleHitboxRuntimeAccess access) {
            access.rvp$pushSkipGlobalVehicleHurtScaling();
            try {
                EntityUtil.hurt(source, entity, finalDamage);
            } finally {
                access.rvp$popSkipGlobalVehicleHurtScaling();
            }
        } else {
            EntityUtil.hurt(source, entity, finalDamage);
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
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.FUSE,
                    () -> "type=BOUNCE position="
                            + RVP_ProjectileLifecycleDebug.formatVec(position()));
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
        RVP_ProjectileLifecycleDebug.noteEvent(this,
                RVP_ProjectileLifecycleDebug.Event.BOUNCE,
                () -> "position=" + RVP_ProjectileLifecycleDebug.formatVec(hitLocation)
                        + " normal=" + RVP_ProjectileLifecycleDebug.formatVec(normal)
                        + " reflectedVelocity=" + RVP_ProjectileLifecycleDebug.formatVec(reflected)
                        + " remaining=" + bounceLeft
                        + " fuseCountdown=" + bounceFuseCountdown);
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
        if (ywzj_rvp$isEntityAttachedToShooterVehicle(entity)) {
            return false;
        }
        // 同一载具发射的其它弹药不互伤（含近炸和直击）
        if (entity instanceof AmmoEntity otherAmmo && otherAmmo.vehicle != null && otherAmmo.vehicle == vehicle) {
            return false;
        }
        Entity rootEntity = ywzj_rvp$resolveCollisionRoot(entity);
        return vehicle == null || rootEntity == null || !vehicle.getPassengers().contains(rootEntity);
    }

    @Nullable
    private BulletHitResult ywzj_rvp$normalizeBulletHitResult(Entity fallbackEntity, BulletHitResult result) {
        Entity hitEntity = result.getEntity() == this ? fallbackEntity : result.getEntity();
        Entity rootEntity = ywzj_rvp$resolveCollisionRoot(hitEntity);
        if (rootEntity == null) {
            return null;
        }
        if (rootEntity == hitEntity) {
            return new BulletHitResult(hitEntity, result.getLocation(), result.isHeadshot());
        }
        if (!rootEntity.isAlive()) {
            return null;
        }
        return new BulletHitResult(rootEntity, result.getLocation(), result.isHeadshot());
    }

    private boolean ywzj_rvp$isOnlyPhysicsOnlyVehicleHit(AbstractVehicle targetVehicle, Vec3 startVec, Vec3 endVec) {
        return !RVP_PhysicsOnlyCollisionHelper.getPhysicsOnlyCubes(targetVehicle).isEmpty()
                && RVP_PhysicsOnlyCollisionHelper.closestNonPhysicsOnlyHitPosition(targetVehicle, startVec, endVec) == null
                && RVP_PhysicsOnlyCollisionHelper.closestPhysicsOnlyHitPosition(targetVehicle, startVec, endVec) != null;
    }

    @Nullable
    private Entity ywzj_rvp$resolveCollisionRoot(@Nullable Entity entity) {
        Entity current = entity;
        int guard = 0;
        while (current instanceof PartEntity<?> partEntity && guard++ < 8) {
            current = partEntity.getParent();
        }
        return current;
    }

    private boolean ywzj_rvp$isEntityAttachedToShooterVehicle(@Nullable Entity entity) {
        Entity rootEntity = ywzj_rvp$resolveCollisionRoot(entity);
        if (rootEntity == null) {
            return false;
        }
        if (rootEntity == this || rootEntity == getOwner()) {
            return true;
        }
        if (rootEntity == vehicle || rootEntity == shooterVehicle) {
            return true;
        }
        Entity owner = getOwner();
        if (owner != null && rootEntity.isPassengerOfSameVehicle(owner)) {
            return true;
        }
        return shooterVehicle != null && shooterVehicle.getPassengers().contains(rootEntity);
    }

    protected boolean checkShooterValid() {
        if (shooterVehicle == null && getOwner() == null) {
            return false;
        }
        // 注意：不再检查 shooterVehicle.isAlive()，允许弹药在发射者载具被摧毁后继续飞行
        Entity shooter = getOwner() != null ? getOwner() : shooterVehicle;
        if (shooter == null) {
            return true;
        }
        // 不再限制导弹飞行距离
        //double dx = getX() - shooter.getX();
        //double dz = getZ() - shooter.getZ();
        //return dx * dx + dz * dz < 3.38724E7D;
        return true;
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
        resolveImpactDetonation(pos, blockHit, blockImpact, null);
    }

    protected void resolveImpactDetonation(Vec3 pos, @org.jetbrains.annotations.Nullable BlockHitResult blockHit,
                                           boolean blockImpact, @Nullable Entity excludeEntity) {
        if (rvpData == null) {
            triggerExplosion(pos, FuseDetonation.NORMAL, excludeEntity);
            return;
        }
        org.ywzj.rvp.weapon.data.RVP_DetonateData detonate = rvpData.getDetonateData();
        boolean applyDispenser = shouldApplyDispenser() && !dispenserOnlyImpact();
        if (detonate.isEffectsBeforeExplosion()) {
            if (applyDispenser) {
                applyDispenserAt(pos, blockHit);
            }
            applyDetonateAt(pos, blockHit, blockImpact);
            triggerExplosion(pos, FuseDetonation.NORMAL, excludeEntity);
        } else {
            triggerExplosion(pos, FuseDetonation.NORMAL, excludeEntity);
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
        org.ywzj.rvp.weapon.data.RVP_DetonateData detonateData = rvpData != null ? rvpData.getDetonateData() : null;
        boolean suppressNativeExplosionEffect = false;
        if (detonateData != null && detonateData.hasHbmEffect() && level() instanceof ServerLevel serverLevel) {
            RVP_HbmEffectBridge.Result hbmResult =
                    RVP_HbmEffectBridge.apply(serverLevel, pos, detonateData.getHbmEffectData(), getOwner());
            if (hbmResult.realExplosionApplied()) {
                return;
            }
            suppressNativeExplosionEffect = hbmResult.visualApplied()
                    && detonateData.getHbmEffectData().isSuppressNativeExplosionEffect();
        }
        if (explosion == null || !explosion.explode) {
            RVP_ProjectileLifecycleDebug.noteEvent(this,
                    RVP_ProjectileLifecycleDebug.Event.EXPLOSION,
                    () -> "kind=" + kind + " skipped=no_explosion_config position="
                            + RVP_ProjectileLifecycleDebug.formatVec(pos));
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
        float resolvedDamage = damage;
        float resolvedRadius = radius;
        boolean resolvedSuppressNative = suppressNativeExplosionEffect;
        RVP_ProjectileLifecycleDebug.noteEvent(this,
                RVP_ProjectileLifecycleDebug.Event.EXPLOSION,
                () -> "kind=" + kind
                        + " position=" + RVP_ProjectileLifecycleDebug.formatVec(pos)
                        + " damage=" + RVP_ProjectileLifecycleDebug.decimal(resolvedDamage)
                        + " radius=" + RVP_ProjectileLifecycleDebug.decimal(resolvedRadius)
                        + " destroyBlock=" + explosion.destroyBlock
                        + " exclude=" + RVP_ProjectileLifecycleDebug.formatEntity(excludeEntity)
                        + " suppressNativeVisual=" + resolvedSuppressNative);
        VehicleExplosion ex = new VehicleExplosion(level(), getOwner(), vehicle, pos,
                radius, damage, explosion.destroyBlock);
        Set<Entity> excluded = new HashSet<>();
        if (excludeEntity != null) {
            excluded.add(excludeEntity);
        }
        if (getOwner() instanceof org.ywzj.rvp.entity.gunner.GunnerEntity) {
            excluded.add(getOwner());
            if (shooterVehicle != null) {
                excluded.add(shooterVehicle);
            }
        }
        Runnable explosionAction = !excluded.isEmpty()
                ? () -> ex.explode(List.copyOf(excluded))
                : ex::explode;
        if (suppressNativeExplosionEffect) {
            RVP_ExplosionVisualSuppression.run(explosionAction);
        } else {
            explosionAction.run();
        }
        if (!suppressNativeExplosionEffect && level() instanceof ServerLevel serverLevel && rvpData != null) {
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
        RVP_ProjectileLifecycleDebug.noteEvent(this,
                RVP_ProjectileLifecycleDebug.Event.FUSE,
                () -> "type=" + kind
                        + " position=" + RVP_ProjectileLifecycleDebug.formatVec(pos)
                        + " proximityTarget=" + RVP_ProjectileLifecycleDebug.formatEntity(proximityTarget));
        if (trySubmunitionTrigger(RVP_EnumSubmunitionTrigger.ON_FUSE)) {
            discard();
            return;
        }
        Entity resolvedProximityTarget = kind == FuseDetonation.PROXIMITY
                ? ywzj_rvp$resolveProximityDamageTarget(proximityTarget)
                : proximityTarget;
        boolean hadGuaranteedDamage = false;
        if (kind == FuseDetonation.PROXIMITY && resolvedProximityTarget != null && rvpData != null) {
            if (resolvedProximityTarget instanceof AmmoEntity) {
                // 弹药实体逻辑不变：近炸触发后强制将其引爆/销毁（防空拦截敌方导弹）
                if (resolvedProximityTarget instanceof RVP_BaseBullet targetBullet) {
                    targetBullet.rvp$detonateByAps();
                } else if (resolvedProximityTarget instanceof AmmoEntity targetAmmo) {
                    targetAmmo.discard();
                }
            } else if (!directHitIds.contains(resolvedProximityTarget.getId())
                    && !proximityDamagedIds.contains(resolvedProximityTarget.getId())) {
                // 非弹药实体：直击与近炸互斥，同一目标不叠加第二次伤害。
                // 强制对触发近炸的目标造成全额爆炸伤害，不依赖 VehicleExplosion 距离衰减
                // （修复高速目标炸不到的 bug）；触发即全额，目标随后逃出范围也照样扣满。
                float guaranteed = rvpData.getProximityFuseDirectDamage();
                if (guaranteed <= 0f) {
                    guaranteed = rvpData.resolveProximityFuseExplosionDamage();
                }
                if (guaranteed <= 0f && explosion != null) {
                    guaranteed = explosion.damage;
                }
                if (RVP_RadarContactHelper.triggerHbmMissileFuze(resolvedProximityTarget, pos)) {
                    hadGuaranteedDamage = true;
                } else if (guaranteed > 0f) {
                    guaranteed = RVP_DamageApplier.applyScaled(guaranteed, resolvedProximityTarget, rvpData);
                    DamageSource source = AllDamageTypes.Sources.explosion(
                            level().registryAccess(), this, getOwner(), pos);
                    resolvedProximityTarget.hurt(source, guaranteed);
                    proximityDamagedIds.add(resolvedProximityTarget.getId());
                    hadGuaranteedDamage = true;
                }
            }
        }
        // 已吃全额近炸的目标排除在 VehicleExplosion 之外，避免二次伤害
        Entity exclude = hadGuaranteedDamage ? resolvedProximityTarget : null;
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

    @Nullable
    private Entity ywzj_rvp$resolveProximityDamageTarget(@Nullable Entity target) {
        Entity resolved = ywzj_rvp$resolveCollisionRoot(target);
        if (resolved == null || !resolved.isAlive()) {
            return null;
        }
        return canDamageEntity(resolved) ? resolved : null;
    }

    protected void explodeAndDiscard(Vec3 pos) {
        if (dispenserOnlyImpact()) {
            applyDispenserAt(pos, lastBlockHit);
            discard();
            return;
        }
        detonateFuseAt(pos, FuseDetonation.NORMAL);
    }

    public void rvp$detonateByAps() {
        Vec3 pos = position();
        RVP_WeaponData config = resolveWeaponConfig();
        if (config == null) {
            triggerExplosion(pos, FuseDetonation.NORMAL, null);
            discard();
            return;
        }
        org.ywzj.rvp.weapon.data.RVP_DetonateData detonate = config.getDetonateData();
        if (detonate.isEffectsBeforeExplosion()) {
            applyDetonateAt(pos, null, false);
            triggerExplosion(pos, FuseDetonation.NORMAL, null);
        } else {
            triggerExplosion(pos, FuseDetonation.NORMAL, null);
            applyDetonateAt(pos, null, false);
        }
        discard();
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
        int placed;
        if (blockHit != null) {
            placed = RVP_DispenserPlacement.placeAtHit(serverLevel, blockHit, payload, getOwner());
        } else {
            placed = RVP_DispenserPlacement.placeAtPosition(serverLevel, pos, payload, getOwner(), lastBlockHit);
        }
        int placedCount = placed;
        RVP_ProjectileLifecycleDebug.noteEvent(this,
                RVP_ProjectileLifecycleDebug.Event.DISPENSER,
                () -> "position=" + RVP_ProjectileLifecycleDebug.formatVec(pos)
                        + " blockHit=" + (blockHit != null)
                        + " placed=" + placedCount);
        return placed;
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

    private void resolveRemoteRefs() {
        if (remoteOwnerId >= 0) {
            Entity owner = level().getEntity(remoteOwnerId);
            if (owner != null) {
                setOwner(owner);
            }
        }
        if (remoteShooterVehicleId >= 0) {
            Entity entity = level().getEntity(remoteShooterVehicleId);
            if (entity instanceof AbstractVehicle vehicle) {
                shooterVehicle = vehicle;
                this.vehicle = vehicle;
            }
        }
    }

    private static void writeRemoteVec3(CompoundTag data, String key, @Nullable Vec3 vec) {
        if (vec == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", vec.x);
        tag.putDouble("y", vec.y);
        tag.putDouble("z", vec.z);
        data.put(key, tag);
    }

    @Nullable
    private static Vec3 readRemoteVec3(CompoundTag data, String key) {
        if (!data.contains(key, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag tag = data.getCompound(key);
        return new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
    }

    protected void spawnTrailParticles() {
        boolean motorBurning = isMotorBurning();
        RVP_WeaponData config = resolveWeaponConfig();
        RVP_EffectsData effects = config != null ? config.getEffectsData() : new RVP_EffectsData();
        boolean missileNativeTrail = this instanceof RVP_MissileEntity;
        if (!motorBurning) {
            trailMotorBurningO = false;
            return;
        }
        if (missileNativeTrail && !effects.isMissileNativeTrailEnabled()) {
            trailMotorBurningO = false;
            return;
        }
        if (missileNativeTrail) {
            spawnMissileNativeTrailParticles(effects);
            return;
        }

        boolean heavy = isHeavyProjectile();
        Vec3 pos = this.position().add(this.getLookAngle().scale(-3));
        if (!trailMotorBurningO || trailParticleTickO != getFlightTickCount() - 1) {
            particlePosO = pos;
        } else if (particlePosO == null) {
            particlePosO = pos;
        }
        Vec3 step = pos.subtract(particlePosO);
        double dist = step.length();
        int segments = Math.max(0, (int) (dist / 0.5D));
        Vec3 dir = dist > 1.0E-6D ? step.normalize() : Vec3.ZERO;
        String configured = config != null ? effects.getTrajectoryParticle() : "";
        ParticleOptions primary = resolveParticle(configured, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE);
        if (primary != null) {
            double spacing = segments <= 0 ? 0.0D : dist / segments;
            for (int i = 0; i <= segments; i++) {
                Vec3 particlePos = segments <= 0 ? pos : particlePosO.add(dir.scale(i * spacing));
                level().addParticle(primary, true,
                        particlePos.x, particlePos.y, particlePos.z,
                        0.0D, 0.0D, 0.0D);
            }
        }
        if (isMotorPropulsion() && getFlightTickCount() % 2 == 0) {
            level().addParticle(ParticleTypes.FLAME, true,
                    getX(), getY(), getZ(),
                    -getDeltaMovement().x * 0.02, -getDeltaMovement().y * 0.02, -getDeltaMovement().z * 0.02);
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true,
                    getX(), getY(), getZ(),
                    -getDeltaMovement().x * 0.01, 0.05, -getDeltaMovement().z * 0.01);
        }
        particlePosO = pos;
        trailParticleTickO = getFlightTickCount();
        trailMotorBurningO = true;
    }

    private void spawnMissileNativeTrailParticles(RVP_EffectsData effects) {
        Vec3 pos = this.position().add(this.getLookAngle().scale(-effects.getMissileNativeTrailOffset()));
        if (!trailMotorBurningO || trailParticleTickO != getFlightTickCount() - 1 || particlePosO == null) {
            particlePosO = pos;
        }
        ParticleOptions primary = resolveParticle(
                effects.hasMissileNativeTrailParticleOverride() ? effects.getMissileNativeTrailParticle() : "",
                ParticleTypes.CAMPFIRE_SIGNAL_SMOKE
        );
        int spawnInterval = effects.getMissileNativeTrailSpawnIntervalTick();
        if (primary != null && getFlightTickCount() % spawnInterval == 0) {
            Vec3 posO = particlePosO == null ? pos : particlePosO;
            Vec3 step = pos.subtract(posO);
            double dist = step.length();
            double segmentStep = effects.getMissileNativeTrailStep();
            int baseSegments = (int) (dist / segmentStep);
            float densityScale = effects.getMissileNativeTrailDensityScale();
            int segments = densityScale <= 0f ? -1 : Math.max(0, Math.round(baseSegments * densityScale));
            if (segments >= 0) {
                Vec3 dir = dist > 1.0E-6D ? step.normalize() : Vec3.ZERO;
                double spacing = segments <= 0 ? 0.0D : dist / segments;
                for (int i = 0; i <= segments; i++) {
                    Vec3 particlePos = segments <= 0 ? pos : posO.add(dir.scale(i * spacing));
                    level().addParticle(primary, true,
                            particlePos.x, particlePos.y, particlePos.z,
                            0.0D, 0.0D, 0.0D);
                }
            }
        }
        particlePosO = pos;
        trailParticleTickO = getFlightTickCount();
        trailMotorBurningO = true;
    }

    protected boolean isHeavyProjectile() {
        return this instanceof RVP_MissileEntity || this instanceof RVP_RocketEntity
                || this instanceof RVP_BombEntity || this instanceof RVP_DispensedEntity;
    }

    /** 是否有火箭推进发动机（推力弹道）。 */
    protected boolean isMotorPropulsion() {
        RVP_WeaponData config = resolveWeaponConfig();
        return config != null && config.usesPropulsion();
    }

    /** 发动机当前是否在燃烧期内（对标本体 MissileEntity.tickParticle）。非推进弹体始终返回 true。 */
    protected boolean isMotorBurning() {
        // 客户端 rvpData 为 null，用生成数据包同步的 motorBurnEndTick
        if (rvpData == null) {
            if (getFlightTickCount() <= motorBurnEndTick) {
                return true;
            }
            int start = this.entityData.get(DATA_SECOND_PULSE_START_TICK);
            int burn = this.entityData.get(DATA_SECOND_PULSE_BURN_TIME_TICK);
            if (start >= 0 && burn > 0) {
                int t2 = getFlightTickCount() - start;
                return t2 >= 0 && t2 <= burn;
            }
            return false;
        }
        if (!isMotorPropulsion()) {
            return true;
        }
        if (rvpData.getProjectileData().usesSecondPulse() && isMissile()) {
            int ignition = rvpData.getResolvedIgnitionDelayTick();
            float burnTime = rvpData.getResolvedMotorBurnTime();
            int motorTick = getFlightTickCount() - ignition;
            if (motorTick >= 0 && motorTick <= burnTime) {
                return true;
            }
            int start = secondPulseStartTick;
            if (start >= 0) {
                int t2 = getFlightTickCount() - start;
                float burn2 = rvpData.getProjectileData().getResolvedSecondPulseBurnTime();
                return t2 >= 0 && t2 <= burn2;
            }
            return false;
        }
        int ignition = rvpData.getResolvedIgnitionDelayTick();
        float burnTime = rvpData.getResolvedMotorBurnTime();
        int motorTick = getFlightTickCount() - ignition;
        return motorTick >= 0 && motorTick <= burnTime;
    }

    public final boolean isMotorBurningNow() {
        return isMotorBurning();
    }

    public final int getSecondPulseStartTick() {
        return secondPulseStartTick;
    }

    protected void broadcastTrailParticles() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        RVP_WeaponData config = resolveWeaponConfig();
        if (config == null) {
            return;
        }
        String configured = config.getEffectsData().getTrajectoryParticle();
        boolean heavy = isHeavyProjectile();
        boolean motorBurning = isMotorBurning();
        // 轨迹粒子：推进类弹体仅在燃烧期发送
        if ((!heavy || motorBurning) || !isMotorPropulsion()) {
            ParticleOptions primary = resolveParticle(configured,
                    heavy ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE);
            if (primary != null) {
                // 轻弹（机炮/破片）：count 2、spread 0.1 —— 让短命的下坠破片也有可辨识尾迹
                double spread = 0.1;
                int count = heavy ? 3 : 2;
                serverLevel.sendParticles(primary, getX(), getY(), getZ(), count, spread, spread, spread, 0.01);
                trailBroadcastCount++;
                if (RVP_TopAttackDebug.isEnabled()) {
                    RVP_TopAttackDebug.noteTick(this, "TRAIL particle="
                            + (configured == null ? ("fallback:" + primary) : configured)
                            + " count=" + count + " heavy=" + heavy
                            + " motorBurn=" + motorBurning + " motorProp=" + isMotorPropulsion()
                            + " pos=(" + String.format("%.1f,%.1f,%.1f", getX(), getY(), getZ()) + ")");
                }
            }
        }
        // 尾焰：仅推进类弹体燃烧期
        if (heavy && isMotorPropulsion() && motorBurning) {
            serverLevel.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 1, 0.03, 0.03, 0.03, 0.002);
        }
    }

    /**
     * 命中瞬间补渲轨迹粒子（effects_data.impact_trail_particles）。
     *
     * <p>当弹体飞行时间过短（从未广播过轨迹粒子就命中死亡，如 1 tick 落地的下坠破片，
     * pre-motion 命中死亡导致 post-motion 的 {@link #broadcastTrailParticles()} 永不执行）
     * 且配置开启本开关时，在命中点沿来袭方向补渲一段轨迹粒子簇，避免"看不见弹道"。</p>
     */
    protected void compensateImpactTrail(Vec3 hitPos) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // 只要此前广播过轨迹粒子（飞行过一段时间），就不再补渲
        if (trailBroadcastCount > 0) {
            return;
        }
        RVP_WeaponData config = resolveWeaponConfig();
        if (config == null || !config.getEffectsData().isImpactTrailParticles()) {
            return;
        }
        String configured = config.getEffectsData().getTrajectoryParticle();
        boolean heavy = isHeavyProjectile();
        ParticleOptions primary = resolveParticle(configured,
                heavy ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE);
        if (primary == null) {
            return;
        }
        // 沿来袭方向补渲一段尾迹（从命中点向后约 1.5 米）
        Vec3 delta = getDeltaMovement();
        double len = delta.length();
        Vec3 back = len > 1.0E-4 ? delta.scale(-1.0 / len) : new Vec3(0, 1, 0);
        Vec3 tailStart = hitPos.add(back.scale(1.5));
        double step = 0.3;
        for (double d = 0; d <= 1.5 + 0.001; d += step) {
            Vec3 p = tailStart.subtract(back.scale(d));
            serverLevel.sendParticles(primary, p.x, p.y, p.z, 1, 0.06, 0.06, 0.06, 0.01);
        }
        // 命中点处补一簇粒子强调落点
        serverLevel.sendParticles(primary, hitPos.x, hitPos.y, hitPos.z, 4, 0.15, 0.15, 0.15, 0.02);
        if (RVP_TopAttackDebug.isEnabled()) {
            RVP_TopAttackDebug.noteTick(this, "COMPENSATE_TRAIL particle="
                    + (configured == null ? ("fallback:" + primary) : configured)
                    + " hit=(" + String.format("%.1f,%.1f,%.1f", hitPos.x, hitPos.y, hitPos.z) + ")");
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

    protected void refreshMotorBurnWindow() {
        if (rvpData != null && rvpData.usesPropulsion()) {
            int ignition = Math.max(rvpData.getResolvedIgnitionDelayTick(), coldLaunchTimeTick);
            int burnTicks = Math.round(rvpData.getResolvedMotorBurnTime());
            this.motorBurnEndTick = ignition + burnTicks;
        } else {
            this.motorBurnEndTick = Integer.MAX_VALUE;
        }
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        super.writeSpawnData(buffer);
        buffer.writeEnum(getWeaponKind());
        buffer.writeFloat(getXRot());
        buffer.writeFloat(getYRot());
        buffer.writeDouble(getDeltaMovement().x);
        buffer.writeDouble(getDeltaMovement().y);
        buffer.writeDouble(getDeltaMovement().z);
        buffer.writeDouble(flightSpeed);
        buffer.writeVarInt(motorBurnEndTick);
        buffer.writeVarInt(coldLaunchTimeTick);
        buffer.writeDouble(coldLaunchVelocity.x);
        buffer.writeDouble(coldLaunchVelocity.y);
        buffer.writeDouble(coldLaunchVelocity.z);
        buffer.writeBoolean(showMslIndicator);
        buffer.writeFloat(signatureSize);
        buffer.writeBoolean(launchTargetSnapshot);
        buffer.writeVarInt(Math.max(irSeekerGraceUntilTick, Integer.MIN_VALUE + 1));
        buffer.writeVarInt(targetEntity != null ? targetEntity.getId() : 0);
        buffer.writeBoolean(targetPos != null);
        if (targetPos != null) {
            buffer.writeDouble(targetPos.x);
            buffer.writeDouble(targetPos.y);
            buffer.writeDouble(targetPos.z);
        }
        buffer.writeBoolean(gpsTargetOffset != null);
        if (gpsTargetOffset != null) {
            buffer.writeDouble(gpsTargetOffset.x);
            buffer.writeDouble(gpsTargetOffset.y);
            buffer.writeDouble(gpsTargetOffset.z);
        }
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        super.readSpawnData(buffer);
        this.weaponKind = buffer.readEnum(RVP_EnumWeaponKind.class);
        setXRot(buffer.readFloat());
        setYRot(buffer.readFloat());
        setDeltaMovement(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        this.flightSpeed = buffer.readDouble();
        this.motorBurnEndTick = buffer.readVarInt();
        this.coldLaunchTimeTick = buffer.readVarInt();
        this.coldLaunchVelocity = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        this.showMslIndicator = buffer.readBoolean();
        this.signatureSize = buffer.readFloat();
        this.launchTargetSnapshot = buffer.readBoolean();
        this.irSeekerGraceUntilTick = buffer.readVarInt();
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
        if (buffer.readBoolean()) {
            this.gpsTargetOffset = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            this.gpsTargetOffsetResolved = true;
        } else {
            this.gpsTargetOffset = null;
            this.gpsTargetOffsetResolved = false;
        }
    }
}
