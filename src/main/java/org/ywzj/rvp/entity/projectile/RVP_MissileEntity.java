package org.ywzj.rvp.entity.projectile;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.PlayMessages;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.RVP_GuidanceMath;
import org.ywzj.rvp.guidance.RVP_HitlSeekerUtil;
import org.ywzj.rvp.guidance.RVP_HitlSteeringMath;
import org.ywzj.rvp.guidance.RVP_TvVideoModeMask;
import org.ywzj.rvp.weapon.AntiRadiationSeekerHelper;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CEnterHitlView;
import org.ywzj.rvp.network.S2CHitlLinkState;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_HumanInTheLoopData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.rvp.ext.WeaponUnitArmExt;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;
import java.util.List;
import java.util.function.Function;

/**
 * Generic guided missile entity for {@code rvp:missile}.
 *
 * <p>MCLOS wire / TV+MCLOS 走分段制导（{@link org.ywzj.rvp.guidance.RVP_GuidanceController} +
 * {@code take_over_motion}）。HITL MOUSE 鼠标指令写入 {@code hitlInputYaw/Pitch}，
 * 弹体 {@code hitlSteeringYaw/Pitch} 直接跟随鼠标指令航向参与制导。</p>
 */
public class RVP_MissileEntity extends RVP_BaseBullet {

    public static final int HITL_MODE_COLOR = RVP_TvVideoModeMask.COLOR;
    public static final int HITL_MODE_BW = RVP_TvVideoModeMask.BW;
    public static final int HITL_MODE_THERMAL = RVP_TvVideoModeMask.THERMAL;
    public static final int HITL_MODE_ALL = RVP_TvVideoModeMask.ALL;

    private float hitlControlRange = 2000f;
    private int hitlTimeoutTick = 200;
    private int hitlLife = 200;
    private int hitlVideoModeMask = HITL_MODE_ALL;
    private int hitlDefaultVideoMode = HITL_MODE_COLOR;
    private RVP_EnumHitlControlMode hitlControlMode = RVP_EnumHitlControlMode.VIEW;
    private boolean hitlEnabled;
    private float hitlMaxTurnDegPerTick = RVP_HitlSteeringMath.DEFAULT_MAX_TURN_DEG_PER_TICK;
    private float hitlMaxLookOffsetDeg = 20f;
    private float hitlInputYaw;
    private float hitlInputPitch;
    private float hitlSteeringYaw;
    private float hitlSteeringPitch;
    private int hitlInputSeq = Integer.MIN_VALUE;
    private RVP_HumanInTheLoopData.SignalSource hitlSignalSource = RVP_HumanInTheLoopData.SignalSource.RADIO;
    private boolean hitlLinkBlocked;
    private boolean hitlLinkSevered;
    private int hitlLinkBlockedTicks;
    private boolean hitlLinkLastSentBlocked;
    private boolean hitlLinkLastSentSevered;
    private int hitlEnterViewResendTicks;

