package org.ywzj.rvp.entity.projectile;

import net.minecraft.network.FriendlyByteBuf;
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
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataHITL;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.rvp.ext.WeaponUnitExternalRadarLockExt;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
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
    private HitlSignalSource hitlSignalSource = HitlSignalSource.RADIO;
    private boolean hitlLinkBlocked;
    private boolean hitlLinkSevered;
    private int hitlLinkBlockedTicks;
    private boolean hitlLinkLastSentBlocked;
    private boolean hitlLinkLastSentSevered;
    private int hitlEnterViewResendTicks;
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
    public void initFromWeapon(RVP_WeaponData data, RVP_EnumWeaponKind kind, AbstractVehicle vehicle, LivingEntity shooter,
                               Vec3 spawnPos, AimRot aim, Vec3 initialMotion) {
        super.initFromWeapon(data, kind, vehicle, shooter, spawnPos, aim, initialMotion);
        if (data == null
                || !(data.getGuidanceData() instanceof RVP_GuidanceDataHITL hitl)
                || !hitl.isHitlEnabled()) {
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
        this.hitlEnabled = true;
        this.hitlEnterViewResendTicks = 5;
        this.hitlInputYaw = aim.yRot();
        this.hitlInputPitch = aim.xRot();
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

    private RVP_EnumGuidanceType resolveNewActiveSeekerType() {
        if (rvpData == null) {
            return RVP_EnumGuidanceType.NONE;
        }
        return RVP_GuidanceModelResolver.resolveActive(
                rvpData.getGuidanceData(),
                getGuidancePhaseState().phase()
        ).guidanceType();
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

        if (!activeRadarOn && tickCount >= 20) {
            Vec3 activationReference = targetPos != null ? targetPos : lastGuidancePos;
            boolean withinActivationRange = activeRadarActivationRange <= 0
                    || activationReference != null
                    && activationReference.distanceTo(position()) <= activeRadarActivationRange;
            if (withinActivationRange || !hasDesignation) {
                activeRadarOn = true;
            }
        }

        if (activeRadarOn && targetEntity == null) {
            activeRadarLostTargetTick++;
            if (activeRadarLostTargetTick >= 60) {
                life = 0;
            }
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

        if (root instanceof WeaponUnitExternalRadarLockExt ext && shooterVehicle != null) {
            AbstractVehicle relayVehicle = RVP_ExternalRadarLinkHelper.getLinkedRelayVehicle(shooterVehicle).orElse(null);
            RadarUnit relayRadar = RVP_ExternalRadarLinkHelper.getPreferredRelayLockRadar(relayVehicle);
            if (relayRadar != null && relayRadar.isOn()) {
                anyRadarOn = true;
                if (RVP_RadarRoleHelper.radarCurrentlyDetects(relayRadar, designatedTarget)
                        || relayRadar.getLockedEntity() == designatedTarget
                        || ext.ywzj_rvp$getExternalRadarLockedEntityId() == designatedTarget.getId()) {
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
        if (hitlSignalSource == HitlSignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
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
        if (hitlSignalSource == HitlSignalSource.RADIO && (hitlLinkBlocked || hitlLinkSevered)) {
            return;
        }
        if (hitlControlMode != RVP_EnumHitlControlMode.DESIGNATE || target == null) {
            return;
        }
        setTargetPos(target);
        setTargetEntity(null);
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
            this.hitlSignalSource = buffer.readEnum(HitlSignalSource.class);
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
