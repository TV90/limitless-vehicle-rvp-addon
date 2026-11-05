package org.ywzj.rvp.entity.projectile;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
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
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.RVP_GuidanceActiveConfig;
import org.ywzj.rvp.guidance.RVP_GuidanceModelResolver;
import org.ywzj.rvp.guidance.RVP_HitlSteeringMath;
import org.ywzj.rvp.guidance.RVP_TvVideoModeMask;
import org.ywzj.rvp.guidance.runtime.RVP_ActiveSeekerRelayPolicy;
import org.ywzj.rvp.countermeasure.RVP_CountermeasureState;
import org.ywzj.rvp.radar.RVP_AspectRcs;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.radar.RVP_RadarScanHelper;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CEnterHitlView;
import org.ywzj.rvp.network.S2CHitlLinkState;
import org.ywzj.rvp.network.S2CMissileTrackAlert;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataHITL;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileManager;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.network.Channel;
import org.ywzj.vehicle.network.message.ServerVehicleWarn;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.WarnType;
import java.util.function.Function;

/**
 * Generic guided missile entity for {@code rvp:missile}.
 *
 * <p>MCLOS wire / TV+MCLOS 走分段制导（{@link org.ywzj.rvp.guidance.RVP_GuidanceController} +
 * {@code take_over_motion}）。HITL MOUSE 鼠标指令为<b>虚拟摇杆</b>模型（2026-09-10）：
 * 客户端累计的鼠标增量作为<b>相对弹体朝向的转向偏移</b>写入 {@code hitlInputYaw/Pitch}，
 * 舵量目标 = 弹体当前朝向 + 偏移——偏移保持则持续转向（摇杆拉住），偏移回中则直飞。</p>
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
    /** 客户端累计的转向偏移量（度，相对弹体朝向，虚拟摇杆）。 */
    private float hitlInputYaw;
    private float hitlInputPitch;
    /** 服务端舵量航向（弹体朝向 + 偏移 经最大转速 slew 后的当前值）。 */
    private float hitlSteeringYaw;
    private float hitlSteeringPitch;
    private int hitlInputSeq = Integer.MIN_VALUE;
    private HitlSignalSource hitlSignalSource = HitlSignalSource.RADIO;
    private boolean hitlLinkBlocked;
    private boolean hitlLinkSevered;
    private int hitlLinkBlockedTicks;
    private boolean hitlLinkLastSentBlocked;
    private boolean hitlLinkLastSentSevered;
    private boolean hitlLinkLastSentDircm;
    private int hitlLinkLastSentDircmRemain;
    /** 已下发的锁点，用于立即同步脱锁和节流移动坐标。 */
    private org.ywzj.rvp.hitl.RVP_HitlLockSnapshot hitlLastSentLock;
    /** 上次锁点同步的弹体 tick，普通位置更新至多每 5 tick 一次。 */
    private int hitlLastSentLockTick;
    /** 无实体时的固定点来源；不复用 IR 的永久烟雾禁锁状态。 */
    private org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status hitlPointStatus = org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.POINT;
    private int hitlEnterViewResendTicks;
    /** 武器配置 {@code hitl_right_click_detonate}：HITL 视角下右键 = 提前引爆（客户端通过 spawn 数据读取）。 */
    private boolean hitlRightClickDetonate;
    /** 虚拟态收到已授权的右键引爆请求后，恢复实体再执行原爆炸链。 */
    private boolean virtualHitlDetonationPending;
    /** 显式远程观察配置，仅服务端持有完整参数；客户端只接收 enabled 标志。 */
    private org.ywzj.rvp.weapon.data.RVP_HitlRemoteViewData hitlRemoteView;
    /** 本发弹体是否启用了远程观察，随 spawn 数据同步。 */
    private boolean hitlRemoteConfigured;
    /** 每发导弹只申请一次会话，防止主动退出后被后续 tick 自动重开。 */
    private boolean hitlRemoteAttempted;
    /** 有限频率的非加载无线电遮挡检测结果和下一次检测时刻。 */
    private boolean hitlRemoteRadioBlocked;
    private int hitlRemoteNextRadioCheck;
    private int activeSeekerDesignatedTargetId = Integer.MIN_VALUE;
    /**
     * 中继失援标志（2026-10-06 实时语义，替代旧版永久锁存 {@code activeSeekerSupportReleased}）：
     * 每 tick 由"有 designated 且支持链不可用"重算，<b>无记忆</b>——雷达重新照射目标后下一 tick
     * 即自动恢复中继重绑，不存在"脱锁/关雷达后永久失效"。随服务端虚拟运行态保存，不发到显示消息。
     */
    private boolean activeSeekerRelayLost;
    /** 中继连续失援计数（tick）：失援期间弹冻结最后已知点滑行，达到滑行自毁上限后自爆。 */
    private int activeSeekerRelayLostTicks;
    /** 中继滑行自毁上限（tick）：来自武器 {@code guidance_data.relay_lost_self_destruct_ticks}，≤0 关闭。 */
    private int relayLostSelfDestructTicks;

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
    protected boolean tryEnterVirtualMidcourse() {
        // 调用阶段 B 服务端管理器；管理器完成资格、快照、SavedData 注册后才移除本实体。
        return RVP_VirtualMissileManager.tryVirtualize(this);
    }

    @Override
    public void initFromWeapon(RVP_WeaponData data, RVP_EnumWeaponKind kind, AbstractVehicle vehicle, LivingEntity shooter,
                               Vec3 spawnPos, AimRot aim, Vec3 initialMotion) {
        super.initFromWeapon(data, kind, vehicle, shooter, spawnPos, aim, initialMotion);
        if (data == null
                || !(data.getGuidanceData() instanceof RVP_GuidanceDataHITL hitl)) {
            return;
        }
        initNewSchemaHitl(data, hitl, aim);
    }

    private void initNewSchemaHitl(RVP_WeaponData data, RVP_GuidanceDataHITL hitl, AimRot aim) {
        this.hitlControlRange = Math.max(hitl.getHitlMaxControlDist(), 1);
        this.hitlTimeoutTick = Math.max(hitl.getHitlMaxControlTick(), 1);
        this.hitlLife = this.hitlTimeoutTick;
        this.hitlVideoModeMask = resolveVideoModeMask(hitl);
        this.hitlDefaultVideoMode = resolveDefaultVideoMode(hitl);
        this.hitlControlMode = switch (data.getGuidanceData().getGuidanceType()) {
            case HITL_TV -> RVP_EnumHitlControlMode.DESIGNATE;
            case HITL_CLOS_TV -> RVP_EnumHitlControlMode.MOUSE;
            default -> RVP_EnumHitlControlMode.VIEW;
        };
        this.hitlMaxTurnDegPerTick = Math.max(hitl.getHitlMaxTurnDegPerTick(), 0.05f);
        this.hitlMaxLookOffsetDeg = Math.max(hitl.getHitlMaxLookOffset(), 1);
        this.hitlSignalSource = "FIBER".equals(hitl.getSignalSource())
                ? HitlSignalSource.FIBER
                : HitlSignalSource.RADIO;
        this.hitlRightClickDetonate = hitl.isHitlRightClickDetonate();
        this.hitlRemoteConfigured = hitl.isRemoteViewEnabled();
        this.hitlRemoteView = hitlRemoteConfigured ? hitl.getHitlRemoteView() : null;
        this.hitlEnabled = true;
        this.hitlEnterViewResendTicks = 5;
        this.hitlInputYaw = 0;
        this.hitlInputPitch = 0;
        this.hitlSteeringYaw = aim.yRot();
        this.hitlSteeringPitch = aim.xRot();
    }

    private static int resolveVideoModeMask(RVP_GuidanceDataHITL hitl) {
        int mask = 0;
        for (String mode : hitl.getHitlVideoModes()) {
            mask |= RVP_TvVideoModeMask.parse(mode);
        }
        return mask != 0 ? mask : HITL_MODE_ALL;
    }

    private static int resolveDefaultVideoMode(RVP_GuidanceDataHITL hitl) {
        for (String mode : hitl.getHitlVideoModes()) {
            int parsed = RVP_TvVideoModeMask.parse(mode);
            if (parsed != 0) {
                return parsed;
            }
        }
        return HITL_MODE_COLOR;
    }

    @Override
    protected void tickGuidance() {
        if (!level().isClientSide() && hitlRemoteConfigured && !hitlRemoteAttempted && hitlEnabled) {
            hitlRemoteAttempted = true;
            // 只有显式配置的弹体申请额外观察，服务端负责人数/距离/母车身份门控。
            if (!org.ywzj.rvp.server.hitl.RVP_HitlRemoteViewService.start(this, hitlRemoteView, hitlControlRange)) {
                hitlEnabled = false;
            }
        }
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

        // 调用统一阶段更新，先通过冷发射/姿态保护，再按本刻阶段管理主动导引头。
        org.ywzj.rvp.guidance.RVP_GuidanceRuntimeController.updatePhase(this);

        RVP_EnumGuidanceType activeType = resolveNewActiveSeekerType();
        if (activeType == RVP_EnumGuidanceType.ARH || activeType == RVP_EnumGuidanceType.AIR) {
            tickActiveSeekerTargetManagement(activeType);
            // 主动导引头开机/锁定时向目标播报本体 RWR 告警（对标本体 MissileEntity.tickTrack）
            tickRwrMissileLaunchWarn(activeType);
        }
        // ARH / AIR / IR（含红外家族导引头）锁定目标时向目标播报 RVP 专用追踪告警
        tickMissileTrackAlert(activeType);

        super.tickGuidance();
        // AIR 本刻完成红外捕获或雷达更新后再计时，避免边界 tick 先自毁再捕获。
        if (activeType == RVP_EnumGuidanceType.AIR) tickAirTargetLoss();

        // 服务端：DIRCM 对 HITL 弹的临时干扰状态变化 → 同步客户端（白闪滤镜驱动）
        if (!level().isClientSide()) {
            maybeSyncHitlLinkState();
        }

        // [DIRCM DEBUG] 干扰期间若 targetEntity 仍被重建，打印来源（排查"干扰未取消截获"）
        if (!level().isClientSide() && dircmJammed && (tickCount & 15) == 0) {
            org.apache.logging.log4j.LogManager.getLogger("RVP_Dircm")
                    .info("[DIRCM DEBUG] jammed hitlMissile={} remain={} targetEntity={} targetPos={}",
                            getId(), dircmJamRemainTick,
                            targetEntity == null ? "null" : targetEntity.getClass().getSimpleName(),
                            targetPos == null ? "null" : String.format("%.1f,%.1f,%.1f",
                                    targetPos.x, targetPos.y, targetPos.z));
        }
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
        // 重发入镜后也补发快照，避免入镜前到达的结果被客户端忽略。
        rvp$syncHitlState();
        hitlEnterViewResendTicks--;
    }

    private RVP_EnumGuidanceType resolveNewActiveSeekerType() {
        if (rvpData == null) {
            return RVP_EnumGuidanceType.NONE;
        }
        return resolveNewActiveConfig().guidanceType();
    }

    /** 当前生效的主动导引头/制导类型（供 gunner 等外部判断威胁类型，如 IR/AIR 红外族）。 */
    public RVP_EnumGuidanceType getActiveGuidanceType() {
        return resolveNewActiveSeekerType();
    }

    /** 干扰判定段标志：已进入原定开机距离（箔条判定 + MSL 告警生效），但导引头尚未真开机。 */
    private boolean countermeasurePhaseActive;
    /** AIR 已到配置开机距离的锁存；默认false，提前搜索不提前累计无目标自毁，真实/虚拟态均保存。 */
    private boolean airLossTimerArmed;

    // ==================== HITL 电视制导速度脱锁反制（2026-09-21） ====================

    /**
     * 速度脱锁：清除实体锁，跟踪点设为最后一次持续跟踪的坐标。之后吊舱实时指定点
     * （含指方块/地面）立即恢复可用，重锁超速实体会被制导源闸门再次脱锁。
     */
    public void rvp$markHitlSpeedBreak(Vec3 lastTrackedPos) {
        // 统一清除实体、骨和旧惯性记忆，再记录唯一的冻结点。
        rvp$freezeHitlTarget(lastTrackedPos, org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.SPEED);
    }

    private void tickActiveSeekerTargetManagement(RVP_EnumGuidanceType type) {
        RVP_GuidanceActiveConfig config = resolveNewActiveConfig();
        if (config == null || config.guidanceType() != type) {
            return;
        }
        if (type == RVP_EnumGuidanceType.AIR) {
            tickAirSeekerTargetManagement(config);
            return;
        }
        activeRadarActivationRange = config.activeRadarActivationRange();
        relayLostSelfDestructTicks = config.relayLostSelfDestructTick();

        Entity designated = rvp$getActiveSeekerDesignatedTargetEntity();
        boolean hasDesignation = rvp$hasActiveSeekerDesignation();
        boolean supportAvailable = designated != null && rvp$hasActiveSeekerSupportForDesignatedTarget();
        // 中继支持每 tick 实时评估（2026-10-06，替代旧版"断一拍即永久锁存"）：
        // 支持可用 → 重绑 designated 持续修正；失援 → 冻结最后已知点滑行（不跟目标运动）；
        // 恢复跟踪（重锁/碟扫回/雷达重开重新探测到）→ 下一 tick 立即恢复中继，无锁存阻挡
        RVP_ActiveSeekerRelayPolicy.RelayDecision relayDecision = RVP_ActiveSeekerRelayPolicy.decide(
                activeRadarCatch, hasDesignation, supportAvailable);
        // 调用生命周期诊断：只在中继失援/恢复的状态边沿记录，避免逐 Tick 重复刷屏。
        if (activeSeekerRelayLost != relayDecision.relayLost()) {
            org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug.noteEvent(this,
                    org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug.Event.GUIDANCE,
                    () -> "reason=" + (relayDecision.relayLost() ? "RADAR_RELAY_LOST" : "RADAR_RELAY_RESTORED")
                            + " action=" + (relayDecision.freeze() ? "coast" : "track")
                            + " selfDestructLimit=" + relayLostSelfDestructTicks);
        }
        activeSeekerRelayLost = relayDecision.relayLost();

        if (!activeRadarCatch) {
            if (relayDecision.rebind()) {
                setTargetEntity(designated);
                setTargetPos(designated.getBoundingBox().getCenter());
                activeSeekerRelayLostTicks = 0;
            } else if (relayDecision.freeze()) {
                // 失援：清目标，弹冻结最后已知点滑行（不跟目标运动）；连续失援达上限自爆
                setTargetEntity(null);
                activeSeekerRelayLostTicks++;
                if (RVP_ActiveSeekerRelayPolicy.shouldSelfDestruct(
                        activeSeekerRelayLostTicks, relayLostSelfDestructTicks)) {
                    // 在统一寿命终止之前说明真正原因；后续 LIFE_END/REMOVED 仍由基类负责。
                    org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug.noteEvent(this,
                            org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug.Event.GUIDANCE,
                            () -> "reason=RADAR_RELAY_TIMEOUT lostTicks=" + activeSeekerRelayLostTicks
                                    + " selfDestructLimit=" + relayLostSelfDestructTicks);
                    life = 0;
                }
            } else {
                // 无 designated（LOAL 发射）：本就不承诺中继，不计失援
                activeSeekerRelayLostTicks = 0;
            }
        } else {
            activeSeekerRelayLostTicks = 0;
        }

        // 目的：分角度 RCS 双阈值相位（2026-09-16）——
        // 干扰判定段：dist ≤ 原定开机距离即开始箔条判定与 MSL 告警，但制导仍为 TWS 中继
        // （载具雷达断中继照样丢目标转惯性）；主动段：dist ≤ 原定 × combined（目标 RCS 隐身
        // 因子 = 分角度 × 开启弹舱增幅，隐身缩减后的真开机距离）才 setAutonomousSeekerOn。
        // 非隐身目标（combined≈1）两阈值重合，行为与现状一致。
        if (!activeRadarOn) {
            Vec3 activationReference = targetPos != null ? targetPos : lastGuidancePos;
            if (activationReference == null || activeRadarActivationRange <= 0) {
                // 发射后不管 / 无开机距离限制：保持发射即自主搜索，隐身由每候选获取距离因子体现
                countermeasurePhaseActive = false;
                setAutonomousSeekerOn(true);
                notifyActiveSeekerOnline(type);
            } else {
                double distToRef = activationReference.distanceTo(position());
                double effectiveActivation = activeRadarActivationRange;
                Entity rcsReference = targetEntity != null ? targetEntity : designated;
                if (rcsReference instanceof AbstractVehicle rcsVehicle) {
                    effectiveActivation = activeRadarActivationRange
                            * RVP_AspectRcs.detectionFactor(rcsVehicle, position());
                }
                // 干扰判定段：进入原定开机距离、尚未到有效开机距离
                countermeasurePhaseActive = distToRef <= activeRadarActivationRange;
                if (countermeasurePhaseActive && designated != null) {
                    // 箔条判定（复用主动导引头同款查询，记忆表连续推进）：decoyed → 断中继
                    // （清 targetEntity 转惯性直飞记忆点；designated 保留，开机后由反制逻辑转锁箔条或滑行）
                    RVP_CountermeasureState.Result result =
                            RVP_CountermeasureState.query(this, designated, type, config);
                    if (result.decoyed()) {
                        setTargetEntity(null);
                    }
                }
                if (distToRef <= effectiveActivation) {
                    setAutonomousSeekerOn(true);
                    notifyActiveSeekerOnline(type);
                }
            }
        }

        if (activeRadarOn && targetEntity == null) {
            // 被 DIRCM 干扰期间不计入"丢目标自毁"：断锁是干扰的预期效果，
            // 不应触发 life=0 的弹体自毁（否则被干扰弹转圈后凭空消失）。
            if (dircmJammed) {
                activeRadarLostTargetTick = 0;
            } else {
                activeRadarLostTargetTick++;
                // 主动ECM对ARH设为 200 tick 自毁（批示：60→200），且禁止重获后仍按 200 计
                int threshold = ecmActiveNoReacquire ? 200 : 60;
                if (activeRadarLostTargetTick >= threshold) {
                    life = 0;
                }
            }
        }
    }

    /** AIR 中继、提前搜索和计时距离分别管理；ARH 仍执行原有双阈值逻辑。 */
    private void tickAirSeekerTargetManagement(RVP_GuidanceActiveConfig config) {
        activeRadarActivationRange = config.activeRadarActivationRange();
        relayLostSelfDestructTicks = config.relayLostSelfDestructTick();
        Entity designated = rvp$getActiveSeekerDesignatedTargetEntity();
        // 调用当前捕获状态；历史上捕获过不代表现在仍有有效红外跟踪。
        if (activeRadarCatch && (targetEntity == null || !targetEntity.isAlive()
                || !org.ywzj.rvp.guidance.runtime.RVP_AirSeekerPolicy.isAirInfraredCandidate(targetEntity))) {
            clearAutonomousSeekerCatch();
        }
        boolean support = org.ywzj.rvp.guidance.runtime.RVP_AirSeekerPolicy.allowsTarget(designated)
                && !dircmJammed && !isSeekerShutOff()
                && !isJamGracePeriodActive() && !hasSmokeBreakLock()
                && rvp$hasActiveSeekerSupportForDesignatedTarget();
        activeSeekerRelayLost = rvp$hasActiveSeekerDesignation() && !support;
        if (!activeRadarCatch) {
            if (support) {
                // 雷达有效时才能读取实时点；指定箔条也只由这条数据链更新，不能标为红外捕获。
                setTargetEntity(designated);
                setTargetPos(org.ywzj.rvp.util.RVP_AimPointResolver.resolveLargestObbCenter(designated));
            } else {
                // 只清实体引用，不读取未观测目标的新位置，保留最后有效坐标与原指定身份。
                setTargetEntity(null);
            }
        }
        Vec3 reference = targetPos != null ? targetPos : lastGuidancePos;
        double distance = reference == null ? -1.0 : reference.distanceTo(position());
        // 调用独立策略：无雷达立即搜索，计时始终等到配置距离且不乘 RCS。
        var decision = org.ywzj.rvp.guidance.runtime.RVP_AirSeekerPolicy.decide(
                activeRadarOn, airLossTimerArmed, support, distance, config.activeRadarActivationRange());
        airLossTimerArmed = decision.lossTimerArmed();
        if (decision.searchOn() && !activeRadarOn) {
            setAutonomousSeekerOn(true);
            notifyActiveSeekerOnline(RVP_EnumGuidanceType.AIR);
        }
    }

    /** AIR 无目标与中继滑行计时只在配置开机距离到达后累计，捕获或中继恢复即清零。 */
    private void tickAirTargetLoss() {
        boolean hasTarget = targetEntity != null && targetEntity.isAlive();
        activeRadarLostTargetTick = org.ywzj.rvp.guidance.runtime.RVP_AirSeekerPolicy.nextLostTicks(
                activeRadarLostTargetTick, airLossTimerArmed, hasTarget, dircmJammed);
        activeSeekerRelayLostTicks = org.ywzj.rvp.guidance.runtime.RVP_AirSeekerPolicy.nextLostTicks(
                activeSeekerRelayLostTicks, airLossTimerArmed, hasTarget || !activeSeekerRelayLost, dircmJammed);
        if (activeRadarLostTargetTick >= 60 || RVP_ActiveSeekerRelayPolicy.shouldSelfDestruct(
                activeSeekerRelayLostTicks, relayLostSelfDestructTicks)) {
            org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug.noteEvent(this,
                    org.ywzj.rvp.debug.RVP_ProjectileLifecycleDebug.Event.GUIDANCE,
                    () -> "reason=AIR_TARGET_TIMEOUT lostTicks=" + activeRadarLostTargetTick
                            + " relayLostTicks=" + activeSeekerRelayLostTicks);
            life = 0;
        }
    }

    /** 本刻雷达中继是否可用；仅供 AIR 执行器使用，由管理阶段每刻重新核验。 */
    public boolean rvp$hasAirRadarRelaySupport() {
        return rvp$hasActiveSeekerDesignation() && !activeSeekerRelayLost;
    }

    /**
     * 主动雷达(ARH)导弹弹载导引头开机并锁定目标时，向目标播报本体 RWR 的
     * MISSILE_LAUNCH 告警（"MSL"）。对标本体 {@code MissileEntity.tickTrack()} 每 2 tick
     * 发送一次，保证目标 WarningReceiver 500ms 告警窗口不中断，RWR 显示"导弹来袭"。
     * AIR（主动红外）不播报此告警——RWR 只响应雷达威胁，AIR 的来袭提示由 IR 告警承担。
     *
     * @param activeType 当前已解析的主动导引头类型（仅 ARH 生效）
     */
    private void tickRwrMissileLaunchWarn(RVP_EnumGuidanceType activeType) {
        // 只从服务端发告警；告警目标过滤（必须是本地驾驶的载具、俯仰角 ≤45°）由本体
        // WarningReceiver.handle 客户端处理。
        if (level().isClientSide()) {
            return;
        }
        // 防御性校验：仅主动雷达(ARH)导引头才向目标播报 RWR MISSILE_LAUNCH 告警。
        // AIR 是红外型导引头，RWR（雷达告警接收机）不响应红外威胁，其来袭由 RVP 的 IR 告警
        // 音效/血条提示承担——否则 AIR 会同时响起"导弹发射告警"与"IR 告警"双响。
        if (activeType != RVP_EnumGuidanceType.ARH) {
            return;
        }
        // 目的：告警闸门前移（2026-09-16 分角度 RCS）——进入干扰判定段（原定开机距离内）
        // 即开始 MSL 告警，不等到隐身缩减后的真开机距离；目标优先取当前锁定实体，
        // 中继被箔条断掉（targetEntity 置空）时回退指定目标实体。
        Entity warnTarget = targetEntity != null ? targetEntity
                : rvp$getActiveSeekerDesignatedTargetEntity();
        if (!(countermeasurePhaseActive || activeRadarOn) || warnTarget == null || !warnTarget.isAlive()) {
            return;
        }
        // 对标本体 MissileEntity.java:400：每 2 tick 广播一次 MISSILE_LAUNCH 给跟踪目标的玩家。
        if (tickCount % 2 == 0) {
            ServerVehicleWarn packet = new ServerVehicleWarn(
                    this.getId(), warnTarget.getId(), WarnType.MISSILE_LAUNCH, "MSL");
            // 调用本体网络通道，向所有跟踪目标实体的玩家广播告警包。
            Channel.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> warnTarget), packet);
            // RVP 无钳制补发：直接向目标载具乘客发 RVP 告警包，客户端写入 targets 后
            // 即使目标相对本机俯仰角超 ±45°（本体 WarningReceiver 会丢弃）也能告警。
            if (warnTarget instanceof AbstractVehicle target) {
                for (Entity passenger : target.getPassengers()) {
                    // 仅向服务端玩家乘客补发（RVP 包不能进本体 Channel，走自有频道）
                    if (passenger instanceof ServerPlayer player) {
                        RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                                new org.ywzj.rvp.network.S2CRvpWarn(
                                        this.getId(), target.getId(), WarnType.MISSILE_LAUNCH, "MSL"));
                    }
                }
            }
        }
    }

    /**
     * 弹载导引头锁定目标时，向目标周期发送 {@link S2CMissileTrackAlert}（驱动血条上方提示
     * 文案与 IR/AIR 告警音效）。覆盖 ARH（箔条规避提示）与 IR/AIR（热焰弹规避提示），
     * 约每 0.5s 一次，形成持续追踪提示。
     *
     * <p>注意走 RVP 自己的频道 {@link RVP_Network#CHANNEL}（RVP 包不能进本体
     * {@code Channel} 频道，否则 Forge 报 Invalid message）。</p>
     */
    private void tickMissileTrackAlert(RVP_EnumGuidanceType activeType) {
        // 本维度没有玩家就没有实体追踪接收者，跳过无用的告警包构造和发送。
        if (level().isClientSide() || level().players().isEmpty()) {
            return;
        }
        byte typeCode = switch (activeType) {
            case ARH -> S2CMissileTrackAlert.TYPE_ARH;
            case AIR -> S2CMissileTrackAlert.TYPE_AIR;
            case IR -> S2CMissileTrackAlert.TYPE_IR;
            case HITL_TV -> S2CMissileTrackAlert.TYPE_HITL_TV;
            default -> 0;
        };
        if (typeCode == 0 || !isSeekerTracking(activeType)) {
            return;
        }
        // 发送频率必须小于 IR 红外捕获宽限期（max(6, scan_interval×2) ≥ 6 tick），
        // 否则 IR 告警会在宽限期内（<10 tick）连一次发送都赶不上而永远不触发。
        if (tickCount % 4 != 0) {
            return;
        }
        Entity target = getTargetEntity();
        if (target == null || !target.isAlive()) {
            return;
        }
        RVP_Network.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> target),
                new S2CMissileTrackAlert(getId(), target.getId(), typeCode));
    }

    /** 导引头是否正在追踪存活目标（ARH/AIR 用自主导引头截获态，IR 用红外捕获宽限期）。 */
    private boolean isSeekerTracking(RVP_EnumGuidanceType activeType) {
        if (activeType == RVP_EnumGuidanceType.ARH || activeType == RVP_EnumGuidanceType.AIR) {
            return isAutonomousSeekerOn() && hasAutonomousSeekerCatch()
                    && getTargetEntity() != null && getTargetEntity().isAlive();
        }
        if (activeType == RVP_EnumGuidanceType.IR) {
            // 持续跟踪判定：目标存活且未因烟雾/光学遮挡脱锁即视为正在被跟踪（脱锁前持续告警）。
            // 不能用 hasIrSeekerGrace：跟踪确认时 resetIrSeekerGrace 会把宽限禁用（MIN_VALUE），
            // 宽限只在"刚锁定/脱锁缓冲"几 tick 为 true，用它判断会在持续跟踪期间漏报。
            return getTargetEntity() != null && getTargetEntity().isAlive() && !hasSmokeBreakLock();
        }
        if (activeType == RVP_EnumGuidanceType.HITL_TV) {
            // 人在回路电视制导：被指定（designate）锁定目标即视为正在追踪
            return getTargetEntity() != null && getTargetEntity().isAlive();
        }
        return false;
    }

    private void notifyActiveSeekerOnline(RVP_EnumGuidanceType type) {
        // 捕获接管模式禁止开机提示；只由真实捕获边沿发送捕获提示。
        if (usesTerminalSeekerHandoff()) return;
        if (!(getOwner() instanceof ServerPlayer player)) {
            return;
        }
        String messageKey = switch (type) {
            case AIR -> "message.ywzj_rvp.seeker_online_ir";
            case ARH -> "message.ywzj_rvp.seeker_online_radar";
            default -> null;
        };
        if (messageKey != null) {
            // [RVP] translatable：服务端发 key，客户端按自身语言本地化（专用服安全）
            player.displayClientMessage(Component.translatable(messageKey), true);
        }
    }

    /**
     * 所有 RVP 导弹统一使用燃料尾焰模式（对标本体 MissileEntity.tickParticle）。
     * 有推进配置的按 {@code motor_burn_time} 计算；无配置的默认燃烧期为 {@code life / 2} tick。
     */
    @Override
    protected boolean isMotorBurning() {
        // 客户端 rvpData 可能为 null，需要同时支持双脉冲第二段的同步燃烧期判断。
        if (rvpData == null) {
            // 上下界夹取燃烧窗口（2026-10-03 与基类同款修复）：motorIgnitionStartTick 为
            // 生成包同步的点火起点，只有上界会让延迟点火的导弹出管即喷焰；
            // 飞行进度用 clientVisualFlightTickCount（2026-10-06：含发包时已飞 tick，
            // 防飞行中途进入追踪范围的导弹把燃烧窗口从 0 重放——动力段结束仍喷焰）
            if (clientVisualFlightTickCount() >= motorIgnitionStartTick
                    && clientVisualFlightTickCount() <= motorBurnEndTick) {
                return true;
            }
            int start = this.entityData.get(DATA_SECOND_PULSE_START_TICK);
            int burn = this.entityData.get(DATA_SECOND_PULSE_BURN_TIME_TICK);
            if (start >= 0 && burn > 0) {
                int t2 = clientVisualFlightTickCount() - start;
                return t2 >= 0 && t2 <= burn;
            }
            return false;
        }
        if (!isMotorPropulsion()) {
            // 无发动机配置的导弹：默认燃烧期取一半寿命
            int defaultBurn = Math.max(life / 2, 20);
            return getFlightTickCount() <= defaultBurn;
        }
        return super.isMotorBurning();
    }

    /** Captures the launch-designated target shared by the new ARH/AIR runtime. */
    public void rvp$setActiveSeekerDesignatedTarget(@Nullable Entity target) {
        // 调用归属门，指定目标、真实/虚拟交接及旧对象都不能保留己方弹药指定。
        if (target != null && !org.ywzj.rvp.guidance.RVP_SeekerTargetFilter.allows(this, target)) target = null;
        if (target == null) {
            this.activeSeekerDesignatedTargetId = Integer.MIN_VALUE;
            // DIRCM 等对抗清除：显式清目标（实时中继语义下不再有"每 tick 强制清空"兜底，
            // 必须在此一次性脱离真目标，转惯性/记忆点）
            clearTarget();
            return;
        }
        this.activeSeekerDesignatedTargetId = target.getId();
        if (this.targetEntity == null) {
            this.targetEntity = target;
        }
        this.lastGuidancePos = target.position().add(0, target.getBbHeight() * 0.5, 0);
    }

    public void rvp$setArhDesignatedTarget(@Nullable Entity target) {
        rvp$setActiveSeekerDesignatedTarget(target);
    }

    @Nullable
    public Entity rvp$getActiveSeekerDesignatedTargetEntity() {
        if (activeSeekerDesignatedTargetId == Integer.MIN_VALUE) {
            return null;
        }
        // 调用统一查询，转虚拟后保持主动导引头指定目标的同一会话 ID。
        Entity entity = org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileTargets.resolve(level(), activeSeekerDesignatedTargetId);
        if (entity == null || !entity.isAlive()) {
            return null;
        }
        return entity;
    }

    @Nullable
    public Entity rvp$getArhDesignatedTargetEntity() {
        return rvp$getActiveSeekerDesignatedTargetEntity();
    }

    public boolean rvp$hasActiveSeekerDesignation() {
        return activeSeekerDesignatedTargetId != Integer.MIN_VALUE;
    }

    public boolean rvp$canActiveSeekerFreeAcquire() {
        return activeRadarCatch
                || activeSeekerDesignatedTargetId == Integer.MIN_VALUE
                || activeSeekerRelayLost;
    }

    public boolean rvp$canArhFreeAcquire() {
        return rvp$canActiveSeekerFreeAcquire();
    }

    public boolean rvp$hasActiveSeekerSupportForDesignatedTarget() {
        // 主动ECM干扰期间：阻断雷达中继支持（ARH/AIR 中继期均受影响）
        if (ecmActiveJamRemainTick > 0) {
            return false;
        }
        Entity designatedTarget = rvp$getActiveSeekerDesignatedTargetEntity();
        if (designatedTarget == null) {
            return false;
        }
        WeaponUnit weaponUnit = getShooterWeaponUnit();
        if (weaponUnit == null) {
            return false;
        }
        WeaponUnit root = weaponUnit.getRootParentWeaponUnit();
        if (root == null) {
            return false;
        }

        boolean infrared = resolveNewActiveSeekerType() == RVP_EnumGuidanceType.AIR;
        if (infrared) {
            // 调用与发射前同一雷达支持入口，箔条丢硬锁和载具隐身失援均即时生效。
            return org.ywzj.rvp.guidance.runtime.RVP_AirSeekerRelaySupport.isAvailable(root, designatedTarget);
        }
        boolean anyRadarOn = false;
        // 2026-10-06 扩展：支持源从"发射站子雷达"扩为整车全部雷达——任意武器站的雷达
        // 以 TWS 探测、硬锁或照射包络跟踪 designated 即构成照射（用户定版"照射即中继"）。
        // 照射包络 = 距离×信号缩放 + 高度门 + 安装方位限位，不含"当前碟位扇区"瞬态项
        // （探测表口径受扇区修剪，脱锁后碟回扫掠一拍即断，是上一版修复无效的根因）
        if (shooterVehicle != null) {
            for (PartUnit<?> partUnit : shooterVehicle.getPartUnits()) {
                if (!(partUnit instanceof RadarUnit radarUnit) || !radarUnit.isOn()) {
                    continue;
                }
                anyRadarOn = true;
                if (radarSupportsDesignation(radarUnit, designatedTarget, infrared, false)) {
                    return true;
                }
            }
        } else {
            // 母车引用不可得（边界）时退回发射站子雷达
            for (RadarUnit radarUnit : root.getRadarUnits()) {
                if (!radarUnit.isOn()) {
                    continue;
                }
                anyRadarOn = true;
                if (radarSupportsDesignation(radarUnit, designatedTarget, infrared, false)) {
                    return true;
                }
            }
        }

        if (root != null && shooterVehicle != null) {
            AbstractVehicle relayVehicle = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(shooterVehicle).orElse(null);
            RadarUnit relayRadar = RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar(relayVehicle);
            if (relayRadar != null && relayRadar.isOn()) {
                anyRadarOn = true;
                if (radarSupportsDesignation(relayRadar, designatedTarget, infrared,
                        RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root) == designatedTarget.getId())) {
                    return true;
                }
            }
        }

        // AIR 不能用旧锁或历史截获旁路实时 RCS 包络；ARH 保留原有后备行为。
        return !infrared && anyRadarOn && targetEntity == designatedTarget && targetEntity.isAlive() && activeRadarCatch;
    }

    /** AIR 实时复核 RCS 包络；ARH 保留已观测/硬锁先返回的既有查询顺序。 */
    private static boolean radarSupportsDesignation(RadarUnit radar, Entity target, boolean infrared, boolean externalLock) {
        boolean tracked = RVP_RadarRoleHelper.radarCurrentlyDetects(radar, target)
                || radar.getLockedEntity() == target || externalLock;
        boolean envelope = (infrared || !tracked) && RVP_RadarScanHelper.isWithinIlluminationEnvelope(radar, target);
        return org.ywzj.rvp.guidance.runtime.RVP_AirSeekerPolicy.radarSupports(infrared, envelope, tracked);
    }

    public boolean rvp$hasArhSupportForDesignatedTarget() {
        return rvp$hasActiveSeekerSupportForDesignatedTarget();
    }

    @Nullable
    private RVP_GuidanceActiveConfig resolveNewActiveConfig() {
        if (rvpData == null) {
            return null;
        }
        return RVP_GuidanceModelResolver.resolveActive(
                rvpData.getGuidanceData(),
                // 未捕获时仍管理主段导引头，不能因末段搜索而中断原雷达中继或提前开启末段计时。
                isTerminalSeekerHandoffPending() ? org.ywzj.rvp.guidance.RVP_GuidancePhase.MAIN
                        : getGuidancePhaseState().phase()
        );
    }

    @Override
    protected void tickMotion() {
        if (hitlSignalSource == HitlSignalSource.RADIO
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
        // 远程模式由独立会话心跳和距离门控决定生命期；静止观看不会因没有鼠标输入超时。
        if (hitlRemoteConfigured) return;
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
        if (hitlSignalSource != HitlSignalSource.RADIO) {
            return;
        }
        // 主动ECM干扰：radio 链路强制阻断（仅 RADIO 源生效，与 §7.5 一致）
        if (ecmActiveJamRemainTick > 0) {
            hitlLinkBlocked = true;
            hitlLinkBlockedTicks++;
            if (hitlLinkBlockedTicks >= 40) {
                hitlLinkSevered = true;
                hitlEnabled = false;
                clearTarget();
            }
            maybeSyncHitlLinkState();
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
        // 视距门控：无线链路只在玩家可见视距内维持，超出视距的导弹已离开发射者可视范围，
        // 不执行 level().clip 否则超长射线会逐 tick 同步加载射线沿途未加载区块，导致服务端
        // 区块生成风暴和严重 TPS 掉刻（目标点在视距外时与经验现象完全吻合）。
        double viewRange = hitlRemoteConfigured
                ? Math.min(hitlControlRange, org.ywzj.rvp.config.RVP_HitlRemoteConfig.range())
                : resolveRadioLinkViewDistance();
        if (start.distanceToSqr(end) > viewRange * viewRange) {
            hitlLinkBlocked = true;
            hitlLinkBlockedTicks++;
            if (hitlLinkBlockedTicks >= 40) {
                hitlLinkSevered = true;
                hitlEnabled = false;
                clearTarget();
            }
            maybeSyncHitlLinkState();
            return;
        }
        boolean blockedNow;
        if (hitlRemoteConfigured && hitlRemoteView != null && level() instanceof ServerLevel serverLevel) {
            if (tickCount >= hitlRemoteNextRadioCheck) {
                var result = org.ywzj.rvp.server.hitl.RVP_HitlRadioPath.check(serverLevel, this, start, end);
                hitlRemoteRadioBlocked = org.ywzj.rvp.server.hitl.RVP_HitlRadioPath.blocks(
                        result, hitlRemoteView.isRequireLoadedRadioPath());
                hitlRemoteNextRadioCheck = tickCount + hitlRemoteView.getLinkCheckIntervalTicks();
                if (org.ywzj.rvp.debug.RVP_DebugFlags.HITL.isEnabled() && tickCount % 100 == 0) {
                    com.mojang.logging.LogUtils.getLogger().info(
                            "[RVP-HITL-STREAM] missile={} radioPath={} requireLoaded={} blocked={}",
                            getId(), result, hitlRemoteView.isRequireLoadedRadioPath(), hitlRemoteRadioBlocked);
                }
            }
            blockedNow = hitlRemoteRadioBlocked;
        } else if (isVirtualMidcourseTarget() && level() instanceof ServerLevel serverLevel) {
            // 普通电视弹转虚拟后也使用无加载射线；未启用远程模式时，未知路径不能冒充可通信。
            var result = org.ywzj.rvp.server.hitl.RVP_HitlRadioPath.check(serverLevel, this, start, end);
            blockedNow = org.ywzj.rvp.server.hitl.RVP_HitlRadioPath.blocks(result, true);
        } else {
            BlockHitResult hit = level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this));
            blockedNow = hit.getType() == HitResult.Type.BLOCK;
        }

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

    /**
     * 解析无线链路维持的最大直线距离（玩家可见视距）。
     * 服务端为权威：取玩家列表视距（区块数）×16 换算成格数，保证射线始终落在已加载
     * 区块内，杜绝 level().clip 逐 tick 同步加载视距外区块。任何异常回退 128 格安全值。
     */
    private double resolveRadioLinkViewDistance() {
        if (level() instanceof ServerLevel serverLevel) {
            int viewDistanceBlocks = serverLevel.getServer().getPlayerList().getViewDistance() * 16;
            if (viewDistanceBlocks > 0) {
                return viewDistanceBlocks;
            }
        }
        return 128.0D;
    }

    private void maybeSyncHitlLinkState() {
        syncHitlState(false);
    }

    /** C2S 接纳或拒绝后都回传真实结果，不能让客户端停留在乐观锁定状态。 */
    public void rvp$syncHitlState() {
        syncHitlState(true);
    }

    private void syncHitlState(boolean force) {
        // 普通导弹不发送 TV 状态，沿用显式 HITL 能力门。
        if (level().isClientSide() || rvpData == null || !rvpData.hasHumanInTheLoop()) return;
        boolean dircm = dircmJammed && dircmHitlTemporary;
        var lock = rvp$getHitlLockSnapshot();
        boolean lockChanged = !lock.sameIdentity(hitlLastSentLock)
                || !lock.equals(hitlLastSentLock) && tickCount - hitlLastSentLockTick >= 5;
        if (!force && !lockChanged && hitlLinkBlocked == hitlLinkLastSentBlocked
                && hitlLinkSevered == hitlLinkLastSentSevered && dircm == hitlLinkLastSentDircm
                && (!dircm || dircmJamRemainTick == hitlLinkLastSentDircmRemain)) return;
        hitlLinkLastSentBlocked = hitlLinkBlocked;
        hitlLinkLastSentSevered = hitlLinkSevered;
        hitlLinkLastSentDircm = dircm;
        hitlLinkLastSentDircmRemain = dircm ? dircmJamRemainTick : 0;
        hitlLastSentLock = lock;
        hitlLastSentLockTick = tickCount;
        if (getOwner() instanceof net.minecraft.server.level.ServerPlayer player) {
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    S2CHitlLinkState.of(getId(), hitlLinkBlocked, hitlLinkSevered,
                            dircm, dircmJamRemainTick, dircmHitlTemporary
                                    ? org.ywzj.rvp.dircm.RVP_DircmRuntimeManager.resolveHitlJamTotal(this)
                                    : 0, lock));
        }
    }

    /** 双端安全的只读快照；禁锁期间不泄露后台残留目标坐标。 */
    public org.ywzj.rvp.hitl.RVP_HitlLockSnapshot rvp$getHitlLockSnapshot() {
        var status = org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.NONE;
        if (dircmJammed || dircmNoRedesignateTick > 0) {
            return new org.ywzj.rvp.hitl.RVP_HitlLockSnapshot(
                    org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.DIRCM, -1, null, null, dircmNoRedesignateTick);
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.DESIGNATE || hitlLinkBlocked || hitlLinkSevered)
            return org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.EMPTY;
        Entity target = getTargetEntity();
        boolean tracked = target != null && target.isAlive();
        if (tracked) status = org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.ENTITY;
        else if (getTargetPos() != null) status = hitlPointStatus;
        return new org.ywzj.rvp.hitl.RVP_HitlLockSnapshot(status, tracked ? target.getId() : -1,
                getTargetPos(), tracked && target.getUUID().equals(hitlObbTargetUuid) ? hitlTargetObb : null, 0);
    }

    public org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status rvp$getHitlPointStatus() {
        return hitlPointStatus;
    }

    /** 脱锁时彻底断开实时实体/OBB/运动采样；保留的坐标只写一次。 */
    public void rvp$freezeHitlTarget(Vec3 point, org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status status) {
        hitlTargetObb = null;
        hitlObbTargetUuid = null;
        clearTarget();
        clearGuidanceMemory();
        hitlPointStatus = status;
        if (point != null) setTargetPos(point);
    }

    private void tickHitlMouseInertia() {
        if (!rvp$isHitlMouseSteering() || !rvp$isHitlActive()) {
            return;
        }
        float max = Math.max(hitlMaxTurnDegPerTick, 0.05f);
        // 虚拟摇杆（2026-09-10）：舵量目标 = 弹体当前朝向 + 客户端转向偏移。
        // 偏移保持 = 持续转向（摇杆拉住不放）；偏移回中 = 沿弹体方向直飞。
        // 修复"绝对航向"模型的缺陷：鼠标停顿即指令航向冻结、转弯率迅速归零直飞甩掉目标。
        float commandYaw = Mth.wrapDegrees(this.getYRot() + hitlInputYaw);
        float commandPitch = Mth.clamp(this.getXRot() + hitlInputPitch, -89.9f, 89.9f);
        hitlSteeringYaw = RVP_HitlSteeringMath.stepYawToward(hitlSteeringYaw, commandYaw, max);
        hitlSteeringPitch = RVP_HitlSteeringMath.stepPitchToward(hitlSteeringPitch, commandPitch, max);
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
        // 远程能力只接受仍有效的观察会话输入；未配置武器沿用原有流程。
        if (hitlRemoteConfigured && !org.ywzj.rvp.server.hitl.RVP_HitlRemoteViewService.isControlling(this)) return;
        if (hitlSignalSource == HitlSignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
            return;
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.MOUSE || seq <= hitlInputSeq) {
            return;
        }
        // 所有操控链都拒绝坏数值，虚拟状态不能把非法舵量存入后续实体恢复快照。
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        this.hitlInputYaw = yaw;
        this.hitlInputPitch = pitch;
        this.hitlInputSeq = seq;
        // 收到操作手有效转向输入即重置指令生命期：持续操控就持续可控，
        // 修复"绕大圈回打超过 hitl_max_control_tick 后舵量被静默冻结"（2026-09-10）
        ywzj_rvp$refreshHitlLife();
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        // 调用弹体存档，再保存 TV 骨身份，重载后不退回车体中心。
        super.addAdditionalSaveData(tag);
        tag.putBoolean("rvp_air_loss_timer_armed", airLossTimerArmed);
        if (hitlTargetObb != null && hitlObbTargetUuid != null) {
            tag.putString("rvp_hitl_target_bone", hitlTargetObb.bone());
            tag.putInt("rvp_hitl_target_cube", hitlTargetObb.index());
            tag.putUUID("rvp_hitl_target_uuid", hitlObbTargetUuid);
        }
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        airLossTimerArmed = tag.getBoolean("rvp_air_loss_timer_armed");
        hitlTargetObb = null; hitlObbTargetUuid = null;
        if (tag.hasUUID("rvp_hitl_target_uuid")) {
            String bone = tag.getString("rvp_hitl_target_bone");
            int index = tag.getInt("rvp_hitl_target_cube");
            if (!bone.isBlank() && bone.length() <= 128 && index >= 0 && index <= 4096) {
                hitlTargetObb = new org.ywzj.rvp.util.RVP_NamedVehicleObbs.Key(bone, index);
                hitlObbTargetUuid = tag.getUUID("rvp_hitl_target_uuid");
            }
        }
    }

    /** TV 选定的目标骨与目标 UUID，防止换目标后复用上一载具的骨。 */
    private org.ywzj.rvp.util.RVP_NamedVehicleObbs.Key hitlTargetObb;
    /** 上述骨所属目标身份。 */
    private java.util.UUID hitlObbTargetUuid;

    public Vec3 rvp$resolveHitlAimPoint(Entity target) {
        if (target instanceof AbstractVehicle vehicle && hitlTargetObb != null
                && target.getUUID().equals(hitlObbTargetUuid)) {
            // 调用单骨实时解析，炮塔转动后仍追踪同一块，失去该骨时保留最后坐标。
            var box = org.ywzj.rvp.util.RVP_NamedVehicleObbs.resolve(vehicle, hitlTargetObb);
            if (box != null) return box.center();
            if (getTargetPos() != null) return getTargetPos();
        }
        return org.ywzj.rvp.util.RVP_AimPointResolver.resolveLargestObbCenter(target);
    }

    public void rvp$setHitlDesignatedEntity(Entity target, org.ywzj.rvp.util.RVP_NamedVehicleObbs.Key key) {
        if (!canAcceptHitlDesignation() || !(target instanceof AbstractVehicle vehicle) || !target.isAlive()) return;
        // 先解析真实骨位置，再检查视线；不能先用实体中心拒绝可见的顶部 OBB。
        var box = org.ywzj.rvp.util.RVP_NamedVehicleObbs.resolve(vehicle, key);
        if (box == null) return;
        acceptHitlEntity(target, key, box.center());
    }

    private boolean canAcceptHitlDesignation() {
        // 复用既有控制会话、无线电与 DIRCM 权限门；不允许客户端坐标绕过禁锁。
        return (!hitlRemoteConfigured || org.ywzj.rvp.server.hitl.RVP_HitlRemoteViewService.isControlling(this))
                && (hitlSignalSource != HitlSignalSource.RADIO || !hitlLinkBlocked && !hitlLinkSevered)
                && !dircmJammed && dircmNoRedesignateTick <= 0 && hitlControlMode == RVP_EnumHitlControlMode.DESIGNATE;
    }

    private void acceptHitlEntity(Entity target, org.ywzj.rvp.util.RVP_NamedVehicleObbs.Key key, Vec3 point) {
        // 使用与飞行期间相同的反制门，只有通过后才能写入实时跟踪目标。
        if (!org.ywzj.rvp.guidance.RVP_HitlTvTargeting.validate(this, target, point, resolveNewActiveConfig())) return;
        hitlTargetObb = key;
        hitlObbTargetUuid = key == null ? null : target.getUUID();
        hitlPointStatus = org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.POINT;
        setTargetEntity(target);
        setTargetPos(point);
        ywzj_rvp$refreshHitlLife();
    }

    public void rvp$setHitlDesignatedTarget(Vec3 target) {
        if (!canAcceptHitlDesignation() || target == null) return;
        if (!org.ywzj.rvp.guidance.RVP_HitlTvTargeting.validate(this, null, target, resolveNewActiveConfig())) return;
        rvp$freezeHitlTarget(target, org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.POINT);
        ywzj_rvp$refreshHitlLife();
    }

    public void rvp$clearHitlDesignation() {
        if (!canAcceptHitlDesignation()) return;
        // 显式清除也清惯性记忆，否则画面无框而导弹继续追旧点。
        rvp$freezeHitlTarget(null, org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.NONE);
    }

    public void rvp$setHitlDesignatedEntity(Entity target) {
        if (!canAcceptHitlDesignation() || target == null || !target.isAlive()) return;
        acceptHitlEntity(target, null, org.ywzj.rvp.util.RVP_AimPointResolver.resolveLargestObbCenter(target));
    }

    /**
     * 重置指令生命期（2026-09-10）：收到操作手的有效输入（转向/指定目标）时把
     * {@code hitlLife} 恢复为上限 {@code hitlTimeoutTick}——持续操控即持续可控。
     * 修复"绕大圈回打超过 hitl_max_control_tick 后舵量静默冻结、导弹沿冻结航向自行
     * 画弧飞走"的 bug。仅 hitlEnabled 时生效（弹已弃置则不复活）。
     */
    private void ywzj_rvp$refreshHitlLife() {
        if (hitlEnabled) {
            this.hitlLife = this.hitlTimeoutTick;
        }
    }

    public void rvp$exitHitl() {
        this.hitlEnabled = false;
    }

    /** 该导弹配置为"右键提前引爆"模式且 HITL 制导仍激活（客户端据此决定右键动作）。 */
    public boolean rvp$isHitlRightClickDetonate() {
        return hitlEnabled && hitlRightClickDetonate;
    }

    /** 人在回路模式下玩家右键提前引爆（仅服务端）：在导弹当前位置走正常爆炸链。 */
    public void rvp$hitlDetonate() {
        // 远程能力只接受仍有效的观察会话输入；未配置武器沿用原有流程。
        if (hitlRemoteConfigured && !org.ywzj.rvp.server.hitl.RVP_HitlRemoteViewService.isControlling(this)) return;
        if (level().isClientSide() || !hitlEnabled) {
            return;
        }
        if (isVirtualMidcourseTarget()) {
            virtualHitlDetonationPending = true;
            return;
        }
        hitlEnabled = false;
        explodeAndDiscard(position());
    }

    /** 仅显式配置的两类 HITL 会话获得远程同步能力。 */
    public boolean rvp$isRemoteHitlConfigured() { return hitlRemoteConfigured; }

    /** 只暴露待处理事实，虚拟管理器不能在未加载位置直接引爆。 */
    public boolean hasVirtualHitlDetonationPending() { return virtualHitlDetonationPending; }

    /** 实体已经恢复并通过区块路径门后，执行原来已授权的右键引爆请求。 */
    public boolean tickVirtualHitlDetonation() {
        if (!virtualHitlDetonationPending) return false;
        virtualHitlDetonationPending = false;
        rvp$hitlDetonate();
        return !isAlive();
    }

    public boolean rvp$isHitlActive() {
        return hitlEnabled && hitlLife > 0;
    }

    /**
     * 线导视觉线激活判定（重写基类）：有人制导（HITL）时链路被切断/被方块遮挡即视为失去制导；
     * 非 HITL（如 MCLOS/SACLOS）沿用基类的引导段判定。
     */
    @Override
    protected boolean rvp$computeWireActive() {
        if (hitlEnabled) {
            if (hitlLinkSevered) {
                return false;
            }
            if (hitlSignalSource == HitlSignalSource.RADIO && hitlLinkBlocked) {
                return false;
            }
            return hitlLife > 0;
        }
        return super.rvp$computeWireActive();
    }

    public boolean rvp$isHitlMouseSteering() {
        return hitlEnabled && hitlControlMode == RVP_EnumHitlControlMode.MOUSE;
    }

    /** 保存电视操控和主动导引头的跨刻状态，配置参数仍由当前武器数据初始化。 */
    @Override
    public net.minecraft.nbt.CompoundTag saveVirtualRuntimeData() {
        // 调用基类运行态出口，保留反制、引信、波次和目标运动历史。
        var tag = super.saveVirtualRuntimeData();
        tag.putBoolean("virtualHitlDetonationPending", virtualHitlDetonationPending);
        tag.putInt("hitlLife", hitlLife);
        tag.putInt("hitlInputSeq", hitlInputSeq);
        tag.putInt("hitlLinkBlockedTicks", hitlLinkBlockedTicks);
        tag.putInt("hitlRemoteNextRadioCheck", hitlRemoteNextRadioCheck);
        tag.putInt("activeSeekerRelayLostTicks", activeSeekerRelayLostTicks);
        tag.putInt("relayLostSelfDestructTicks", relayLostSelfDestructTicks);
        tag.putFloat("hitlInputYaw", hitlInputYaw);
        tag.putFloat("hitlInputPitch", hitlInputPitch);
        tag.putFloat("hitlSteeringYaw", hitlSteeringYaw);
        tag.putFloat("hitlSteeringPitch", hitlSteeringPitch);
        tag.putBoolean("hitlEnabled", hitlEnabled);
        tag.putBoolean("hitlLinkBlocked", hitlLinkBlocked);
        tag.putBoolean("hitlLinkSevered", hitlLinkSevered);
        tag.putBoolean("hitlRemoteAttempted", hitlRemoteAttempted);
        tag.putBoolean("hitlRemoteRadioBlocked", hitlRemoteRadioBlocked);
        tag.putBoolean("activeSeekerRelayLost", activeSeekerRelayLost);
        tag.putBoolean("countermeasurePhaseActive", countermeasurePhaseActive);
        tag.putBoolean("airLossTimerArmed", airLossTimerArmed);
        tag.putString("hitlControlMode", hitlControlMode.name());
        tag.putString("hitlSignalSource", hitlSignalSource.name());
        // 保存远端新增的脱锁原因，防止虚拟态读档后把烟内冻结点重新当成普通点锁。
        tag.putString("hitlPointStatus", hitlPointStatus.name());
        // 调用真实/虚拟统一指定目标出口，虚拟拦截目标也必须保存稳定 UUID。
        Entity designated = rvp$getActiveSeekerDesignatedTargetEntity();
        if (designated != null) tag.putUUID("activeSeekerDesignatedUuid", designated.getUUID());
        if (hitlObbTargetUuid != null) tag.putUUID("hitlObbTargetUuid", hitlObbTargetUuid);
        if (hitlTargetObb != null) {
            tag.putString("hitlObbBone", hitlTargetObb.bone());
            tag.putInt("hitlObbIndex", hitlTargetObb.index());
        }
        return tag;
    }

    /** 恢复命令序号、舵量和导引头状态；会话授权仍由原服务管理器验证。 */
    @Override
    public void restoreVirtualRuntimeData(net.minecraft.nbt.CompoundTag tag) {
        // 调用基类恢复，不能因导弹子类重新建立目标而清掉速度/加速度历史。
        super.restoreVirtualRuntimeData(tag);
        if (tag.isEmpty()) return;
        virtualHitlDetonationPending = tag.getBoolean("virtualHitlDetonationPending");
        hitlLife = tag.getInt("hitlLife");
        hitlInputSeq = tag.getInt("hitlInputSeq");
        hitlLinkBlockedTicks = tag.getInt("hitlLinkBlockedTicks");
        hitlRemoteNextRadioCheck = tag.getInt("hitlRemoteNextRadioCheck");
        activeSeekerRelayLostTicks = tag.getInt("activeSeekerRelayLostTicks");
        relayLostSelfDestructTicks = tag.getInt("relayLostSelfDestructTicks");
        hitlInputYaw = tag.getFloat("hitlInputYaw");
        hitlInputPitch = tag.getFloat("hitlInputPitch");
        hitlSteeringYaw = tag.getFloat("hitlSteeringYaw");
        hitlSteeringPitch = tag.getFloat("hitlSteeringPitch");
        hitlEnabled = tag.getBoolean("hitlEnabled");
        hitlLinkBlocked = tag.getBoolean("hitlLinkBlocked");
        hitlLinkSevered = tag.getBoolean("hitlLinkSevered");
        hitlRemoteAttempted = tag.getBoolean("hitlRemoteAttempted");
        hitlRemoteRadioBlocked = tag.getBoolean("hitlRemoteRadioBlocked");
        activeSeekerRelayLost = tag.getBoolean("activeSeekerRelayLost");
        countermeasurePhaseActive = tag.getBoolean("countermeasurePhaseActive");
        airLossTimerArmed = tag.getBoolean("airLossTimerArmed");
        hitlControlMode = RVP_EnumHitlControlMode.valueOf(tag.getString("hitlControlMode"));
        hitlSignalSource = HitlSignalSource.valueOf(tag.getString("hitlSignalSource"));
        // 未记录脱锁原因的运行态使用字段默认值；已记录状态必须原样接续。
        hitlPointStatus = tag.contains("hitlPointStatus")
                ? org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.valueOf(tag.getString("hitlPointStatus"))
                : org.ywzj.rvp.hitl.RVP_HitlLockSnapshot.Status.POINT;
        Entity designated = resolveVirtualRuntimeEntity(tag, "activeSeekerDesignatedUuid");
        activeSeekerDesignatedTargetId = designated == null ? Integer.MIN_VALUE : designated.getId();
        hitlObbTargetUuid = tag.hasUUID("hitlObbTargetUuid") ? tag.getUUID("hitlObbTargetUuid") : null;
        hitlTargetObb = tag.contains("hitlObbBone") ? new org.ywzj.rvp.util.RVP_NamedVehicleObbs.Key(
                tag.getString("hitlObbBone"), tag.getInt("hitlObbIndex")) : null;
    }

    /** 纯显示代理没有生成包；仅建立镜头/HUD 所需的只读配置，不初始化飞行或建立服务端会话。 */
    public void initializeVirtualDisplayControls() {
        // 调用客户端安全配置出口，公共类不直接引用 Minecraft 等客户端类型。
        var data = getResolvedWeaponConfig();
        if (data != null && data.getGuidanceData() instanceof RVP_GuidanceDataHITL hitl && !hitlEnabled) {
            initNewSchemaHitl(data, hitl, new AimRot(getXRot(), getYRot()));
            hitlRemoteAttempted = true;
        }
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
        buffer.writeBoolean(hitlRightClickDetonate);
        buffer.writeBoolean(hitlRemoteConfigured);
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
            this.hitlSignalSource = buffer.readEnum(HitlSignalSource.class);
        }
        if (buffer.readableBytes() >= 1) {
            this.hitlRightClickDetonate = buffer.readBoolean();
        }
        this.hitlRemoteConfigured = buffer.readBoolean();
        this.hitlSteeringYaw = getYRot();
        this.hitlSteeringPitch = getXRot();
        this.hitlInputYaw = hitlSteeringYaw;
        this.hitlInputPitch = hitlSteeringPitch;
    }

    private enum HitlSignalSource {
        FIBER,
        RADIO
    }
}