    public RVP_MissileEntity(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    public RVP_MissileEntity(EntityType<? extends Projectile> type, Level level, ResourceLocation weaponId) {
        super(type, level, weaponId);
    }

    public RVP_MissileEntity(PlayMessages.SpawnEntity msg, Level level) {
        super(RVP_Entities.RVP_MISSILE.get(), level);
    }

    @Override
    public void initFromWeapon(RVP_WeaponData data, RVP_EnumWeaponKind kind, AbstractVehicle vehicle, LivingEntity shooter,
                               Vec3 spawnPos, AimRot aim, Vec3 initialMotion) {
        super.initFromWeapon(data, kind, vehicle, shooter, spawnPos, aim, initialMotion);
        if (data == null || !data.hasHumanInTheLoop()) {
            return;
        }
        RVP_HumanInTheLoopData hitl = data.getGuidanceData().getHumanInTheLoop();
        this.hitlControlRange = hitl.controlRange(2000f);
        this.hitlTimeoutTick = hitl.timeoutTick(200);
        this.hitlLife = this.hitlTimeoutTick;
        this.hitlVideoModeMask = hitl.videoModeMask();
        this.hitlDefaultVideoMode = hitl.defaultVideoMode();
        this.hitlControlMode = hitl.resolveControlMode(data.getGuidanceData());
        this.hitlMaxTurnDegPerTick = hitl.maxTurnDegPerTick();
        this.hitlMaxLookOffsetDeg = hitl.maxLookOffsetDeg(RVP_HitlSeekerUtil.saclosSeekerHalfFov(data));
        this.hitlSignalSource = hitl.signalSource();
        this.hitlEnabled = true;
        this.hitlEnterViewResendTicks = 5;
        this.hitlInputYaw = aim.yRot();
        this.hitlInputPitch = aim.xRot();
        this.hitlSteeringYaw = aim.yRot();
        this.hitlSteeringPitch = aim.xRot();
    }

    @Override
    protected void tickGuidance() {
        maybeSyncEnterHitlView();
        tickHitlRadioLink();
        if (hitlLinkSevered || hitlLinkBlocked) {
            return;
        }
        tickHitlSession();
        tickHitlMouseInertia();

        if (level().isClientSide()) {
            super.tickGuidance();
            return;
        }

        // ===== ARM 反辐射弹：独立制导，不经过 stage 系统（对标本体 SeadMissileEntity） =====
        if (isArmMissile()) {
            tickArmGuidance();
            return;
        }

        // ===== ARH 主动雷达弹：目标管理嵌入，转向走 stage 系统 =====
        if (isArhMissile()) {
            tickArhTargetManagement();
        }

        super.tickGuidance();
    }

    private void maybeSyncEnterHitlView() {
        if (level().isClientSide() || hitlEnterViewResendTicks <= 0) {
            return;
        }
        if (rvpData == null || !rvpData.hasHumanInTheLoop() || !hitlEnabled) {
            hitlEnterViewResendTicks = 0;
            return;
        }
        if (!(getOwner() instanceof ServerPlayer player)) {
            hitlEnterViewResendTicks = 0;
            return;
        }
        RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                S2CEnterHitlView.of(getId(), hitlControlMode));
        hitlEnterViewResendTicks--;
    }

    /**
     * 判断当前弹体是否为主动雷达制导（ARH）导弹。
     */
    private boolean isArhMissile() {
        return rvpData != null
                && rvpData.getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                && rvpData.isActiveRadar();
    }

    /**
     * 判断当前弹体是否为反辐射导弹（ARM）。
     */
    private boolean isArmMissile() {
        return rvpData != null
                && rvpData.getWeaponKind() == RVP_EnumWeaponKind.MISSILE
                && rvpData.isAntiRadiationMissile();
    }

    /**
     * ARM 独立制导逻辑。
     * 绕过 stage 系统直接管理 targetPos（对标本体 SeadMissileEntity.tickSeadTrack()）。
     */
    private void tickArmGuidance() {
        if (tickCount == 0) {
            initArmParams();
        }

        boolean canScan = !isAntiRadiationLostPermanent() || armAllowReacquire;
        AntiRadiationSeekerHelper.AntiRadiationEmitter best = null;

        if (canScan && tickCount >= getAntiRadiationNextScanTick()) {
            setAntiRadiationNextScanTick(tickCount + armScanIntervalTick);
            List<AntiRadiationSeekerHelper.AntiRadiationEmitter> emitters =
                    AntiRadiationSeekerHelper.scanVisibleEmitters(
                            level(), position(), getLookAngle(),
                            armSeekerFov, armSeekRange, getShooterVehicle(),
                            tickCount, getRadiationPulseTickMap(), armPulseMemoryTick);

            // STEP 1: 预选目标优先
            int preselectVid = getPreselectedVehicleId();
            int preselectRid = getPreselectedRadarIndex();
            if (preselectVid >= 0) {
                for (var emitter : emitters) {
                    if (emitter.vehicleId() == preselectVid
                            && (preselectRid < 0 || emitter.radarIndex() == preselectRid)) {
                        best = emitter;
                        break;
                    }
                }
            }

            // STEP 2: 无预选则按评分选最佳
            if (best == null) {
                double bestScore = Double.MAX_VALUE;
                for (var emitter : emitters) {
                    double score = AntiRadiationSeekerHelper.score(
                            position(), getLookAngle(),
                            armSeekerFov, armSeekRange, emitter.pdw(), armLockedBonus);
                    if (score < bestScore) {
                        bestScore = score;
                        best = emitter;
                    }
                }
            }
        }

        if (best != null) {
            setAntiRadiationLostPermanent(false);
            int memory = armMemoryTick > 0 ? armMemoryTick
                    : AntiRadiationSeekerHelper.getDefaultMemoryTick(best.radarUnit());
            setAntiRadiationMemoryLeftTick(memory);
            setTargetPos(best.position());
            rememberGuidancePos(best.position());
            // 直接调用转向（不走 stage 系统）
            RVP_GuidanceMath.guidanceToPos(this, best.position());
        } else if (getAntiRadiationMemoryLeftTick() > 0 && getLastGuidancePos() != null) {
            setAntiRadiationMemoryLeftTick(getAntiRadiationMemoryLeftTick() - 1);
            Vec3 memory = getLastGuidancePos();
            setTargetPos(memory);
            RVP_GuidanceMath.guidanceToPos(this, memory);
        } else {
            clearTarget();
            if (!armAllowReacquire) {
                setAntiRadiationLostPermanent(true);
            }
        }
    }

