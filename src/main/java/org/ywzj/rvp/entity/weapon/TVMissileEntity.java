package org.ywzj.rvp.entity.weapon;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PlayMessages;
import org.ywzj.rvp.weapon.data.VehicleTVMissileWeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.weapon.MissileEntity;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

public class TVMissileEntity extends MissileEntity {

    private static final float TV_MISSILE_MAX_TURN_DEG_PER_TICK = 3.0f;

    private final float tvMissileControlRange;
    private final int tvMissileTimeoutTick;
    private int tvMissileVideoModeMask = VehicleTVMissileWeaponData.TV_MISSILE_MODE_COLOR
            | VehicleTVMissileWeaponData.TV_MISSILE_MODE_BW
            | VehicleTVMissileWeaponData.TV_MISSILE_MODE_THERMAL;
    private int tvMissileDefaultVideoMode = VehicleTVMissileWeaponData.TV_MISSILE_MODE_COLOR;
    private int tvMissileLife;
    private boolean tvMissileEnabled = true;

    private float tvMissileInputYaw;
    private float tvMissileInputPitch;
    private int tvMissileInputSeq = Integer.MIN_VALUE;
    private int tvMissileLastInputTick = Integer.MIN_VALUE;

    public TVMissileEntity(EntityType<? extends Projectile> entityType, Level level) {
        super(entityType, level);
        this.tvMissileControlRange = 2000f;
        this.tvMissileTimeoutTick = 200;
        this.tvMissileLife = this.tvMissileTimeoutTick;
    }

    public TVMissileEntity(PlayMessages.SpawnEntity msg, Level level) {
        super(msg, level);
        this.tvMissileControlRange = 2000f;
        this.tvMissileTimeoutTick = 200;
        this.tvMissileLife = this.tvMissileTimeoutTick;
    }

    public TVMissileEntity(EntityType<? extends Projectile> entityType, Level level, VehicleTVMissileWeaponData data, WeaponUnit weaponUnit) {
        super(entityType, level, data, weaponUnit);
        this.tvMissileControlRange = Math.max(1f, data.getTVMissileControlRange());
        this.tvMissileTimeoutTick = Math.max(1, data.getTVMissileTimeoutTick());
        this.tvMissileVideoModeMask = data.getTVMissileVideoModeMask();
        this.tvMissileDefaultVideoMode = data.getDefaultTVMissileVideoMode();
        this.tvMissileLife = this.tvMissileTimeoutTick;
    }

    @Override
    public void tick() {
        super.tick();
    }

    public void ywzj_rvp$setTVMissileInput(float yaw, float pitch, int seq) {
        if (seq < this.tvMissileInputSeq) {
            return;
        }
        this.tvMissileInputYaw = yaw;
        this.tvMissileInputPitch = pitch;
        this.tvMissileInputSeq = seq;
        this.tvMissileLastInputTick = this.tickCount;
    }

    public void ywzj_rvp$exitTVMissile() {
        this.tvMissileEnabled = false;
        this.targetPos = null;
        this.targetEntity = null;
    }

    public int ywzj_rvp$getTVMissileVideoModeMask() {
        return tvMissileVideoModeMask;
    }

    public int ywzj_rvp$getDefaultTVMissileVideoMode() {
        return tvMissileDefaultVideoMode;
    }

