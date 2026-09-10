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
import org.ywzj.rvp.guidance.RVP_GuidanceTransitionContext;
import org.ywzj.rvp.guidance.RVP_HitlSteeringMath;
import org.ywzj.rvp.guidance.RVP_TvVideoModeMask;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
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
    private int hitlEnterViewResendTicks;
    /** 武器配置 {@code hitl_right_click_detonate}：HITL 视角下右键 = 提前引爆（客户端通过 spawn 数据读取）。 */
    private boolean hitlRightClickDetonate;
    private int activeSeekerDesignatedTargetId = Integer.MIN_VALUE;
    private boolean activeSeekerSupportReleased;

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

        getGuidancePhaseState().update(
                rvpData.getGuidanceData().getTerminalGuidance(),
                RVP_GuidanceTransitionContext.from(this)
        );

        RVP_EnumGuidanceType activeType = resolveNewActiveSeekerType();
        if (activeType == RVP_EnumGuidanceType.ARH || activeType == RVP_EnumGuidanceType.AIR) {
            tickActiveSeekerTargetManagement(activeType);
            // 主动导引头开机/锁定时向目标播报本体 RWR 告警（对标本体 MissileEntity.tickTrack）
            tickRwrMissileLaunchWarn(activeType);
        }
        // ARH / AIR / IR（含红外家族导引头）锁定目标时向目标播报 RVP 专用追踪告警
        tickMissileTrackAlert(activeType);

        super.tickGuidance();

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
        hitlEnterViewResendTicks--;
    }

    private RVP_EnumGuidanceType resolveNewActiveSeekerType() {
        if (rvpData == null) {
            return RVP_EnumGuidanceType.NONE;
        }
        return RVP_GuidanceModelResolver.resolveActive(
                rvpData.getGuidanceData(),
                getGuidancePhaseState().phase()
        ).guidanceType();
    }

    /** 当前生效的主动导引头/制导类型（供 gunner 等外部判断威胁类型，如 IR/AIR 红外族）。 */
    public RVP_EnumGuidanceType getActiveGuidanceType() {
        return resolveNewActiveSeekerType();
    }

    private void tickActiveSeekerTargetManagement(RVP_EnumGuidanceType type) {
        RVP_GuidanceActiveConfig config = resolveNewActiveConfig();
        if (config == null || config.guidanceType() != type) {
            return;
        }
        activeRadarActivationRange = config.activeRadarActivationRange();

        Entity designated = rvp$getActiveSeekerDesignatedTargetEntity();
        boolean hasDesignation = rvp$hasActiveSeekerDesignation();
        boolean supportAvailable = designated != null && rvp$hasActiveSeekerSupportForDesignatedTarget();
        if (hasDesignation && !supportAvailable) {
            activeSeekerSupportReleased = true;
        }

        if (!activeRadarCatch) {
            if (designated != null && !activeSeekerSupportReleased && supportAvailable) {
                setTargetEntity(designated);
                setTargetPos(designated.getBoundingBox().getCenter());
            } else if (activeSeekerSupportReleased) {
                setTargetEntity(null);
            }
        }

        if (!activeRadarOn) {
            Vec3 activationReference = targetPos != null ? targetPos : lastGuidancePos;
            boolean withinActivationRange = activeRadarActivationRange <= 0
                    || activationReference != null
                    && activationReference.distanceTo(position()) <= activeRadarActivationRange;
            if (withinActivationRange || !hasDesignation) {
                setAutonomousSeekerOn(true);
                notifyActiveSeekerOnline(type);
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
        // 仅导引头开机且有存活目标时才告警（对标本体 radar=true 分支）。
        if (!activeRadarOn || targetEntity == null || !targetEntity.isAlive()) {
            return;
        }
        // 对标本体 MissileEntity.java:400：每 2 tick 广播一次 MISSILE_LAUNCH 给跟踪目标的玩家。
        if (tickCount % 2 == 0) {
            ServerVehicleWarn packet = new ServerVehicleWarn(
                    this.getId(), targetEntity.getId(), WarnType.MISSILE_LAUNCH, "MSL");
            // 调用本体网络通道，向所有跟踪目标实体的玩家广播告警包。
            Channel.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> targetEntity), packet);
            // RVP 无钳制补发：直接向目标载具乘客发 RVP 告警包，客户端写入 targets 后
            // 即使目标相对本机俯仰角超 ±45°（本体 WarningReceiver 会丢弃）也能告警。
            if (targetEntity instanceof AbstractVehicle target) {
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
        if (level().isClientSide()) {
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
        if (!(getOwner() instanceof ServerPlayer player)) {
            return;
        }
        String message = switch (type) {
            case AIR -> "主动红外导引头开机";
            case ARH -> "主动雷达导引头开机";
            default -> null;
        };
        if (message != null) {
            player.displayClientMessage(Component.literal(message), true);
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
            // 无发动机配置的导弹：默认燃烧期取一半寿命
            int defaultBurn = Math.max(life / 2, 20);
            return getFlightTickCount() <= defaultBurn;
        }
        return super.isMotorBurning();
    }

    /** Captures the launch-designated target shared by the new ARH/AIR runtime. */
    public void rvp$setActiveSeekerDesignatedTarget(@Nullable Entity target) {
        if (target == null) {
            this.activeSeekerDesignatedTargetId = Integer.MIN_VALUE;
            this.activeSeekerSupportReleased = true;
            return;
        }
        this.activeSeekerDesignatedTargetId = target.getId();
        this.activeSeekerSupportReleased = false;
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
        Entity entity = level().getEntity(activeSeekerDesignatedTargetId);
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
                || activeSeekerSupportReleased;
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

        boolean anyRadarOn = false;
        for (RadarUnit radarUnit : root.getRadarUnits()) {
            if (!radarUnit.isOn()) {
                continue;
            }
            anyRadarOn = true;
            if (RVP_RadarRoleHelper.radarCurrentlyDetects(radarUnit, designatedTarget)
                    || radarUnit.getLockedEntity() == designatedTarget) {
                return true;
            }
        }

        if (root != null && shooterVehicle != null) {
            AbstractVehicle relayVehicle = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(shooterVehicle).orElse(null);
            RadarUnit relayRadar = RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar(relayVehicle);
            if (relayRadar != null && relayRadar.isOn()) {
                anyRadarOn = true;
                if (RVP_RadarRoleHelper.radarCurrentlyDetects(relayRadar, designatedTarget)
                        || relayRadar.getLockedEntity() == designatedTarget
                        || RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root) == designatedTarget.getId()) {
                    return true;
                }
            }
        }

        return anyRadarOn && targetEntity == designatedTarget && targetEntity.isAlive() && activeRadarCatch;
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
                getGuidancePhaseState().phase()
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
        double viewRange = resolveRadioLinkViewDistance();
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
        boolean dircm = dircmJammed && dircmHitlTemporary;
        if (hitlLinkBlocked == hitlLinkLastSentBlocked
                && hitlLinkSevered == hitlLinkLastSentSevered
                && dircm == hitlLinkLastSentDircm
                && (!dircm || dircmJamRemainTick == hitlLinkLastSentDircmRemain)) {
            return;
        }
        hitlLinkLastSentBlocked = hitlLinkBlocked;
        hitlLinkLastSentSevered = hitlLinkSevered;
        hitlLinkLastSentDircm = dircm;
        hitlLinkLastSentDircmRemain = dircm ? dircmJamRemainTick : 0;
        if (getOwner() instanceof net.minecraft.server.level.ServerPlayer player) {
            RVP_Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    S2CHitlLinkState.of(getId(), hitlLinkBlocked, hitlLinkSevered,
                            dircm, dircmJamRemainTick, dircmHitlTemporary
                                    ? org.ywzj.rvp.dircm.RVP_DircmRuntimeManager.resolveHitlJamTotal(this)
                                    : 0));
        }
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
        if (hitlSignalSource == HitlSignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
            return;
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.MOUSE || seq <= hitlInputSeq) {
            return;
        }
        this.hitlInputYaw = yaw;
        this.hitlInputPitch = pitch;
        this.hitlInputSeq = seq;
        // 收到操作手有效转向输入即重置指令生命期：持续操控就持续可控，
        // 修复"绕大圈回打超过 hitl_max_control_tick 后舵量被静默冻结"（2026-09-10）
        ywzj_rvp$refreshHitlLife();
    }

    public void rvp$setHitlDesignatedTarget(Vec3 target) {
        if (hitlSignalSource == HitlSignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
            return;
        }
        // 被 DIRCM 干扰期间/干扰后禁止重新指定期内：忽略操作员持续指定（保持断锁/对地直飞）
        if (dircmJammed || dircmNoRedesignateTick > 0) {
            return;
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.DESIGNATE || target == null) {
            return;
        }
        setTargetPos(target);
        setTargetEntity(null);
        // 指定目标同样是有效操作：重置指令生命期（与转向输入同语义）
        ywzj_rvp$refreshHitlLife();
    }

    public void rvp$clearHitlDesignation() {
        if (hitlSignalSource == HitlSignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
            return;
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.DESIGNATE) {
            return;
        }
        clearTarget();
    }

    public void rvp$setHitlDesignatedEntity(net.minecraft.world.entity.Entity target) {
        if (hitlSignalSource == HitlSignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
            return;
        }
        // 被 DIRCM 干扰期间/干扰后禁止重新指定期内：忽略操作员持续指定目标实体（保持断锁）
        if (dircmJammed || dircmNoRedesignateTick > 0) {
            return;
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.DESIGNATE || target == null || !target.isAlive()) {
            return;
        }
        setTargetEntity(target);
        setTargetPos(target.getBoundingBox().getCenter());
        // 指定目标实体同样是有效操作：重置指令生命期（与转向输入同语义）
        ywzj_rvp$refreshHitlLife();
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
        if (level().isClientSide() || !hitlEnabled) {
            return;
        }
        hitlEnabled = false;
        explodeAndDiscard(position());
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