    private boolean armAllowReacquire = true;
    private float armSeekerFov = 70f;
    private float armSeekRange = 2048f;
    private int armScanIntervalTick = 2;
    private int armMemoryTick = 120;
    private int armPulseMemoryTick = 80;
    private float armLockedBonus = 0.5f;

    /**
     * 所有 RVP 导弹统一使用燃料尾焰模式（对标本体 MissileEntity.tickParticle）。
     * 有推进配置的按 {@code motor_burn_time} 计算；无配置的默认燃烧期为 {@code life / 2} tick。
     */
    @Override
    protected boolean isMotorBurning() {
        // 客户端 rvpData 可能为 null，需要同时支持双脉冲第二段的同步燃烧期判断。
        if (rvpData == null) {
            if (tickCount <= motorBurnEndTick) {
                return true;
            }
            int start = this.entityData.get(DATA_SECOND_PULSE_START_TICK);
            int burn = this.entityData.get(DATA_SECOND_PULSE_BURN_TIME_TICK);
            if (start >= 0 && burn > 0) {
                int t2 = tickCount - start;
                return t2 >= 0 && t2 <= burn;
            }
            return false;
        }
        if (!isMotorPropulsion()) {
            // 无发动机配置的导弹：默认燃烧期取一半寿命
            int defaultBurn = Math.max(life / 2, 20);
            return tickCount <= defaultBurn;
        }
        return super.isMotorBurning();
    }

    private void initArmParams() {
        if (rvpData == null) return;
        var stages = rvpData.getGuidanceData().getStages();
        if (stages == null || stages.isEmpty()) return;
        for (var stage : stages) {
            var sources = stage.getSources();
            if (sources == null) continue;
            for (var source : sources) {
                if (source.getType() != RVP_EnumGuidanceType.ARM) continue;
                var params = source.getParams();
                if (params != null) {
                    armAllowReacquire = params.reacquire(true);
                    armSeekerFov = stage.getSeeker().getFov();
                    armSeekRange = stage.getSeeker().getRange();
                    armScanIntervalTick = params.scanIntervalTick(2);
                    armMemoryTick = params.memoryTick(120);
                    armPulseMemoryTick = params.radiationPulseMemoryTick(80);
                    armLockedBonus = params.lockedBonus(0.5f);
                }

                // 从武器挂点复制 HUD 预选目标（对标本体 MissileEntityMixin.initArmParameters）
                WeaponUnit wu = getShooterWeaponUnit();
                if (wu != null) {
                    WeaponUnit rootWu = wu.getRootParentWeaponUnit();
                    if (rootWu instanceof WeaponUnitArmExt armExt) {
                        int preselectVid = armExt.ywzj_rvp$getArmPreselectedVehicleId();
                        int preselectRid = armExt.ywzj_rvp$getArmPreselectedRadarIndex();
                        setPreselectedTarget(preselectVid, preselectRid);
                        // 写入预选目标的坐标作为初始 IOG 记忆点，使导弹立刻朝该点飞行（解决大离轴发射时扫描间隔内丢目标的问题）
                        if (preselectVid >= 0) {
                            Vec3 preselectPos = armExt.ywzj_rvp$getArmPreselectedPos();
                            if (preselectPos != null) {
                                setTargetPos(preselectPos);
                                rememberGuidancePos(preselectPos);
                                setAntiRadiationMemoryLeftTick(armMemoryTick > 0 ? armMemoryTick : 60);
                            }
                        }
                    }
                }
                return;
            }
        }
    }

