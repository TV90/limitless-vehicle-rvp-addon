package org.ywzj.rvp.entity.projectile;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PlayMessages;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;

/**
 * Generic guided missile entity for {@code rvp:missile}.
 */
public class RVP_MissileEntity extends RVP_BaseBullet {

    private static final float TV_MAX_TURN_DEG_PER_TICK = 3.0f;
    public static final int TV_MODE_COLOR = 1;
    public static final int TV_MODE_BW = 1 << 1;
    public static final int TV_MODE_THERMAL = 1 << 2;
    public static final int TV_MODE_ALL = TV_MODE_COLOR | TV_MODE_BW | TV_MODE_THERMAL;

    private float tvControlRange = 2000f;
    private int tvTimeoutTick = 200;
    private int tvLife = 200;
    private int tvVideoModeMask = TV_MODE_ALL;
    private int tvDefaultVideoMode = TV_MODE_COLOR;
    private boolean tvEnabled = true;
    private float tvInputYaw;
    private float tvInputPitch;
    private int tvInputSeq = Integer.MIN_VALUE;
    private int tvLastInputTick = Integer.MIN_VALUE;

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
        if (data != null && data.usesGuidanceType(RVP_EnumGuidanceType.TV)) {
            this.tvControlRange = data.getTVMissileControlRange();
            this.tvTimeoutTick = data.getTVMissileTimeoutTick();
            this.tvLife = this.tvTimeoutTick;
            this.tvVideoModeMask = data.getTVMissileVideoModeMask();
            this.tvDefaultVideoMode = data.getDefaultTVMissileVideoMode();
            this.tvEnabled = true;
            this.tvInputYaw = aim.yRot();
            this.tvInputPitch = aim.xRot();
        }
    }

    @Override
    protected void tickGuidance() {
        tickTVState();
        super.tickGuidance();
    }

    private void tickTVState() {
        if (rvpData == null || !rvpData.usesGuidanceType(RVP_EnumGuidanceType.TV)) {
            return;
        }
        if (!tvEnabled || tvLife <= 0) {
            clearTarget();
            return;
        }
        LivingEntity controller = getOwner() instanceof LivingEntity living ? living : null;
        if (controller == null || !controller.isAlive() || controller.isSpectator()) {
            clearTarget();
            return;
        }
        double maxRange = Math.max(tvControlRange, 1.0);
        if (controller.distanceToSqr(this) > maxRange * maxRange) {
            clearTarget();
            return;
        }
        tvLife--;
        float desiredYaw = this.getYRot();
        float desiredPitch = this.getXRot();
        if (tvLastInputTick != Integer.MIN_VALUE && tickCount - tvLastInputTick <= 40) {
            desiredYaw = tvInputYaw;
            desiredPitch = tvInputPitch;
        }
        float nextPitch = getXRot() + Mth.clamp(Mth.wrapDegrees(desiredPitch - getXRot()), -TV_MAX_TURN_DEG_PER_TICK, TV_MAX_TURN_DEG_PER_TICK);
        float nextYaw = getYRot() + Mth.clamp(Mth.wrapDegrees(desiredYaw - getYRot()), -TV_MAX_TURN_DEG_PER_TICK, TV_MAX_TURN_DEG_PER_TICK);
        setXRot(Mth.clamp(nextPitch, -89.9f, 89.9f));
        setYRot(Mth.wrapDegrees(nextYaw));
        xRotO = getXRot();
        yRotO = getYRot();
        setTargetPos(position().add(rvp$getTVLookDirection().scale(64.0)));
        setTargetEntity(null);
    }

    public int rvp$getTVMissileVideoModeMask() {
        return tvVideoModeMask;
    }

    public int rvp$getDefaultTVMissileVideoMode() {
        return tvDefaultVideoMode;
    }

    public void rvp$setTVMissileInput(float yaw, float pitch, int seq) {
        if (seq < tvInputSeq) {
            return;
        }
        this.tvInputYaw = yaw;
        this.tvInputPitch = pitch;
        this.tvInputSeq = seq;
        this.tvLastInputTick = tickCount;
    }

    public void rvp$exitTVMissile() {
        this.tvEnabled = false;
        clearTarget();
    }

    public Vec3 rvp$getTVLookDirection() {
        return VectorUtil.rotToVec(getXRot(), getYRot()).normalize();
    }

    public boolean rvp$isTVGuidanceActive() {
        return tvEnabled && tvLife > 0;
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        super.writeSpawnData(buffer);
        buffer.writeFloat(tvControlRange);
        buffer.writeVarInt(tvTimeoutTick);
        buffer.writeVarInt(tvLife);
        buffer.writeVarInt(tvVideoModeMask);
        buffer.writeVarInt(tvDefaultVideoMode);
        buffer.writeBoolean(tvEnabled);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        super.readSpawnData(buffer);
        this.tvControlRange = buffer.readFloat();
        this.tvTimeoutTick = buffer.readVarInt();
        this.tvLife = buffer.readVarInt();
        this.tvVideoModeMask = buffer.readVarInt();
        this.tvDefaultVideoMode = buffer.readVarInt();
        this.tvEnabled = buffer.readBoolean();
    }
}