    public void ywzj_rvp$manualGuidanceTick() {
        if (this.tickCount < this.ignitionDelayTick) {
            return;
        }
        if (!tvMissileEnabled) {
            this.targetPos = null;
            this.targetEntity = null;
            return;
        }
        LivingEntity controller = getController();
        if (controller == null) {
            this.targetPos = null;
            this.targetEntity = null;
            return;
        }
        if (tvMissileLife <= 0) {
            this.targetPos = null;
            this.targetEntity = null;
            return;
        }
        tvMissileLife -= 1;

        double maxRange = Math.max(1.0, tvMissileControlRange);
        if (controller.distanceToSqr(this) > maxRange * maxRange) {
            this.targetPos = null;
            this.targetEntity = null;
            return;
        }

        float desiredYaw = controller.getYRot();
        float desiredPitch = controller.getXRot();
        if (this.tvMissileLastInputTick != Integer.MIN_VALUE
                && this.tickCount - this.tvMissileLastInputTick <= 40) {
            desiredYaw = this.tvMissileInputYaw;
            desiredPitch = this.tvMissileInputPitch;
        }

        // Rate-limited but direct TV steering: no chase/intercept lag, only a hard turn-rate cap.
        float curPitch = this.getXRot();
        float curYaw = this.getYRot();
        float nextPitch = curPitch + Mth.clamp(Mth.wrapDegrees(desiredPitch - curPitch), -TV_MISSILE_MAX_TURN_DEG_PER_TICK, TV_MISSILE_MAX_TURN_DEG_PER_TICK);
        float nextYaw = curYaw + Mth.clamp(Mth.wrapDegrees(desiredYaw - curYaw), -TV_MISSILE_MAX_TURN_DEG_PER_TICK, TV_MISSILE_MAX_TURN_DEG_PER_TICK);
        this.setXRot(Mth.clamp(nextPitch, -89.9f, 89.9f));
        this.setYRot(Mth.wrapDegrees(nextYaw));
        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();

        Vec3 holdDir = VectorUtil.rotToVec(this.getXRot(), this.getYRot()).normalize();
        if (holdDir.lengthSqr() < 1.0E-6) {
            holdDir = this.getLookAngle();
        }
        this.targetPos = this.position().add(holdDir.scale(64.0));
        this.targetEntity = null;
    }

    public void ywzj_rvp$manualMoveTickNoGravity() {
        if (this.targetVec != null && this.targetPos != null && this.position().distanceTo(this.targetPos) < 5f) {
            this.targetPos = VectorUtil.hitPosition(this, this.targetPos, this.targetPos.add(this.targetVec.scale(256)));
        }
        Vec3 velocity = this.getDeltaMovement();
        if (this.tickCount >= this.ignitionDelayTick) {
            Vec3 lookDir = this.getLookAngle();
            int motorTick = this.tickCount - this.ignitionDelayTick;
            double speed = Math.max(velocity.length(), this.referenceSpeed * 0.35f);
            if (motorTick <= this.motorBurnTime) {
                speed += this.thrust / this.mass;
            }
            if (speed > 0) {
                speed -= this.dragCoefficient * speed * speed;
                speed = Math.max(speed, 0.01);
            }
            velocity = lookDir.scale(speed);
        }
        if (this.tickCount < this.ignitionDelayTick) {
            velocity = this.vehicle.getDeltaMovement().add(0, -1, 0);
        }
        this.setDeltaMovement(velocity);
        this.setPos(this.getX() + velocity.x, this.getY() + velocity.y, this.getZ() + velocity.z);
        if (this.tickCount >= this.ignitionDelayTick) {
            int motorTick = this.tickCount - this.ignitionDelayTick;
            if (motorTick > this.motorBurnTime && this.targetEntity == null && this.targetPos == null && velocity.lengthSqr() > 0.01) {
                Vec3 normVel = velocity.normalize();
                double pitch = Math.toDegrees(-Math.asin(normVel.y));
                double yaw = Math.toDegrees(Math.atan2(normVel.z, normVel.x)) - 90.0;
                this.setXRot((float) Mth.lerp(0.2, this.getXRot(), pitch));
                this.setYRot((float) Mth.lerp(0.2, this.getYRot(), yaw));
            }
        }
    }

    private LivingEntity getController() {
        Entity owner = getOwner();
        if (!(owner instanceof LivingEntity living)) {
            return null;
        }
        if (!living.isAlive() || living.isSpectator()) {
            return null;
        }
        if (living.getVehicle() instanceof AbstractVehicle) {
            return living;
        }
        return null;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag data) {
        super.addAdditionalSaveData(data);
        data.putInt("RvpTVMissileVideoModeMask", tvMissileVideoModeMask);
        data.putInt("RvpTVMissileDefaultVideoMode", tvMissileDefaultVideoMode);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag data) {
        super.readAdditionalSaveData(data);
        if (data.contains("RvpTVMissileVideoModeMask")) {
            tvMissileVideoModeMask = data.getInt("RvpTVMissileVideoModeMask");
        }
        if (data.contains("RvpTVMissileDefaultVideoMode")) {
            tvMissileDefaultVideoMode = data.getInt("RvpTVMissileDefaultVideoMode");
        }
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        super.writeSpawnData(buffer);
        buffer.writeInt(tvMissileVideoModeMask);
        buffer.writeInt(tvMissileDefaultVideoMode);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf additionalData) {
        super.readSpawnData(additionalData);
        tvMissileVideoModeMask = additionalData.readInt();
        tvMissileDefaultVideoMode = additionalData.readInt();
    }
}