    /**
     * ARH 目标管理：载机雷达锁续标 → 实时追踪 → 距离触发弹载雷达开机 → 丢锁自毁。
     * 不取代 stage 系统的转向，只维护 targetEntity/targetPos 状态。
     */
    private void tickArhTargetManagement() {
        if (tickCount == 0) {
            initArhParams();
        }

        // 主动雷达截获前：载机雷达必须持续锁定目标（不同于本体仅检测）
        if (!activeRadarCatch && targetEntity != null) {
            WeaponUnit weaponUnit = getShooterWeaponUnit();
            boolean radarStillLocked = false;
            if (weaponUnit != null) {
                RadarUnit radar = weaponUnit.getMainRadarUnit();
                radarStillLocked = radar != null && radar.getLockedEntity() == targetEntity;
            }
            if (!radarStillLocked) {
                targetEntity = null;
            }
        }

        // tickGuidance() HOMING 段：实时追踪 + 主动雷达开机距离检测
        if (tickCount >= 20 && targetEntity != null && targetEntity.isAlive()) {
            targetPos = targetEntity.position().add(0, targetEntity.getBbHeight() * 0.5, 0);
            lastGuidancePos = targetPos;

            if (!activeRadarOn && targetEntity.distanceTo(this) <= activeRadarActivationRange) {
                activeRadarOn = true;
                // 开机提示（action bar 不干扰聊天）
                if (getOwner() instanceof ServerPlayer player) {
                    player.displayClientMessage(Component.literal("主动雷达导引头开机"), true);
                }
            }
        }

        // tickTrack() ACTIVE_RADAR 段：弹载雷达扫描截获
        if (activeRadarOn) {
            List<Entity> detectedEntities = scanArhTargets();
            Entity activeRadarTarget = null;

            if (targetEntity != null) {
                activeRadarTarget = Radar.checkTarget(this, detectedEntities, targetEntity);
                if (activeRadarTarget == targetEntity) {
                    activeRadarCatch = true;
                }
            }

            if (activeRadarTarget == null && !detectedEntities.isEmpty()) {
                targetEntity = detectedEntities.get(0);
                activeRadarCatch = true;
            }
        }

        // 主动雷达丢锁倒计时
        if (activeRadarOn && targetEntity == null) {
            activeRadarLostTargetTick++;
            if (activeRadarLostTargetTick >= 60) {
                life = 0;
            }
        }
    }

    /**
     * 从武器数据的 guidance stage 中读取 ARH 参数（activeRadarActivationRange、seekerFov 等）。
     */
    private void initArhParams() {
        if (rvpData == null) return;
        var stages = rvpData.getGuidanceData().getStages();
        if (stages == null || stages.isEmpty()) return;

        for (var stage : stages) {
            var sources = stage.getSources();
            if (sources == null) continue;
            for (var source : sources) {
                if (source.getType() != RVP_EnumGuidanceType.ARH) continue;
                var params = source.getParams();
                if (params != null) {
                    activeRadarActivationRange = params.activeRadarActivationRange(1024f);
                }
                return;
            }
        }
        // 兜底默认值
        activeRadarActivationRange = 1024f;
    }

