package org.ywzj.rvp.entity.projectile;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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
import org.ywzj.rvp.guidance.RVP_EnumHitlControlMode;
import org.ywzj.rvp.guidance.RVP_HitlSeekerUtil;
import org.ywzj.rvp.guidance.RVP_HitlSteeringMath;
import org.ywzj.rvp.guidance.RVP_TvVideoModeMask;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CHitlLinkState;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_HumanInTheLoopData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

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
        this.hitlInputYaw = aim.yRot();
        this.hitlInputPitch = aim.xRot();
        this.hitlSteeringYaw = aim.yRot();
        this.hitlSteeringPitch = aim.xRot();
    }

    @Override
    protected void tickGuidance() {
        tickHitlRadioLink();
        if (hitlLinkSevered || hitlLinkBlocked) {
            return;
        }
        tickHitlSession();
        tickHitlMouseInertia();
        super.tickGuidance();
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