    /**
     * 弹载雷达扫描目标（对标本体 MissileEntity.scanTargets()）。
     */
    private List<Entity> scanArhTargets() {
        float fov = 60f;
        // 从 guidance 数据读 seeker FOV
        if (rvpData != null && !rvpData.getGuidanceData().getStages().isEmpty()) {
            fov = rvpData.getGuidanceData().getStages().get(0).getSeeker().getFov();
        }
        final float seekFov = fov;
        return Radar.scanTargets(this, this.position(), activeRadarActivationRange,
                entityPos -> Math.toDegrees(VectorUtil.angleBetween(this.getLookAngle(),
                        entityPos.subtract(this.position()))) <= seekFov);
    }

    @Override
    protected void tickMotion() {
        if (hitlSignalSource == RVP_HumanInTheLoopData.SignalSource.RADIO
                && (hitlLinkBlocked || hitlLinkSevered)
                && hitlControlMode == RVP_EnumHitlControlMode.MOUSE) {
            RVP_ProjectileMotion.tickHitlTvMove(this);
            return;
        }
        if (rvp$guidanceWireDirectApplied()) {
            RVP_ProjectileMotion.tickHitlTvMove(this);
            return;
        }
        super.tickMotion();
    }

    private void tickHitlSession() {
        if (rvpData == null || !rvpData.hasHumanInTheLoop() || !hitlEnabled) {
            return;
        }
        LivingEntity controller = getOwner() instanceof LivingEntity living ? living : null;
        if (controller == null || !controller.isAlive() || controller.isSpectator()) {
            hitlEnabled = false;
            return;
        }
        if (hitlLife <= 0) {
            return;
        }
        hitlLife--;
        double maxRange = Math.max(hitlControlRange, 1.0);
        if (controller.distanceToSqr(this) > maxRange * maxRange) {
            return;
        }
    }

    private void tickHitlRadioLink() {
        if (level().isClientSide()) {
            return;
        }
        if (rvpData == null || !rvpData.hasHumanInTheLoop() || !hitlEnabled) {
            return;
        }
        if (hitlSignalSource != RVP_HumanInTheLoopData.SignalSource.RADIO) {
            return;
        }
        if (hitlLinkSevered) {
            return;
        }
        if (shooterVehicle == null) {
            return;
        }
        Vec3 start = shooterVehicle.getBoundingBox().getCenter();
        Vec3 end = getBoundingBox().getCenter();
        BlockHitResult hit = level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this));
        boolean blockedNow = hit.getType() == HitResult.Type.BLOCK;

        if (blockedNow) {
            hitlLinkBlockedTicks++;
        } else {
            hitlLinkBlockedTicks = 0;
        }

        hitlLinkBlocked = blockedNow;
        if (hitlLinkBlocked && hitlLinkBlockedTicks >= 40) {
            hitlLinkSevered = true;
            hitlEnabled = false;
            clearTarget();
        }
        maybeSyncHitlLinkState();
    }

    private void maybeSyncHitlLinkState() {
        if (hitlLinkBlocked == hitlLinkLastSentBlocked && hitlLinkSevered == hitlLinkLastSentSevered) {
            return;
        }
        hitlLinkLastSentBlocked = hitlLinkBlocked;
        hitlLinkLastSentSevered = hitlLinkSevered;
        if (getOwner() instanceof net.minecraft.server.level.ServerPlayer player) {
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    S2CHitlLinkState.of(getId(), hitlLinkBlocked, hitlLinkSevered));
        }
    }

    private void tickHitlMouseInertia() {
        if (!rvp$isHitlMouseSteering() || !rvp$isHitlActive()) {
            return;
        }
        if (!rvp$isPastRigidityTime()) {
            hitlSteeringYaw = getYRot();
            hitlSteeringPitch = getXRot();
            return;
        }
        float max = Math.max(hitlMaxTurnDegPerTick, 0.05f);
        hitlSteeringYaw = RVP_HitlSteeringMath.stepYawToward(hitlSteeringYaw, hitlInputYaw, max);
        hitlSteeringPitch = RVP_HitlSteeringMath.stepPitchToward(hitlSteeringPitch, hitlInputPitch, max);
    }

    public RVP_EnumHitlControlMode rvp$getHitlControlMode() {
        return hitlControlMode;
    }

    public int rvp$getHitlVideoModeMask() {
        return hitlVideoModeMask;
    }

    public int rvp$getDefaultHitlVideoMode() {
        return hitlDefaultVideoMode;
    }

    public float rvp$getHitlInputYaw() {
        return hitlInputYaw;
    }

    public float rvp$getHitlInputPitch() {
        return hitlInputPitch;
    }

    public float rvp$getHitlSteeringYaw() {
        return hitlSteeringYaw;
    }

    public float rvp$getHitlSteeringPitch() {
        return hitlSteeringPitch;
    }

    public float rvp$getHitlMaxTurnDegPerTick() {
        return hitlMaxTurnDegPerTick;
    }

    public float rvp$getHitlMaxLookOffsetDeg() {
        return hitlMaxLookOffsetDeg;
    }

    public void rvp$setHitlSteeringInput(float yaw, float pitch, int seq) {
        if (hitlSignalSource == RVP_HumanInTheLoopData.SignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
            return;
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.MOUSE || seq <= hitlInputSeq) {
            return;
        }
        this.hitlInputYaw = yaw;
        this.hitlInputPitch = pitch;
        this.hitlInputSeq = seq;
    }

    public void rvp$setHitlDesignatedTarget(Vec3 target) {
        if (hitlSignalSource == RVP_HumanInTheLoopData.SignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
            return;
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.DESIGNATE || target == null) {
            return;
        }
        setTargetPos(target);
        setTargetEntity(null);
    }

    public void rvp$clearHitlDesignation() {
        if (hitlSignalSource == RVP_HumanInTheLoopData.SignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
            return;
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.DESIGNATE) {
            return;
        }
        clearTarget();
    }

    public void rvp$setHitlDesignatedEntity(net.minecraft.world.entity.Entity target) {
        if (hitlSignalSource == RVP_HumanInTheLoopData.SignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
            return;
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.DESIGNATE || target == null || !target.isAlive()) {
            return;
        }
        setTargetEntity(target);
        setTargetPos(target.getBoundingBox().getCenter());
    }

    public void rvp$exitHitl() {
        this.hitlEnabled = false;
    }

    public boolean rvp$isHitlActive() {
        return hitlEnabled && hitlLife > 0;
    }

    public boolean rvp$isHitlMouseSteering() {
        return hitlEnabled && hitlControlMode == RVP_EnumHitlControlMode.MOUSE;
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        super.writeSpawnData(buffer);
        buffer.writeFloat(hitlControlRange);
        buffer.writeVarInt(hitlTimeoutTick);
        buffer.writeVarInt(hitlLife);
        buffer.writeVarInt(hitlVideoModeMask);
        buffer.writeVarInt(hitlDefaultVideoMode);
        buffer.writeBoolean(hitlEnabled);
        buffer.writeEnum(hitlControlMode);
        buffer.writeFloat(hitlMaxTurnDegPerTick);
        buffer.writeFloat(hitlMaxLookOffsetDeg);
        buffer.writeEnum(hitlSignalSource);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        super.readSpawnData(buffer);
        this.hitlControlRange = buffer.readFloat();
        this.hitlTimeoutTick = buffer.readVarInt();
        this.hitlLife = buffer.readVarInt();
        this.hitlVideoModeMask = buffer.readVarInt();
        this.hitlDefaultVideoMode = buffer.readVarInt();
        this.hitlEnabled = buffer.readBoolean();
        this.hitlControlMode = buffer.readEnum(RVP_EnumHitlControlMode.class);
        if (buffer.readableBytes() >= Float.BYTES) {
        this.hitlMaxTurnDegPerTick = buffer.readFloat();
        this.hitlMaxLookOffsetDeg = buffer.readFloat();
    }
        if (buffer.readableBytes() >= 1) {
            this.hitlSignalSource = buffer.readEnum(RVP_HumanInTheLoopData.SignalSource.class);
        }
        this.hitlSteeringYaw = getYRot();
        this.hitlSteeringPitch = getXRot();
        this.hitlInputYaw = hitlSteeringYaw;
        this.hitlInputPitch = hitlSteeringPitch;
    }
}
